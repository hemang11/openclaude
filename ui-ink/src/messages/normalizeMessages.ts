import type { BackendSnapshot, PromptToolEvent, SessionMessageView } from '../../../types/stdio/protocol.ts'
import {
  buildMessageLookups,
  getOrderedSiblingToolUseIdsFromLookup,
  getSiblingToolUseIdsFromLookup,
  hasUnresolvedHooksFromLookup,
  type MessageLookups,
} from './buildMessageLookups.ts'
import { collapseReadSearchGroups } from './collapseReadSearchGroups.ts'
import { groupToolUses } from './groupToolUses.ts'
import type {
  AssistantContentBlock,
  RenderableAssistantMessage,
  RenderableGroupedToolMessage,
  RenderableMessage,
  ToolBlockStatus,
} from './types.ts'

type NormalizeArgs = {
  snapshot: BackendSnapshot | null
  limit?: number
}

type AssistantAccumulator = {
  message: RenderableAssistantMessage
}

type ToolMessageState = Omit<RenderableGroupedToolMessage, 'type' | 'createdAt'>

export function normalizeRenderableMessages({
  snapshot,
  limit,
}: NormalizeArgs): RenderableMessage[] {
  const orderedMessages = reorderMessagesForUI(snapshot?.messages ?? [])
  const renderable: RenderableMessage[] = []
  const lookups = buildMessageLookups(orderedMessages)
  const emittedToolUseIds = new Set<string>()
  let assistantTurn: AssistantAccumulator | null = null

  for (const message of orderedMessages) {
    switch (message.kind) {
      case 'thinking':
      case 'assistant':
        assistantTurn ??= createAssistantAccumulator({
          id: message.id,
          createdAt: message.createdAt,
          providerId: message.providerId,
          modelId: message.modelId,
          live: false,
        })
        appendAssistantContent(assistantTurn, message)
        break
      case 'tool_use':
        if (assistantTurn) {
          flushAssistantTurn(renderable, assistantTurn)
          assistantTurn = null
        }
        emitSiblingToolMessages(renderable, emittedToolUseIds, message, lookups)
        break
      case 'tool':
      case 'tool_result': {
        if (assistantTurn) {
          flushAssistantTurn(renderable, assistantTurn)
          assistantTurn = null
        }
        const toolUseId = message.toolId ?? message.id
        if (lookups.toolUseById.has(toolUseId)) {
          break
        }
        emitToolMessage(renderable, emittedToolUseIds, createGroupedToolMessageFromOrphan(message))
        break
      }
      default: {
        if (assistantTurn) {
          flushAssistantTurn(renderable, assistantTurn)
          assistantTurn = null
        }
        if (isToolScopedHookMessage(message, lookups)) {
          break
        }
        const normalized = normalizeNonAssistantMessage(message)
        if (normalized) {
          renderable.push(normalized)
        }
      }
    }
  }

  if (assistantTurn) {
    flushAssistantTurn(renderable, assistantTurn)
  }

  const normalized = collapseReadSearchGroups(groupToolUses(renderable, lookups))
  if (typeof limit === 'number' && Number.isFinite(limit) && limit >= 0) {
    return normalized.slice(-limit)
  }
  return normalized
}

type ToolUseGroup = {
  toolUse: SessionMessageView | null
  preHooks: SessionMessageView[]
  toolEvents: SessionMessageView[]
  toolResult: SessionMessageView | null
  postHooks: SessionMessageView[]
}

