import type { ProviderId } from '../../../types/stdio/protocol.ts'

export type ToolBlockStatus = 'queued' | 'running' | 'completed' | 'failed'

export type AssistantThinkingBlock = {
  type: 'thinking'
  id: string
  text: string
  live: boolean
}

export type AssistantTextBlock = {
  type: 'text'
  id: string
  text: string
  live: boolean
}

export type AssistantGroupedToolBlock = {
  type: 'grouped_tool'
  id: string
  assistantMessageId?: string | null
  toolGroupKey?: string | null
  toolId: string
  toolIds: string[]
  siblingToolIds: string[]
  toolName: string
  toolRenderClass?: string | null
  status: ToolBlockStatus
  commands: string[]
  details: string[]
  resultTexts: string[]
  command?: string | null
  detail?: string | null
  resultText?: string | null
  permissionPending: boolean
  live: boolean
}

export type AssistantCollapsedReadSearchBlock = {
  type: 'collapsed_read_search'
  id: string
  toolIds: string[]
  label: string
  status: ToolBlockStatus
  commands: string[]
  summary: string
  active: boolean
  live: boolean
}

export type AssistantContentBlock =
  | AssistantThinkingBlock
  | AssistantTextBlock

export type RenderableAssistantMessage = {
  type: 'assistant'
  id: string
  createdAt: string
  providerId?: ProviderId | null
  modelId?: string | null
  live: boolean
  blocks: AssistantContentBlock[]
}

export type RenderableGroupedToolMessage = AssistantGroupedToolBlock & {
  createdAt: string
}

export type RenderableCollapsedReadSearchMessage = AssistantCollapsedReadSearchBlock & {
  createdAt: string
}

export type RenderableUserMessage = {
  type: 'user'
  id: string
  createdAt: string
  text: string
}

export type RenderableStatusMessage = {
  type: 'system' | 'progress' | 'attachment' | 'tombstone' | 'compact_boundary' | 'compact_summary'
  id: string
  createdAt: string
  text: string
  attachmentKind?: string | null
  hookEvent?: string | null
  source?: string | null
  reason?: string | null
}

export type RenderableMessage =
  | RenderableAssistantMessage
  | RenderableGroupedToolMessage
  | RenderableCollapsedReadSearchMessage
  | RenderableUserMessage
  | RenderableStatusMessage
