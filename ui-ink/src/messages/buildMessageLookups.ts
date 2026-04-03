import type { SessionMessageView } from '../../../types/stdio/protocol.ts'

export type MessageLookups = {
  assistantMessageById: Map<string, SessionMessageView>
  toolUseById: Map<string, SessionMessageView>
  toolEventsById: Map<string, SessionMessageView[]>
  hookMessagesByToolUseId: Map<string, SessionMessageView[]>
  inProgressHookCounts: Map<string, Map<string, number>>
  resolvedHookCounts: Map<string, Map<string, number>>
  toolResultById: Map<string, SessionMessageView>
  toolUseIdsByAssistantMessageId: Map<string, string[]>
  assistantMessageIdByToolUseId: Map<string, string>
  toolGroupKeyByToolUseId: Map<string, string>
  toolUseIdsByGroupKey: Map<string, string[]>
  siblingToolUseIdsByToolUseId: Map<string, ReadonlySet<string>>
  resolvedToolUseIds: ReadonlySet<string>
  erroredToolUseIds: ReadonlySet<string>
}

export function buildMessageLookups(messages: SessionMessageView[]): MessageLookups {
  const assistantMessageById = new Map<string, SessionMessageView>()
  const toolUseById = new Map<string, SessionMessageView>()
  const toolEventsById = new Map<string, SessionMessageView[]>()
  const hookMessagesByToolUseId = new Map<string, SessionMessageView[]>()
  const inProgressHookCounts = new Map<string, Map<string, number>>()
  const resolvedHookCounts = new Map<string, Map<string, number>>()
  const toolResultById = new Map<string, SessionMessageView>()
  const toolUseIdsByAssistantMessageId = new Map<string, string[]>()
  const assistantMessageIdByToolUseId = new Map<string, string>()
  const toolGroupKeyByToolUseId = new Map<string, string>()
  const toolUseIdsByGroupKey = new Map<string, string[]>()
  const declaredSiblingToolUseIdsByToolUseId = new Map<string, ReadonlySet<string>>()
  const resolvedToolUseIds = new Set<string>()
  const erroredToolUseIds = new Set<string>()

  let lastAssistantMessageId: string | null = null
  for (const message of messages) {
    if (message.kind === 'assistant') {
      assistantMessageById.set(message.id, message)
      lastAssistantMessageId = message.id
      continue
    }

    if (message.kind === 'tool_use') {
      const toolUseId = message.toolId ?? message.id
      toolUseById.set(toolUseId, message)
      const assistantMessageId = deriveAssistantMessageId(message, lastAssistantMessageId)
      if (assistantMessageId) {
        assistantMessageIdByToolUseId.set(toolUseId, assistantMessageId)
        const existing = toolUseIdsByAssistantMessageId.get(assistantMessageId) ?? []
        toolUseIdsByAssistantMessageId.set(assistantMessageId, [...existing, toolUseId])
      }
      const groupKey = deriveToolGroupKey(message, assistantMessageId)
      if (groupKey) {
        toolGroupKeyByToolUseId.set(toolUseId, groupKey)
        const existing = toolUseIdsByGroupKey.get(groupKey) ?? []
        toolUseIdsByGroupKey.set(groupKey, [...existing, toolUseId])
      }
      if (message.siblingToolIds && message.siblingToolIds.length > 0) {
        declaredSiblingToolUseIdsByToolUseId.set(
          toolUseId,
          normalizeSiblingToolUseIds(message.siblingToolIds, toolUseId),
        )
      }
      continue
    }

    if (message.kind === 'tool') {
      const toolUseId = message.toolId ?? message.id
      const existing = toolEventsById.get(toolUseId) ?? []
      toolEventsById.set(toolUseId, [...existing, message])
      if (message.phase === 'completed' || message.phase === 'failed' || message.phase === 'cancelled') {
        resolvedToolUseIds.add(toolUseId)
      }
      if (message.phase === 'failed' || message.phase === 'cancelled') {
        erroredToolUseIds.add(toolUseId)
      }
      applyToolUseRelationshipMetadata(
        declaredSiblingToolUseIdsByToolUseId,
        assistantMessageIdByToolUseId,
        message,
        toolUseId,
      )
      continue
    }

    if (message.kind === 'tool_result') {
      const toolUseId = message.toolId ?? message.id
      toolResultById.set(toolUseId, message)
      resolvedToolUseIds.add(toolUseId)
      if (message.isError) {
        erroredToolUseIds.add(toolUseId)
      }
      applyToolUseRelationshipMetadata(
        declaredSiblingToolUseIdsByToolUseId,
        assistantMessageIdByToolUseId,
        message,
        toolUseId,
      )
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
      const existing = hookMessagesByToolUseId.get(toolUseId) ?? []
      hookMessagesByToolUseId.set(toolUseId, [...existing, message])
      const hookEvent = message.hookEvent.trim()
      if (message.phase === 'hook_started' || message.phase === 'hook_progress') {
        const countsByEvent = inProgressHookCounts.get(toolUseId) ?? new Map<string, number>()
        countsByEvent.set(hookEvent, (countsByEvent.get(hookEvent) ?? 0) + 1)
        inProgressHookCounts.set(toolUseId, countsByEvent)
      }
      if (message.phase === 'hook_response') {
        const countsByEvent = resolvedHookCounts.get(toolUseId) ?? new Map<string, number>()
        countsByEvent.set(hookEvent, (countsByEvent.get(hookEvent) ?? 0) + 1)
        resolvedHookCounts.set(toolUseId, countsByEvent)
      }
      applyToolUseRelationshipMetadata(
        declaredSiblingToolUseIdsByToolUseId,
        assistantMessageIdByToolUseId,
        message,
        toolUseId,
      )
      continue
    }

    if (
      message.kind === 'attachment'
      && message.attachmentKind === 'hook_additional_context'
      && message.toolId
      && message.toolId.trim()
    ) {
      const toolUseId = message.toolId.trim()
      const existing = hookMessagesByToolUseId.get(toolUseId) ?? []
      hookMessagesByToolUseId.set(toolUseId, [...existing, message])
      if (message.hookEvent && message.hookEvent.trim()) {
        const hookEvent = message.hookEvent.trim()
        const countsByEvent = resolvedHookCounts.get(toolUseId) ?? new Map<string, number>()
        countsByEvent.set(hookEvent, (countsByEvent.get(hookEvent) ?? 0) + 1)
        resolvedHookCounts.set(toolUseId, countsByEvent)
      }
      applyToolUseRelationshipMetadata(
        declaredSiblingToolUseIdsByToolUseId,
        assistantMessageIdByToolUseId,
        message,
        toolUseId,
      )
    }
  }

  const siblingToolUseIdsByToolUseId = new Map<string, ReadonlySet<string>>()
  for (const [toolUseId, siblingSet] of declaredSiblingToolUseIdsByToolUseId.entries()) {
    siblingToolUseIdsByToolUseId.set(toolUseId, siblingSet)
  }
  for (const toolUseIds of toolUseIdsByAssistantMessageId.values()) {
    const siblingSet = new Set(toolUseIds)
    for (const toolUseId of toolUseIds) {
      if (!siblingToolUseIdsByToolUseId.has(toolUseId)) {
        siblingToolUseIdsByToolUseId.set(toolUseId, siblingSet)
      }
    }
  }

  return {
    assistantMessageById,
    toolUseById,
    toolEventsById,
    hookMessagesByToolUseId,
    inProgressHookCounts,
    resolvedHookCounts,
    toolResultById,
    toolUseIdsByAssistantMessageId,
    assistantMessageIdByToolUseId,
    toolGroupKeyByToolUseId,
    toolUseIdsByGroupKey,
    siblingToolUseIdsByToolUseId,
    resolvedToolUseIds,
    erroredToolUseIds,
  }
}

