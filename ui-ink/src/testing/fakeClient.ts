import { EventEmitter } from 'node:events'

import type { OpenClaudeClient, OpenClaudeRequestHandle } from '../ipc/OpenClaudeStdioClient.ts'
import type {
  BackendSnapshot,
  CommandResult,
  CommandView,
  MutationResult,
  OpenClaudeEvent,
  PermissionEditorSnapshotView,
  OpenClaudeResponse,
  OpenClaudeRequest,
  PromptSubmitResult,
  SessionMessageView,
} from '../../../types/stdio/protocol.ts'

export class FakeOpenClaudeClient extends EventEmitter implements OpenClaudeClient {
  public snapshot: BackendSnapshot
  public readonly promptSubmissions: string[] = []
  protected permissionEditorSnapshotData: PermissionEditorSnapshotView

  constructor(snapshot: BackendSnapshot = createSnapshot()) {
    super()
    this.snapshot = snapshot
    this.permissionEditorSnapshotData = createPermissionEditorSnapshot()
  }

  async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    return this.handleRequest(method, params, onEvent)
  }

  startRequest<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): OpenClaudeRequestHandle<TResult> {
    return {
      id: `fake-${Math.random().toString(16).slice(2)}`,
      promise: Promise.resolve().then(() => this.request(method, params, onEvent)),
    }
  }

  private async handleRequest<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    switch (method) {
      case 'initialize':
      case 'state.snapshot':
        return this.snapshot as TResult
      case 'prompt.submit': {
        const text = String((params as { text?: string } | undefined)?.text ?? '')
        this.promptSubmissions.push(text)
        onEvent?.({
          kind: 'event',
          id: `prompt-${this.promptSubmissions.length}`,
          event: 'prompt.started',
          data: { message: 'Prompt started' },
        })
        onEvent?.({
          kind: 'event',
          id: `prompt-${this.promptSubmissions.length}`,
          event: 'prompt.delta',
          data: { text: 'Synthetic assistant response.' },
        })
        this.snapshot = appendConversation(this.snapshot, text, 'Synthetic assistant response.')
        return {
          sessionId: this.snapshot.session.sessionId ?? 'session-test',
          modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
          text: 'Synthetic assistant response.',
          snapshot: this.snapshot,
        } as TResult
      }
      case 'prompt.cancel':
        return {
          message: 'Prompt cancelled.',
          snapshot: this.snapshot,
        } as TResult
      case 'settings.update':
        this.snapshot = {
          ...this.snapshot,
          settings: {
            ...this.snapshot.settings,
            fastMode:
              (params as { fastMode?: boolean | null } | undefined)?.fastMode ?? this.snapshot.settings.fastMode,
            verboseOutput:
              (params as { verboseOutput?: boolean | null } | undefined)?.verboseOutput ?? this.snapshot.settings.verboseOutput,
            reasoningVisible:
              (params as { reasoningVisible?: boolean | null } | undefined)?.reasoningVisible ?? this.snapshot.settings.reasoningVisible,
            alwaysCopyFullResponse:
              (params as { alwaysCopyFullResponse?: boolean | null } | undefined)?.alwaysCopyFullResponse
              ?? this.snapshot.settings.alwaysCopyFullResponse,
            effortLevel:
              (params as { effortLevel?: string | null } | undefined)?.effortLevel ?? this.snapshot.settings.effortLevel,
          },
        }
        return {
          message: 'Updated settings.',
          snapshot: this.snapshot,
        } as TResult
      case 'sessions.list':
        return {
          sessions: [
            {
              sessionId: 'session-older',
              title: 'Summarize desktop repo',
              preview: 'Summary for ~/Desktop/py',
              updatedAt: '2026-04-01T23:00:00Z',
              messageCount: 12,
              workingDirectory: '/tmp/workspace',
              workspaceRoot: '/tmp/workspace',
              active: false,
            },
          ],
        } as TResult
      case 'sessions.resume': {
        const sessionId = String((params as { sessionId?: string } | undefined)?.sessionId ?? 'session-older')
        this.snapshot = {
          ...this.snapshot,
          state: {
            ...this.snapshot.state,
            activeSessionId: sessionId,
          },
          session: {
            ...this.snapshot.session,
            sessionId,
            title: 'Summarize desktop repo',
          },
        }
        return {
          message: `Resumed session Summarize desktop repo.`,
          snapshot: this.snapshot,
        } as TResult
      }
      case 'sessions.rewind': {
        const messageId = String((params as { messageId?: string } | undefined)?.messageId ?? '')
        const rewindIndex = this.snapshot.messages.findIndex((message) => message.kind === 'user' && message.id === messageId)
        const nextMessages = rewindIndex >= 0 ? this.snapshot.messages.slice(0, rewindIndex) : this.snapshot.messages
        const userMessageCount = nextMessages.filter((message) => message.kind === 'user').length
        const assistantMessageCount = nextMessages.filter((message) => message.kind === 'assistant').length
        this.snapshot = {
          ...this.snapshot,
          session: {
            ...this.snapshot.session,
            userMessageCount,
            assistantMessageCount,
            totalMessageCount: nextMessages.length,
          },
          messages: nextMessages,
        }
        return {
          message: 'Rewound conversation to the selected checkpoint.',
          snapshot: this.snapshot,
        } as TResult
      }
      case 'sessions.rename': {
        const title = String((params as { title?: string } | undefined)?.title ?? '')
        this.snapshot = {
          ...this.snapshot,
          session: {
            ...this.snapshot.session,
            title,
          },
        }
        return {
          message: `Session renamed to: ${title}`,
          snapshot: this.snapshot,
        } as TResult
      }
      case 'sessions.plan_mode': {
        const enabled = Boolean((params as { enabled?: boolean } | undefined)?.enabled)
        this.snapshot = {
          ...this.snapshot,
          session: {
            ...this.snapshot.session,
            planMode: enabled,
          },
        }
        return {
          message: enabled ? 'Enabled plan mode.' : 'Exited plan mode.',
          snapshot: this.snapshot,
        } as TResult
      }
      case 'sessions.clear': {
        this.snapshot = {
          ...this.snapshot,
          state: {
            ...this.snapshot.state,
            activeSessionId: 'session-cleared',
          },
          session: {
            ...this.snapshot.session,
            sessionId: 'session-cleared',
            title: null,
            updatedAt: '2026-04-02T00:00:00Z',
            userMessageCount: 0,
            assistantMessageCount: 0,
            totalMessageCount: 0,
            estimatedContextTokens: 0,
            todos: [],
          },
          messages: [],
        }
        return {
          message: 'Cleared conversation history and started a new session.',
          snapshot: this.snapshot,
        } as TResult
      }
      case 'permissions.editor.snapshot':
        return this.permissionEditorSnapshotData as TResult
      case 'permissions.editor.mutate': {
        const action = String((params as { action?: string } | undefined)?.action ?? '')
        const source = String((params as { source?: string } | undefined)?.source ?? 'session')
        const behavior = String((params as { behavior?: string } | undefined)?.behavior ?? 'allow')
        const rule = String((params as { rule?: string } | undefined)?.rule ?? '')
        if (action === 'add' && rule) {
          const tab = this.permissionEditorSnapshotData.tabs.find((entry) => entry.id === behavior)
          const group = tab?.sourceGroups.find((entry) => entry.source === source)
          group?.rules.push({
            source,
            displayName: sourceLabel(source),
            behavior,
            toolName: rule.split('(')[0] ?? rule,
            ruleString: rule,
            summary: `${behavior} ${rule}`,
            createdAt: '2026-04-03T00:00:00Z',
            editable: true,
          })
        } else if (action === 'remove' && rule) {
          this.permissionEditorSnapshotData.tabs.forEach((tab) => {
            tab.sourceGroups.forEach((group) => {
              group.rules = group.rules.filter((entry) => !(entry.source === source && entry.behavior === behavior && entry.ruleString === rule))
            })
          })
        } else if (action === 'clear') {
          this.permissionEditorSnapshotData.tabs.forEach((tab) => {
            tab.sourceGroups.forEach((group) => {
              if (group.source === source) {
                group.rules = []
              }
            })
          })
        } else if (action === 'retry-denials') {
          const recentTab = this.permissionEditorSnapshotData.tabs.find((entry) => entry.id === 'recent')
          if (recentTab) {
            recentTab.recentActivities = recentTab.recentActivities.map((activity) => ({
              ...activity,
              status: activity.status === 'denied' ? 'allowed' : activity.status,
            }))
          }
        }
        return {
          message: action === 'retry-denials'
            ? 'Recorded retry for 1 denied permission request. Ask again to continue.'
            : `Updated permissions via ${action}.`,
          snapshot: this.permissionEditorSnapshotData,
        } as TResult
      }
      case 'provider.disconnect': {
        const providerId = String((params as { providerId?: string } | undefined)?.providerId ?? '')
        const remainingConnections = this.snapshot.state.connections.filter((connection) => connection.providerId !== providerId)
        const remainingProviders = this.snapshot.providers.map((provider) =>
          provider.providerId === providerId
            ? { ...provider, connected: false, active: false, connection: null }
            : provider,
        )
        const nextActiveProvider = remainingConnections[0]?.providerId ?? null
        const nextModels = this.snapshot.models.map((model) => ({
          ...model,
          providerActive: model.providerId === nextActiveProvider,
          active: false,
        }))
        this.snapshot = {
          ...this.snapshot,
          state: {
            ...this.snapshot.state,
            activeProvider: nextActiveProvider,
            activeModelId: null,
            connections: remainingConnections,
          },
          providers: remainingProviders,
          models: nextModels,
        }
        return {
          message: `Disconnected ${providerId}`,
          snapshot: this.snapshot,
        } as TResult
      }
      case 'models.select': {
        const providerId = String((params as { providerId?: string } | undefined)?.providerId ?? '')
        const modelId = String((params as { modelId?: string } | undefined)?.modelId ?? '')
        const resolvedProviderId =
          this.snapshot.models.find((model) => model.providerId === providerId && model.id === modelId)?.providerId
          ?? this.snapshot.state.activeProvider
        this.snapshot = {
          ...this.snapshot,
          state: {
            ...this.snapshot.state,
            activeProvider: resolvedProviderId,
            activeModelId: modelId,
          },
          providers: this.snapshot.providers.map((provider) => ({
            ...provider,
            active: provider.providerId === resolvedProviderId,
          })),
          models: this.snapshot.models.map((model) => ({
            ...model,
            providerActive: model.providerId === resolvedProviderId,
            active: model.providerId === resolvedProviderId && model.id === modelId,
          })),
        }
        return {
          message: `Selected model ${modelId}`,
          snapshot: this.snapshot,
        } as TResult
      }
      case 'command.run':
      case 'command.execute': {
        const commandName = String((params as { commandName?: string; name?: string } | undefined)?.commandName ?? (params as { name?: string } | undefined)?.name ?? 'command')
        const rawArgs = String((params as { args?: string } | undefined)?.args ?? '').trim().toLowerCase()
        if (commandName === 'effort') {
          const effortLevel = !rawArgs || rawArgs === 'auto' || rawArgs === 'unset' ? null : rawArgs
          this.snapshot = {
            ...this.snapshot,
            settings: {
              ...this.snapshot.settings,
              effortLevel,
            },
          }
          return {
            message: effortLevel == null ? 'Effort level set to auto' : `Set effort level to ${effortLevel}: Synthetic test description`,
            snapshot: this.snapshot,
            panel: null,
          } as TResult
        }
        if (commandName === 'compact') {
          const now = '2026-04-02T00:00:00Z'
          this.snapshot = {
            ...this.snapshot,
            session: {
              ...this.snapshot.session,
              updatedAt: now,
              userMessageCount: 0,
              assistantMessageCount: 0,
              totalMessageCount: 2,
            },
            messages: [
              {
                kind: 'compact_boundary',
                id: 'compact-boundary-1',
                createdAt: now,
                text: 'Conversation compacted',
                phase: 'manual',
                reason: 'preTokens=42, summarized=2',
              },
              {
                kind: 'compact_summary',
                id: 'compact-summary-1',
                createdAt: now,
                text: 'This session is being continued from a previous conversation that ran out of context.\n\nSummary:\n1. Primary Request and Intent:\n- Review the repo',
              },
            ],
          }
          return {
            message: 'Compacted conversation history into a summary.',
            snapshot: this.snapshot,
            panel: null,
          } as TResult
        }
        if (commandName === 'context') {
          return {
            message: 'Rendered context usage panel.',
            snapshot: this.snapshot,
            panel: {
              kind: 'context',
              title: 'Context',
              subtitle: 'Provider-visible context for the active session.',
              sections: [
                {
                  title: 'Overview',
                  lines: [
                    'Provider: OpenAI',
                    'Model: gpt-5.3-codex',
                    'Session: session-test',
                    'Workspace: /tmp/workspace',
                    'Projected prompt messages: 4',
                    'Provider-visible session messages: 2',
                  ],
                },
                {
                  title: 'Budget',
                  lines: [
                    'Estimated input context: 128 tokens',
                    'Context window: 200000 tokens',
                    'Remaining headroom: 199872 tokens',
                    'Usage: 0.1%',
                    'Status: healthy',
                  ],
                },
                {
                  title: 'Breakdown',
                  lines: [
                    'Base system prompt: 32 tokens',
                    'AGENTS instructions: 16 tokens',
                    'Conversation + retained tool context: 80 tokens',
                    'System messages in provider view: 2',
                    'Assistant messages in provider view: 1',
                    'User/tool-result messages in provider view: 1',
                  ],
                },
              ],
              contextUsage: {
                estimatedTokens: 128,
                contextWindowTokens: 200000,
                usedCells: 1,
                totalCells: 24,
                status: 'healthy',
              },
            },
          } as TResult
        }
        if (commandName === 'cost') {
          return {
            message: 'Rendered session cost summary.',
            snapshot: this.snapshot,
            panel: {
              kind: 'cost',
              title: 'Cost',
              subtitle: 'Session timing and estimated context usage.',
              sections: [
                {
                  title: 'Overview',
                  lines: [
                    'Started: 2026-04-02T00:00:00Z',
                    'Updated: 2026-04-02T00:00:00Z',
                    'User turns: 1',
                    'Assistant turns: 1',
                    'Estimated context: 128 tokens',
                  ],
                },
              ],
            },
          } as TResult
        }
        if (commandName === 'diff') {
          return {
            message: 'Rendered workspace diff.',
            snapshot: this.snapshot,
            panel: {
              kind: 'diff',
              title: 'Workspace Diff',
              subtitle: 'Uncommitted workspace changes.',
              sections: [
                {
                  title: 'git status --short',
                  lines: [' M src/app.tsx'],
                },
                {
                  title: 'git diff --stat',
                  lines: ['src/app.tsx | 3 ++-'],
                },
              ],
            },
          } as TResult
        }
        if (commandName === 'doctor') {
          return {
            message: 'Rendered OpenClaude diagnostics.',
            snapshot: this.snapshot,
            panel: {
              kind: 'doctor',
              title: 'Doctor',
              subtitle: 'Runtime diagnostics.',
              sections: [
                {
                  title: 'Providers',
                  lines: ['OpenAI connected via browser_sso', 'Anthropic available'],
                },
                {
                  title: 'Context',
                  lines: ['Projected prompt tokens: 128', 'Context window: 200000'],
                },
              ],
            },
          } as TResult
        }
        return {
          message: `Executed ${commandName}.`,
          snapshot: this.snapshot,
          panel: null,
        } as TResult
      }
      default:
        throw new Error(`Fake client does not implement ${method}.`)
    }
  }

  dispose(): void {}
}

