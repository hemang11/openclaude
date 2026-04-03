export type ProviderId =
  | 'anthropic'
  | 'openai'
  | 'gemini'
  | 'mistral'
  | 'kimi'
  | 'bedrock'

export type AuthMethod = 'api_key' | 'browser_sso' | 'aws_credentials'
export type SessionMessageKind =
  | 'user'
  | 'assistant'
  | 'compact_summary'
  | 'thinking'
  | 'tool'
  | 'tool_use'
  | 'tool_result'
  | 'compact_boundary'
  | 'system'
  | 'progress'
  | 'attachment'
  | 'tombstone'
export type PanelKind = 'cost' | 'context' | 'diff' | 'doctor' | 'help' | 'config'

export interface StdioRequest<TMethod extends string = string, TParams = unknown> {
  kind: 'request'
  id: string
  method: TMethod
  params?: TParams
}

export interface StdioResponse<TResult = unknown> {
  kind: 'response'
  id: string
  ok: boolean
  result?: TResult
  error?: ProtocolError
}

export interface StdioEvent<TEvent extends string = string, TData = unknown> {
  kind: 'event'
  id: string
  event: TEvent
  data: TData
}

export interface ProtocolError {
  code: string
  message: string
}

export interface ConnectionView {
  providerId: ProviderId
  authMethod: AuthMethod
  credentialReference: string
  connectedAt: string
}

export interface ProviderView {
  providerId: ProviderId
  displayName: string
  supportedAuthMethods: AuthMethod[]
  connected: boolean
  active: boolean
  connection?: ConnectionView | null
}

export interface ModelView {
  id: string
  displayName: string
  providerId: ProviderId
  providerDisplayName: string
  providerActive: boolean
  active: boolean
}

export interface CommandView {
  name: string
  displayName: string
  description: string
  aliases: string[]
  argumentHint?: string | null
  execution: 'overlay' | 'frontend' | 'backend'
  overlay?: 'providers' | 'models' | 'config' | 'help' | 'keybindings' | 'context' | null
  frontendAction?: 'copy' | 'exit' | null
  enabled: boolean
  hidden: boolean
}

export interface SettingsView {
  fastMode: boolean
  verboseOutput: boolean
  reasoningVisible: boolean
  alwaysCopyFullResponse: boolean
  effortLevel?: string | null
}

export interface TodoView {
  content: string
  status: 'pending' | 'in_progress' | 'completed' | string
  activeForm: string
}

export interface SessionSummaryView {
  sessionId?: string | null
  title?: string | null
  startedAt: string
  updatedAt: string
  durationSeconds: number
  userMessageCount: number
  assistantMessageCount: number
  totalMessageCount: number
  estimatedContextTokens: number
  contextWindowTokens: number
  totalCostUsd: number
  workingDirectory?: string | null
  workspaceRoot?: string | null
  planMode: boolean
  todos: TodoView[]
}

export interface SessionListItemView {
  sessionId: string
  title: string
  preview: string
  updatedAt: string
  messageCount: number
  workingDirectory?: string | null
  workspaceRoot?: string | null
  active: boolean
}

export interface SessionMessageView {
  kind: SessionMessageKind
  id: string
  createdAt: string
  text: string
  displayText?: string | null
  providerId?: ProviderId | null
  modelId?: string | null
  assistantMessageId?: string | null
  siblingToolIds?: string[] | null
  toolGroupKey?: string | null
  toolRenderClass?: string | null
  attachmentKind?: string | null
  hookEvent?: string | null
  toolId?: string | null
  toolName?: string | null
  phase?: string | null
  isError?: boolean | null
  inputJson?: string | null
  command?: string | null
  permissionRequestId?: string | null
  interactionType?: string | null
  interactionJson?: string | null
  source?: string | null
  reason?: string | null
}

export interface StateView {
  activeProvider?: ProviderId | null
  activeModelId?: string | null
  activeSessionId?: string | null
  connections: ConnectionView[]
}

export interface ContextUsageView {
  estimatedTokens: number
  contextWindowTokens: number
  usedCells: number
  totalCells: number
  status: string
}

export interface PanelSectionView {
  title: string
  lines: string[]
}

export interface PanelView {
  kind: PanelKind | string
  title: string
  subtitle?: string | null
  sections: PanelSectionView[]
  contextUsage?: ContextUsageView | null
}

export interface BackendSnapshot {
  state: StateView
  settings: SettingsView
  session: SessionSummaryView
  providers: ProviderView[]
  models: ModelView[]
  commands: CommandView[]
  messages: SessionMessageView[]
}

export interface MutationResult {
  message: string
  snapshot: BackendSnapshot
}

export interface PromptSubmitResult {
  sessionId: string
  modelId: string
  text: string
  snapshot: BackendSnapshot
}

export interface CommandResult {
  message: string
  panel?: PanelView | null
  snapshot: BackendSnapshot
}

export interface StatusEvent {
  message: string
}

export interface PromptDeltaEvent {
  text: string
}

export interface PromptReasoningDeltaEvent {
  text: string
  summary: boolean
}

export interface PromptToolEvent {
  toolId: string
  toolName: string
  phase: 'started' | 'progress' | 'delta' | 'completed' | 'failed' | 'cancelled' | 'yielded' | 'status'
  text?: string | null
  command?: string | null
}