function deriveToolGroupKey(
  message: SessionMessageView,
  assistantMessageId: string | null,
): string | null {
  if (message.toolGroupKey && message.toolGroupKey.trim()) {
    return message.toolGroupKey.trim()
  }
  if (!assistantMessageId || !message.toolName || !message.toolName.trim()) {
    return null
  }
  return `${assistantMessageId}:${message.toolName.trim().toLowerCase()}`
}

export function getSiblingToolUseIdsFromLookup(
  lookups: MessageLookups,
  toolUseId: string,
): ReadonlySet<string> {
  return lookups.siblingToolUseIdsByToolUseId.get(toolUseId) ?? new Set([toolUseId])
}

export function getOrderedSiblingToolUseIdsFromLookup(
  lookups: MessageLookups,
  toolUseId: string,
): string[] {
  const assistantMessageId = lookups.assistantMessageIdByToolUseId.get(toolUseId)
  if (assistantMessageId) {
    const ordered = lookups.toolUseIdsByAssistantMessageId.get(assistantMessageId)
    if (ordered && ordered.length > 0) {
      return [...ordered]
    }
  }

  return [...getSiblingToolUseIdsFromLookup(lookups, toolUseId)]
}

export function hasResolvedToolUse(
  lookups: MessageLookups,
  toolUseId: string,
): boolean {
  return lookups.resolvedToolUseIds.has(toolUseId)
}

export function hasUnresolvedHooksFromLookup(
  lookups: MessageLookups,
  toolUseId: string,
  hookEvent: string,
): boolean {
  const normalizedToolUseId = toolUseId.trim()
  const normalizedHookEvent = hookEvent.trim()
  if (!normalizedToolUseId || !normalizedHookEvent) {
    return false
  }

  const inProgressCount =
    lookups.inProgressHookCounts.get(normalizedToolUseId)?.get(normalizedHookEvent) ?? 0
  const resolvedCount =
    lookups.resolvedHookCounts.get(normalizedToolUseId)?.get(normalizedHookEvent) ?? 0
  return inProgressCount > resolvedCount
}

function deriveAssistantMessageId(
  message: SessionMessageView,
  lastAssistantMessageId: string | null,
): string | null {
  if (message.assistantMessageId && message.assistantMessageId.trim()) {
    return message.assistantMessageId.trim()
  }
  const separatorIndex = message.id.indexOf(':')
  if (separatorIndex > 0) {
    return message.id.slice(0, separatorIndex)
  }
  return lastAssistantMessageId
}

function normalizeSiblingToolUseIds(
  siblingToolIds: readonly string[] | null | undefined,
  toolUseId: string,
): ReadonlySet<string> {
  const ordered = new Set<string>()
  for (const siblingToolId of siblingToolIds ?? []) {
    if (typeof siblingToolId === 'string' && siblingToolId.trim()) {
      ordered.add(siblingToolId.trim())
    }
  }
  ordered.add(toolUseId)
  return ordered
}

function applyToolUseRelationshipMetadata(
  declaredSiblingToolUseIdsByToolUseId: Map<string, ReadonlySet<string>>,
  assistantMessageIdByToolUseId: Map<string, string>,
  message: SessionMessageView,
  toolUseId: string,
): void {
  if (message.assistantMessageId && message.assistantMessageId.trim()) {
    assistantMessageIdByToolUseId.set(toolUseId, message.assistantMessageId.trim())
  }
  if (message.siblingToolIds && message.siblingToolIds.length > 0) {
    declaredSiblingToolUseIdsByToolUseId.set(
      toolUseId,
      normalizeSiblingToolUseIds(message.siblingToolIds, toolUseId),
    )
  }
}
