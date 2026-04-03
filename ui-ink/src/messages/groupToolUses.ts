import type { MessageLookups } from './buildMessageLookups.ts'
import type { RenderableGroupedToolMessage, RenderableMessage, ToolBlockStatus } from './types.ts'

export function groupToolUses(
  messages: RenderableMessage[],
  lookups: MessageLookups,
): RenderableMessage[] {
  const grouped: RenderableMessage[] = []
  let index = 0

  while (index < messages.length) {
    const message = messages[index]
    if (message?.type !== 'grouped_tool') {
      if (message) {
        grouped.push(message)
      }
      index += 1
      continue
    }

    const groupKey =
      message.toolGroupKey
      ?? lookups.toolGroupKeyByToolUseId.get(message.toolId)
      ?? null
    if (!groupKey) {
      grouped.push(message)
      index += 1
      continue
    }

    const family: RenderableGroupedToolMessage[] = [message]
    let nextIndex = index + 1
    while (nextIndex < messages.length) {
      const candidate = messages[nextIndex]
      if (
        candidate?.type !== 'grouped_tool'
        || candidate.toolName !== message.toolName
        || (candidate.toolGroupKey ?? lookups.toolGroupKeyByToolUseId.get(candidate.toolId) ?? null) !== groupKey
      ) {
        break
      }
      family.push(candidate)
      nextIndex += 1
    }

    grouped.push(family.length >= 2 ? mergeGroupedToolFamily(family) : message)
    index = nextIndex
  }

  return grouped
}

function mergeGroupedToolFamily(messages: RenderableGroupedToolMessage[]): RenderableGroupedToolMessage {
  const first = messages[0]!
  const commandValues = flattenUnique(messages.flatMap((message) => message.commands))
  const detailValues = flattenUnique(messages.flatMap((message) => message.details))
  const resultValues = flattenUnique(messages.flatMap((message) => message.resultTexts))
  const toolIds = flattenUnique(messages.flatMap((message) => message.toolIds))
  const siblingToolIds = flattenUnique(messages.flatMap((message) => message.siblingToolIds))

  return {
    ...first,
    id: first.id,
    toolId: toolIds[0] ?? first.toolId,
    toolIds,
    siblingToolIds,
    status: mergeStatuses(messages.map((message) => message.status)),
    commands: commandValues,
    details: detailValues,
    resultTexts: resultValues,
    command: commandValues[0] ?? first.command ?? null,
    detail: detailValues[0] ?? first.detail ?? null,
    resultText: resultValues[0] ?? first.resultText ?? null,
    permissionPending: messages.some((message) => message.permissionPending),
    live: messages.some((message) => message.live),
  }
}

function flattenUnique(values: string[]): string[] {
  const seen = new Set<string>()
  const ordered: string[] = []
  for (const value of values) {
    const normalized = value.trim()
    if (!normalized || seen.has(normalized)) {
      continue
    }
    seen.add(normalized)
    ordered.push(normalized)
  }
  return ordered
}

function mergeStatuses(statuses: ToolBlockStatus[]): ToolBlockStatus {
  if (statuses.includes('failed')) {
    return 'failed'
  }
  if (statuses.includes('running')) {
    return 'running'
  }
  if (statuses.every((status) => status === 'completed')) {
    return 'completed'
  }
  return 'queued'
}