export function reorderMessagesForUI(messages: SessionMessageView[]): SessionMessageView[] {
  const toolUseGroups = new Map<string, ToolUseGroup>()

  for (const message of messages) {
    if (message.kind === 'tool_use') {
      const toolUseId = message.toolId ?? message.id
      const existing = toolUseGroups.get(toolUseId) ?? {
        toolUse: null,
        preHooks: [],
        toolEvents: [],
        toolResult: null,
        postHooks: [],
      }
      existing.toolUse = message
      toolUseGroups.set(toolUseId, existing)
      continue
    }

    if (message.kind === 'tool') {
      const toolUseId = message.toolId ?? message.id
      const existing = toolUseGroups.get(toolUseId) ?? {
        toolUse: null,
        preHooks: [],
        toolEvents: [],
        toolResult: null,
        postHooks: [],
      }
      existing.toolEvents = [...existing.toolEvents, message]
      toolUseGroups.set(toolUseId, existing)
      continue
    }

    if (message.kind === 'tool_result') {
      const toolUseId = message.toolId ?? message.id
      const existing = toolUseGroups.get(toolUseId) ?? {
        toolUse: null,
        preHooks: [],
        toolEvents: [],
        toolResult: null,
        postHooks: [],
      }
      existing.toolResult = message
      toolUseGroups.set(toolUseId, existing)
      continue
    }

    if (
      message.kind === 'progress'
      && message.toolId
      && message.toolId.trim()
      && message.hookEvent
      && message.hookEvent.trim()
      && (message.phase === 'hook_started' || message.phase === 'hook_progress' || message.phase === 'hook_response')
    ) {
      const toolUseId = message.toolId.trim()
      const existing = toolUseGroups.get(toolUseId) ?? {
        toolUse: null,
        preHooks: [],
        toolEvents: [],
        toolResult: null,
        postHooks: [],
      }
      if (message.hookEvent === 'PreToolUse' || message.hookEvent === 'PermissionRequest') {
        existing.preHooks = [...existing.preHooks, message]
      } else {
        existing.postHooks = [...existing.postHooks, message]
      }
      toolUseGroups.set(toolUseId, existing)
      continue
    }

    if (
      message.kind === 'attachment'
      && message.attachmentKind === 'hook_additional_context'
      && message.toolId
      && message.toolId.trim()
      && (message.hookEvent === 'PreToolUse' || message.hookEvent === 'PostToolUse')
    ) {
      const toolUseId = message.toolId.trim()
      const existing = toolUseGroups.get(toolUseId) ?? {
        toolUse: null,
        preHooks: [],
        toolEvents: [],
        toolResult: null,
        postHooks: [],
      }
      if (message.hookEvent === 'PreToolUse') {
        existing.preHooks = [...existing.preHooks, message]
      } else {
        existing.postHooks = [...existing.postHooks, message]
      }
      toolUseGroups.set(toolUseId, existing)
    }
  }

  const reordered: SessionMessageView[] = []
  const processedToolUseIds = new Set<string>()

  for (const message of messages) {
    if (message.kind === 'tool_use') {
      const toolUseId = message.toolId ?? message.id
      if (processedToolUseIds.has(toolUseId)) {
        continue
      }
      processedToolUseIds.add(toolUseId)
      const group = toolUseGroups.get(toolUseId)
      if (group?.toolUse) {
        reordered.push(group.toolUse)
        reordered.push(...group.preHooks)
        reordered.push(...group.toolEvents)
        if (group.toolResult) {
          reordered.push(group.toolResult)
        }
        reordered.push(...group.postHooks)
      } else {
        reordered.push(message)
      }
      continue
    }

    if (message.kind === 'tool' || message.kind === 'tool_result') {
      const toolUseId = message.toolId ?? message.id
      if (toolUseGroups.get(toolUseId)?.toolUse) {
        continue
      }
    }

    if (
      message.kind === 'progress'
      && message.toolId
      && message.toolId.trim()
      && message.hookEvent
      && message.hookEvent.trim()
      && (message.phase === 'hook_started' || message.phase === 'hook_progress' || message.phase === 'hook_response')
      && toolUseGroups.get(message.toolId.trim())?.toolUse
    ) {
      continue
    }

    if (
      message.kind === 'attachment'
      && message.attachmentKind === 'hook_additional_context'
      && message.toolId
      && message.toolId.trim()
      && (message.hookEvent === 'PreToolUse' || message.hookEvent === 'PostToolUse')
      && toolUseGroups.get(message.toolId.trim())?.toolUse
    ) {
      continue
    }

    reordered.push(message)
  }

  return reordered
}

