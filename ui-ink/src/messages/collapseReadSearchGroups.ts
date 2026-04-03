import type { AssistantCollapsedReadSearchBlock, RenderableCollapsedReadSearchMessage, RenderableGroupedToolMessage, RenderableMessage, ToolBlockStatus } from './types.ts'

const COLLAPSIBLE_CLASSES = new Set(['read', 'search', 'list', 'bash'])

export function collapseReadSearchGroups(messages: RenderableMessage[]): RenderableMessage[] {
  const collapsed: RenderableMessage[] = []
  let index = 0

  while (index < messages.length) {
    const message = messages[index]
    if (message?.type !== 'grouped_tool' || !isCollapsible(message)) {
      if (message) {
        collapsed.push(message)
      }
      index += 1
      continue
    }

    const family: RenderableGroupedToolMessage[] = [message]
    let nextIndex = index + 1
    while (nextIndex < messages.length) {
      const candidate = messages[nextIndex]
      if (candidate?.type !== 'grouped_tool' || !isCollapsible(candidate)) {
        break
      }
      family.push(candidate)
      nextIndex += 1
    }

    collapsed.push(family.length >= 2 ? createCollapsedReadSearchMessage(family) : message)
    index = nextIndex
  }

  return markActiveCollapsedReadSearchRows(collapsed)
}

function isCollapsible(message: RenderableGroupedToolMessage): boolean {
  const renderClass = (message.toolRenderClass ?? '').trim().toLowerCase()
  if (renderClass && COLLAPSIBLE_CLASSES.has(renderClass)) {
    return true
  }
  const toolName = message.toolName.trim().toLowerCase()
  return toolName === 'bash' || toolName === 'read' || toolName === 'file_read' || toolName === 'grep'
    || toolName === 'glob' || toolName === 'web_search'
}

function createCollapsedReadSearchMessage(
  messages: RenderableGroupedToolMessage[],
): RenderableCollapsedReadSearchMessage {
  const block = createCollapsedReadSearchBlock(messages)
  return {
    ...block,
    createdAt: messages[0]?.createdAt ?? new Date().toISOString(),
  }
}

function createCollapsedReadSearchBlock(
  messages: RenderableGroupedToolMessage[],
): AssistantCollapsedReadSearchBlock {
  const toolIds = flattenUnique(messages.flatMap((message) => message.toolIds))
  const commands = flattenUnique(messages.flatMap((message) => message.commands)).slice(0, 6)
  const failedCount = messages.filter((message) => message.status === 'failed').length
  const runningCount = messages.filter((message) => message.status === 'running' || message.status === 'queued').length
  const completedCount = messages.filter((message) => message.status === 'completed').length
  const status: ToolBlockStatus =
    failedCount > 0 ? 'failed'
    : runningCount > 0 ? 'running'
    : completedCount === messages.length ? 'completed'
    : 'queued'

  const label = familyLabel(messages)
  const summary =
    failedCount > 0
      ? `${failedCount} failed, ${Math.max(0, messages.length - failedCount)} finished`
      : runningCount > 0
        ? `${runningCount} in progress`
        : `${completedCount} completed`

  return {
    type: 'collapsed_read_search',
    id: `collapsed:${toolIds.join(',')}`,
    toolIds,
    label,
    status,
    commands,
    summary,
    active: false,
    live: messages.some((message) => message.live),
  }
}

function familyLabel(messages: RenderableGroupedToolMessage[]): string {
  const classes = new Set(
    messages.map((message) => (message.toolRenderClass ?? '').trim().toLowerCase()).filter(Boolean),
  )
  if (classes.size === 1) {
    const only = [...classes][0]
    switch (only) {
      case 'read':
        return 'Read actions'
      case 'search':
        return 'Search actions'
      case 'list':
        return 'List actions'
      case 'bash':
        return 'Bash actions'
      default:
        break
    }
  }
  return 'Read/search actions'
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

function markActiveCollapsedReadSearchRows(messages: RenderableMessage[]): RenderableMessage[] {
  let activeCollapsedGroupId: string | null = null

  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (!message) {
      continue
    }

    if (message.type === 'collapsed_read_search') {
      if (message.status === 'running' || message.status === 'queued') {
        activeCollapsedGroupId = message.id
      }
      break
    }

    if (!isSkippableTrailingMessage(message)) {
      break
    }
  }

  if (activeCollapsedGroupId == null) {
    return messages
  }

  return messages.map((message) =>
    message.type === 'collapsed_read_search'
      ? {
          ...message,
          active: message.id === activeCollapsedGroupId,
        }
      : message,
  )
}

function isSkippableTrailingMessage(message: RenderableMessage): boolean {
  return (
    message.type === 'system'
    || message.type === 'progress'
    || message.type === 'attachment'
    || message.type === 'tombstone'
    || message.type === 'compact_boundary'
    || message.type === 'compact_summary'
  )
}