export interface PermissionRequestEvent {
  requestId: string
  toolId: string
  toolName: string
  inputJson: string
  command?: string | null
  reason: string
  interactionType?: string | null
  interactionJson?: string | null
}

export interface PermissionRespondParams {
  requestId: string
  decision: 'allow' | 'deny'
  payloadJson?: string
  updatedInputJson?: string
  userModified?: boolean
  decisionReason?: string
  interrupt?: boolean
}

export interface PermissionEditorMutationParams {
  action: 'add' | 'remove' | 'clear' | 'retry-denials'
  source?: string | null
  behavior?: 'allow' | 'deny' | 'ask' | null
  rule?: string | null
}

export interface PermissionRuleView {
  source: string
  displayName: string
  behavior: 'allow' | 'deny' | 'ask' | string
  toolName: string
  ruleString: string
  summary: string
  createdAt: string
  editable: boolean
}

export interface PermissionRuleSourceGroupView {
  source: string
  displayName: string
  editable: boolean
  rules: PermissionRuleView[]
}

export interface PermissionActivityView {
  toolUseId: string
  toolName: string
  status: string
  detail: string
  createdAt: string
}

export interface PermissionEditorTabView {
  id: string
  title: string
  description: string
  sourceGroups: PermissionRuleSourceGroupView[]
  recentActivities: PermissionActivityView[]
}

export interface PermissionEditorSnapshotView {
  sessionId?: string | null
  workspaceDisplayPath: string
  workspaceRoot?: string | null
  activeProvider?: string | null
  activeModel?: string | null
  tabs: PermissionEditorTabView[]
}

export interface PermissionEditorMutationResult {
  message: string
  snapshot: PermissionEditorSnapshotView
}

export interface ProviderConnectParams {
  providerId: ProviderId
  authMethod: AuthMethod
  apiKeyEnv?: string
  awsProfile?: string
}

export interface ProviderSelectionParams {
  providerId: ProviderId
}

export interface ModelSelectionParams {
  providerId: ProviderId
  modelId: string
}

export interface PromptSubmitParams {
  text: string
}

export interface PromptCancelParams {
  requestId: string
}

export interface CommandRunParams {
  commandName?: string
  name?: string
  args?: string
}

export interface SettingsUpdateParams {
  fastMode?: boolean
  verboseOutput?: boolean
  reasoningVisible?: boolean
  alwaysCopyFullResponse?: boolean
  effortLevel?: string | null
}

export interface SessionSelectionParams {
  sessionId: string
}

export interface SessionRenameParams {
  title: string
}

export interface SessionPlanModeParams {
  enabled: boolean
}

export interface SessionRewindParams {
  messageId: string
}

export type OpenClaudeRequest =
  | StdioRequest<'initialize'>
  | StdioRequest<'state.snapshot'>
  | StdioRequest<'providers.list'>
  | StdioRequest<'provider.connect', ProviderConnectParams>
  | StdioRequest<'provider.use', ProviderSelectionParams>
  | StdioRequest<'provider.disconnect', ProviderSelectionParams>
  | StdioRequest<'models.list'>
  | StdioRequest<'models.select', ModelSelectionParams>
  | StdioRequest<'sessions.list'>
  | StdioRequest<'sessions.resume', SessionSelectionParams>
  | StdioRequest<'sessions.rename', SessionRenameParams>
  | StdioRequest<'sessions.plan_mode', SessionPlanModeParams>
  | StdioRequest<'sessions.rewind', SessionRewindParams>
  | StdioRequest<'sessions.clear'>
  | StdioRequest<'commands.list'>
  | StdioRequest<'command.run', CommandRunParams>
  | StdioRequest<'command.execute', CommandRunParams>
  | StdioRequest<'permissions.editor.snapshot'>
  | StdioRequest<'permissions.editor.mutate', PermissionEditorMutationParams>
  | StdioRequest<'settings.update', SettingsUpdateParams>
  | StdioRequest<'permission.respond', PermissionRespondParams>
  | StdioRequest<'prompt.cancel', PromptCancelParams>
  | StdioRequest<'prompt.submit', PromptSubmitParams>

export type OpenClaudeEvent =
  | StdioEvent<'provider.connect.status', StatusEvent>
  | StdioEvent<'prompt.started', StatusEvent>
  | StdioEvent<'prompt.status', StatusEvent>
  | StdioEvent<'prompt.delta', PromptDeltaEvent>
  | StdioEvent<'prompt.reasoning.delta', PromptReasoningDeltaEvent>
  | StdioEvent<'prompt.tool.started', PromptToolEvent>
  | StdioEvent<'prompt.tool.delta', PromptToolEvent>
  | StdioEvent<'prompt.tool.completed', PromptToolEvent>
  | StdioEvent<'prompt.tool.failed', PromptToolEvent>
  | StdioEvent<'permission.requested', PermissionRequestEvent>

export type OpenClaudeResponse =
  | StdioResponse<BackendSnapshot>
  | StdioResponse<{ providers: ProviderView[] }>
  | StdioResponse<{ models: ModelView[] }>
  | StdioResponse<{ sessions: SessionListItemView[] }>
  | StdioResponse<{ commands: CommandView[] }>
  | StdioResponse<MutationResult>
  | StdioResponse<PermissionEditorSnapshotView>
  | StdioResponse<PermissionEditorMutationResult>
  | StdioResponse<PromptSubmitResult>
  | StdioResponse<CommandResult>