export function createLiveRenderableMessages(args: {
  liveAssistantText: string
  liveReasoningText: string
  liveToolCalls: PromptToolEvent[]
  fallbackProviderId?: string | null
  fallbackModelId?: string | null
}): RenderableMessage[] {
  const { liveAssistantText, liveReasoningText, liveToolCalls, fallbackProviderId, fallbackModelId } = args
  const createdAt = new Date().toISOString()
  const messages: RenderableMessage[] = []

  if (liveReasoningText.trim()) {
    messages.push(createAssistantMessage({
      id: 'live-thinking-turn',
      createdAt,
      live: true,
      providerId: fallbackProviderId,
      modelId: fallbackModelId,
      blocks: [{
        type: 'thinking',
        id: 'live-thinking',
        text: liveReasoningText,
        live: true,
      }],
    }))
  }

  for (const toolCall of liveToolCalls) {
    messages.push({
      type: 'grouped_tool',
      id: `live-tool-${toolCall.toolId}`,
      createdAt,
      assistantMessageId: null,
      toolGroupKey: null,
      toolId: toolCall.toolId,
      toolIds: [toolCall.toolId],
      siblingToolIds: [toolCall.toolId],
      toolName: toolCall.toolName,
      toolRenderClass: null,
      status: toolStatus(toolCall.phase),
      commands: compactValues([firstNonBlank(toolCall.command)]),
      details: compactValues([normalizeToolDetail(toolCall.phase, toolCall.text ?? '')]),
      resultTexts:
        toolCall.phase === 'completed' || toolCall.phase === 'failed'
          ? compactValues([normalizeToolResultText(toolCall.phase, toolCall.text ?? '')])
          : [],
      command: firstNonBlank(toolCall.command),
      detail: firstNonBlank(
        summarizeInput(toolCall.command, ''),
        normalizeToolDetail(toolCall.phase, toolCall.text ?? ''),
      ),
      resultText:
        toolCall.phase === 'completed' || toolCall.phase === 'failed'
          ? normalizeToolResultText(toolCall.phase, toolCall.text ?? '')
          : null,
      permissionPending: false,
      live: true,
    })
  }

  if (liveAssistantText.trim()) {
    messages.push(createAssistantMessage({
      id: 'live-text-turn',
      createdAt,
      live: true,
      providerId: fallbackProviderId,
      modelId: fallbackModelId,
      blocks: [{
        type: 'text',
        id: 'live-text',
        text: liveAssistantText,
        live: true,
      }],
    }))
  }

  return messages
}

function createAssistantAccumulator(args: {
  id: string
  createdAt: string
  providerId?: string | null
  modelId?: string | null
  live: boolean
}): AssistantAccumulator {
  return {
    message: createAssistantMessage({
      id: args.id,
      createdAt: args.createdAt,
      providerId: args.providerId,
      modelId: args.modelId,
      live: args.live,
      blocks: [],
    }),
  }
}

function createAssistantMessage(args: {
  id: string
  createdAt: string
  providerId?: string | null
  modelId?: string | null
  live: boolean
  blocks: AssistantContentBlock[]
}): RenderableAssistantMessage {
  return {
    type: 'assistant',
    id: args.id,
    createdAt: args.createdAt,
    providerId: args.providerId as RenderableAssistantMessage['providerId'],
    modelId: args.modelId ?? null,
    live: args.live,
    blocks: args.blocks,
  }
}

function appendAssistantContent(accumulator: AssistantAccumulator, message: SessionMessageView): void {
  switch (message.kind) {
    case 'thinking':
      if (message.text.trim()) {
        accumulator.message.blocks.push({
          type: 'thinking',
          id: message.id,
          text: message.text,
          live: false,
        })
      }
      return
    case 'assistant':
      if (message.text.trim()) {
        accumulator.message.blocks.push({
          type: 'text',
          id: message.id,
          text: message.text,
          live: false,
        })
      }
      return
  }
}

function normalizeNonAssistantMessage(message: SessionMessageView): RenderableMessage | null {
  switch (message.kind) {
    case 'user':
      return {
        type: 'user',
        id: message.id,
        createdAt: message.createdAt,
        text: message.text,
      }
    case 'system':
    case 'progress':
    case 'attachment':
    case 'compact_boundary':
    case 'compact_summary':
    case 'tombstone':
      return {
        type: message.kind,
        id: message.id,
        createdAt: message.createdAt,
        text: message.text,
        attachmentKind: message.attachmentKind,
        hookEvent: message.hookEvent,
        source: message.source,
        reason: message.reason,
      }
    default:
      return null
  }
}

function isToolScopedHookMessage(message: SessionMessageView, lookups: MessageLookups): boolean {
  if (!message.toolId || !message.toolId.trim() || !lookups.toolUseById.has(message.toolId.trim())) {
    return false
  }

  if (
    message.kind === 'progress'
    && message.hookEvent
    && message.hookEvent.trim()
    && (message.phase === 'hook_started' || message.phase === 'hook_progress' || message.phase === 'hook_response')
  ) {
    return true
  }

  if (
    message.kind === 'attachment'
    && message.attachmentKind === 'hook_additional_context'
    && message.hookEvent
    && message.hookEvent.trim()
  ) {
    return true
  }

  return false
}