export function createSnapshot(): BackendSnapshot {
  const now = '2026-04-02T00:00:00Z'
  const commands = buildCommands()
  return {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [
        {
          providerId: 'openai',
          authMethod: 'browser_sso',
          credentialReference: 'openai/default',
          connectedAt: now,
        },
      ],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
      effortLevel: null,
    },
    session: {
      sessionId: 'session-test',
      title: 'Current session',
      startedAt: now,
      updatedAt: now,
      durationSeconds: 0,
      userMessageCount: 0,
      assistantMessageCount: 0,
      totalMessageCount: 0,
      estimatedContextTokens: 0,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      workingDirectory: '/tmp/workspace',
      workspaceRoot: '/tmp/workspace',
      planMode: false,
      todos: [],
    },
    providers: [
      {
        providerId: 'openai',
        displayName: 'OpenAI',
        supportedAuthMethods: ['api_key', 'browser_sso'],
        connected: true,
        active: true,
        connection: {
          providerId: 'openai',
          authMethod: 'browser_sso',
          credentialReference: 'openai/default',
          connectedAt: now,
        },
      },
    ],
    models: [
      {
        id: 'gpt-5.3-codex',
        displayName: 'GPT-5.3 Codex',
        providerId: 'openai',
        providerDisplayName: 'OpenAI',
        providerActive: true,
        active: true,
      },
    ],
    commands,
    messages: [],
  }
}

function createPermissionEditorSnapshot(): PermissionEditorSnapshotView {
  return {
    sessionId: 'session-test',
    workspaceDisplayPath: '/tmp/workspace',
    workspaceRoot: '/tmp/workspace',
    activeProvider: 'openai',
    activeModel: 'gpt-5.3-codex',
    tabs: [
      {
        id: 'recent',
        title: 'Recently denied',
        description: 'Commands recently denied by the auto mode classifier.',
        sourceGroups: [],
        recentActivities: [
          {
            toolUseId: 'tool-denied-1',
            toolName: 'Bash',
            status: 'denied',
            detail: 'Bash(ls -1 ~/Desktop)',
            createdAt: '2026-04-03T00:00:00Z',
          },
        ],
      },
      permissionBehaviorTab('allow', 'Allow', 'Claude Code won\'t ask before using allowed tools.', {
        session: ['Bash(ls -1 ~/Desktop)'],
      }),
      permissionBehaviorTab('ask', 'Ask', 'Claude Code will always ask for confirmation before using these tools.', {
        project: ['WebFetch(domain:example.com)'],
      }),
      permissionBehaviorTab('deny', 'Deny', 'Claude Code will always reject requests to use denied tools.', {
        local: ['Bash(rm -rf /tmp/demo)'],
      }),
    ],
  }
}