function createGroupedToolMessageFromLookup(
  toolUseMessage: SessionMessageView,
  lookups: MessageLookups,
): RenderableGroupedToolMessage {
  const toolUseId = toolUseMessage.toolId ?? toolUseMessage.id
  const events = lookups.toolEventsById.get(toolUseId) ?? []
  const result = lookups.toolResultById.get(toolUseId) ?? null
  const lastEvent = events.length > 0 ? events[events.length - 1] : null
  const summarizedInput = summarizeInput(
    firstNonBlank(lastEvent?.command, toolUseMessage.command),
    toolUseMessage.inputJson ?? toolUseMessage.text,
  )
  const commandValues = compactValues([
    lastEvent?.command,
    toolUseMessage.command,
    summarizedInput,
  ])
  const detailValues = compactValues([
    summarizedInput,
    normalizeToolDetail(lastEvent?.phase ?? toolUseMessage.phase ?? 'started', lastEvent?.text ?? ''),
  ])
  const resultValues = compactValues(
    result != null
      ? [normalizeToolResultText(result.phase ?? 'completed', result.displayText ?? result.text)]
      : lastEvent != null && (lastEvent.phase === 'completed' || lastEvent.phase === 'failed')
        ? [normalizeToolResultText(lastEvent.phase ?? 'completed', lastEvent.text)]
        : [],
  )
  const toolState: ToolMessageState = {
    id: toolUseMessage.id,
    assistantMessageId: toolUseMessage.assistantMessageId ?? lookups.assistantMessageIdByToolUseId.get(toolUseId) ?? null,
    toolGroupKey: toolUseMessage.toolGroupKey ?? lookups.toolGroupKeyByToolUseId.get(toolUseId) ?? null,
    toolId: toolUseId,
    toolIds: [toolUseId],
    siblingToolIds: Array.from(getSiblingToolUseIdsFromLookup(lookups, toolUseId)),
    toolName: toolUseMessage.toolName ?? lastEvent?.toolName ?? result?.toolName ?? 'tool',
    toolRenderClass: toolUseMessage.toolRenderClass ?? lastEvent?.toolRenderClass ?? result?.toolRenderClass ?? null,
    status: resolveToolStatus(toolUseMessage, lastEvent, result, lookups),
    commands: commandValues,
    details: detailValues,
    resultTexts: resultValues,
    command: commandValues[0] ?? null,
    detail: detailValues[0] ?? null,
    resultText: resultValues[0] ?? null,
    permissionPending: result == null && lastEvent?.phase === 'permission_requested',
    live: false,
  }

  return {
    type: 'grouped_tool',
    createdAt: toolUseMessage.createdAt,
    ...toolState,
  }
}

function createGroupedToolMessageFromOrphan(
  message: SessionMessageView,
): RenderableGroupedToolMessage {
  return {
    type: 'grouped_tool',
    createdAt: message.createdAt,
    id: message.id,
    assistantMessageId: message.assistantMessageId ?? null,
    toolGroupKey: message.toolGroupKey ?? null,
    toolId: message.toolId ?? message.id,
    toolIds: [message.toolId ?? message.id],
    siblingToolIds: [message.toolId ?? message.id],
    toolName: message.toolName ?? 'tool',
    toolRenderClass: message.toolRenderClass ?? null,
    status: toolStatus(message.phase ?? 'status'),
    commands: compactValues([message.command]),
    details: compactValues([
      message.kind === 'tool' ? normalizeToolDetail(message.phase ?? 'status', message.text) : null,
    ]),
    resultTexts: compactValues([
      message.kind === 'tool_result' || message.phase === 'completed' || message.phase === 'failed'
        ? normalizeToolResultText(message.phase ?? 'completed', message.displayText ?? message.text)
        : null,
    ]),
    command: message.command ?? null,
    detail: message.kind === 'tool' ? normalizeToolDetail(message.phase ?? 'status', message.text) : null,
    resultText:
      message.kind === 'tool_result' || message.phase === 'completed' || message.phase === 'failed'
        ? normalizeToolResultText(message.phase ?? 'completed', message.displayText ?? message.text)
        : null,
    permissionPending: (message.phase ?? '') === 'permission_requested',
    live: false,
  }
}

function emitToolMessage(
  renderable: RenderableMessage[],
  emittedToolUseIds: Set<string>,
  message: RenderableGroupedToolMessage,
): void {
  if (emittedToolUseIds.has(message.toolId)) {
    return
  }
  emittedToolUseIds.add(message.toolId)
  renderable.push(message)
}