function permissionBehaviorTab(
  behavior: 'allow' | 'ask' | 'deny',
  title: string,
  description: string,
  rulesBySource: Record<string, string[]>,
): PermissionEditorSnapshotView['tabs'][number] {
  return {
    id: behavior,
    title,
    description,
    recentActivities: [],
    sourceGroups: ['session', 'user', 'project', 'local', 'policy'].map((source) => ({
      source,
      displayName: sourceLabel(source),
      editable: source !== 'policy',
      rules: (rulesBySource[source] ?? []).map((ruleString) => ({
        source,
        displayName: sourceLabel(source),
        behavior,
        toolName: ruleString.split('(')[0] ?? ruleString,
        ruleString,
        summary: `${behavior} ${ruleString}`,
        createdAt: '2026-04-03T00:00:00Z',
        editable: source !== 'policy',
      })),
    })),
  }
}

function sourceLabel(source: string): string {
  switch (source) {
    case 'session':
      return 'Current session'
    case 'user':
      return 'User settings'
    case 'project':
      return 'Shared project settings'
    case 'local':
      return 'Project local settings'
    case 'policy':
      return 'Enterprise managed settings'
    default:
      return source
  }
}

export function appendConversation(snapshot: BackendSnapshot, userText: string, assistantText: string): BackendSnapshot {
  const now = '2026-04-02T00:00:00Z'
  const messages: SessionMessageView[] = [
    ...snapshot.messages,
    {
      kind: 'user',
      id: `user-${snapshot.messages.length + 1}`,
      createdAt: now,
      text: userText,
      providerId: snapshot.state.activeProvider,
      modelId: snapshot.state.activeModelId,
    },
    {
      kind: 'assistant',
      id: `assistant-${snapshot.messages.length + 2}`,
      createdAt: now,
      text: assistantText,
      providerId: snapshot.state.activeProvider,
      modelId: snapshot.state.activeModelId,
    },
  ]

  return {
    ...snapshot,
    session: {
      ...snapshot.session,
      updatedAt: now,
      userMessageCount: snapshot.session.userMessageCount + 1,
      assistantMessageCount: snapshot.session.assistantMessageCount + 1,
      totalMessageCount: messages.length,
    },
    messages,
  }
}

function buildCommands(): CommandView[] {
  return [
    createCommand('provider', 'Connect a provider'),
    createCommand('resume', 'Resume a session'),
    createCommand('session', 'Show current session'),
    createCommand('rename', 'Rename current session'),
    createCommand('login', 'Sign in with a provider account or switch auth'),
    createCommand('logout', 'Sign out from the active provider'),
    createCommand('models', 'Select a model'),
    createCommand('config', 'Open config panel'),
    createCommand('status', 'Show current status'),
    createCommand('context', 'Visualize context usage'),
    createCommand('tools', 'List available tools'),
    createCommand('permissions', 'Manage allow & deny tool permission rules'),
    createCommand('usage', 'Show plan usage limits'),
    createCommand('stats', 'Show usage statistics and activity'),
    createCommand('copy', 'Copy the latest response'),
    createCommand('compact', 'Compact conversation history into a summary'),
    createCommand('cost', 'Show session cost'),
    createCommand('clear', 'Clear conversation history and free up context'),
    createCommand('plan', 'Enter plan mode or inspect the current plan'),
    createCommand('rewind', 'Restore the conversation to a previous checkpoint'),
    createCommand('memory', 'Edit OpenClaude memory files'),
    createCommand('diff', 'View diffs'),
    createCommand('doctor', 'Diagnose the installation'),
    createCommand('tasks', 'List and manage background tasks'),
    createCommand('fast', 'Toggle fast mode'),
    createCommand('effort', 'Set effort level for model usage'),
    createCommand('keybindings', 'Edit keybindings'),
    createCommand('help', 'Open help'),
    createCommand('exit', 'Exit OpenClaude'),
  ]
}

function createCommand(name: string, description: string): CommandView {
  const overlayMap: Record<string, CommandView['overlay']> = {
    provider: 'providers',
    models: 'models',
    config: 'config',
    keybindings: 'keybindings',
    help: 'help',
  }
  const backendCommands = new Set([
    'session',
    'status',
    'context',
    'tools',
    'permissions',
    'usage',
    'stats',
    'cost',
    'compact',
    'diff',
    'doctor',
    'effort',
  ])
  const frontendActions: Record<string, CommandView['frontendAction']> = {
    copy: 'copy',
    exit: 'exit',
  }

  return {
    name,
    displayName: name,
    description,
    aliases: [],
    execution: overlayMap[name] ? 'overlay' : backendCommands.has(name) ? 'backend' : frontendActions[name] ? 'frontend' : 'frontend',
    overlay: overlayMap[name] ?? null,
    frontendAction: frontendActions[name] ?? null,
    enabled: true,
    hidden: false,
  }
}