function emitSiblingToolMessages(
  renderable: RenderableMessage[],
  emittedToolUseIds: Set<string>,
  toolUseMessage: SessionMessageView,
  lookups: MessageLookups,
): void {
  const toolUseId = toolUseMessage.toolId ?? toolUseMessage.id
  const siblingToolUseIds = getOrderedSiblingToolUseIdsFromLookup(lookups, toolUseId)

  for (const siblingToolUseId of siblingToolUseIds) {
    if (emittedToolUseIds.has(siblingToolUseId)) {
      continue
    }

    const siblingToolUseMessage = lookups.toolUseById.get(siblingToolUseId)
    if (!siblingToolUseMessage) {
      continue
    }

    emitToolMessage(
      renderable,
      emittedToolUseIds,
      createGroupedToolMessageFromLookup(siblingToolUseMessage, lookups),
    )
  }
}

function resolveToolStatus(
  toolUseMessage: SessionMessageView,
  lastEvent: SessionMessageView | null,
  result: SessionMessageView | null,
  lookups: MessageLookups,
): ToolBlockStatus {
  const toolUseId = toolUseMessage.toolId ?? toolUseMessage.id
  const hasUnresolvedPostHooks =
    hasUnresolvedHooksFromLookup(lookups, toolUseId, 'PostToolUse')
    || hasUnresolvedHooksFromLookup(lookups, toolUseId, 'PostToolUseFailure')

  if (hasUnresolvedPostHooks) {
    return 'running'
  }
  if (result != null) {
    return result.isError ? 'failed' : 'completed'
  }
  if (lastEvent != null) {
    return toolStatus(lastEvent.phase ?? 'status')
  }
  if (lookups.erroredToolUseIds.has(toolUseId)) {
    return 'failed'
  }
  return lookups.resolvedToolUseIds.has(toolUseId) ? 'completed' : 'queued'
}

function toolStatus(phase: string): ToolBlockStatus {
  switch (phase) {
    case 'completed':
    case 'yielded':
      return 'completed'
    case 'failed':
    case 'cancelled':
      return 'failed'
    case 'permission_requested':
      return 'queued'
    case 'started':
    case 'permission_granted':
    case 'status':
    case 'delta':
    case 'progress':
      return 'running'
    default:
      return 'queued'
  }
}

function normalizeToolDetail(phase: string, text: string): string | null {
  const normalized = text.trim()
  if (!normalized) {
    return null
  }
  if (phase === 'completed' || phase === 'yielded' || phase === 'failed' || phase === 'cancelled') {
    return null
  }
  return normalized
}

function normalizeToolResultText(phase: string, text: string): string {
  const normalized = text.trim()
  if (normalized) {
    return normalized
  }
  return phase === 'failed' || phase === 'cancelled'
    ? 'Tool execution failed.'
    : 'Tool execution completed.'
}

function summarizeInput(command: string | null | undefined, inputJson: string): string | null {
  if (command && command.trim()) {
    return command.trim()
  }
  const normalized = inputJson.trim()
  if (!normalized) {
    return null
  }
  return normalized
}

function firstNonBlank(...values: Array<string | null | undefined>): string | null {
  for (const value of values) {
    if (value != null && value.trim()) {
      return value.trim()
    }
  }
  return null
}

function compactValues(values: Array<string | null | undefined>): string[] {
  const ordered: string[] = []
  const seen = new Set<string>()
  for (const value of values) {
    if (value == null || !value.trim()) {
      continue
    }
    const normalized = value.trim()
    if (seen.has(normalized)) {
      continue
    }
    seen.add(normalized)
    ordered.push(normalized)
  }
  return ordered
}

function flushAssistantTurn(renderable: RenderableMessage[], assistantTurn: AssistantAccumulator): void {
  const blocks = mergeAdjacentTextBlocks(assistantTurn.message.blocks)
  if (blocks.length === 0) {
    return
  }

  renderable.push({
    ...assistantTurn.message,
    blocks,
  })
}

function mergeAdjacentTextBlocks(blocks: AssistantContentBlock[]): AssistantContentBlock[] {
  const merged: AssistantContentBlock[] = []
  for (const block of blocks) {
    const previous = merged[merged.length - 1]
    if (block.type === 'text' && previous?.type === 'text') {
      merged[merged.length - 1] = {
        ...previous,
        text: `${previous.text}\n${block.text}`,
        live: previous.live || block.live,
      }
      continue
    }
    merged.push(block)
  }
  return merged
}
