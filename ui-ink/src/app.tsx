import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Box, Text, useApp } from 'ink'

import { applyInputSequence, type EditorKey } from './input/editor.ts'
import { resolveBackendLaunchConfig } from './ipc/backendLaunch.ts'
import { useTerminalInput, type InputKey } from './input/useTerminalInput.ts'
import { OpenClaudeStdioClient, type OpenClaudeClient } from './ipc/OpenClaudeStdioClient.ts'
import { listMemoryFiles, type MemoryFileEntry } from './local/memory.ts'
import { createLiveRenderableMessages } from './messages/normalizeMessages.ts'
import { copyToClipboard, editFileInExternalEditor, editTextInExternalEditor, listWorkspaceFiles, openOrCreateKeybindingsFile } from './local/system.ts'
import { appendPromptHistoryEntry, loadPromptHistory } from './local/promptHistory.ts'
import {
  getModeFromInput,
  getValueFromInput,
  prependModeCharacterToInput,
  type PromptInputMode,
} from './prompt/inputModes.ts'
import { buildPromptSuggestionState } from './prompt/suggestions.ts'
import {
  nextSuggestionIndex,
  promptFooterShortcutLines,
  resolvePromptAction,
} from './prompt/keybindings.ts'
import { Markdown } from './ui/Markdown.tsx'
import { Message } from './ui/Message.tsx'
import { Picker, type PickerOption } from './ui/Picker.tsx'
import { buildPanelViewport, nextPanelScrollOffset } from './ui/panelViewport.ts'
import {
  clampPermissionIndex,
  createPermissionsOverlayState,
  getPermissionsEditableSources,
  getPermissionsRecentActivity,
  getPermissionsTabRows,
  getPermissionsNextTab,
  type PermissionRuleBehavior,
  type PermissionsOverlayState,
  renderPermissionsOverlay,
} from './ui/PermissionsOverlay.tsx'
import { PromptComposer } from './ui/PromptComposer.tsx'
import type { FooterLine } from './ui/PromptFooter.tsx'
import type { PromptHistoryEntry } from './ui/HistorySearchOverlay.tsx'
import { PromptInputPanel } from './ui/PromptInputPanel.tsx'
import { ReplShell } from './ui/ReplShell.tsx'
import { StartupHeader } from './ui/StartupHeader.tsx'
import { StatusLine } from './ui/StatusLine.tsx'
import { uiTheme, type ThemeColor } from './ui/theme.ts'
import { Transcript } from './ui/Transcript.tsx'
import type {
  AuthMethod,
  BackendSnapshot,
  CommandResult,
  CommandView,
  ModelView,
  MutationResult,
  OpenClaudeEvent,
  PanelView,
  PermissionEditorMutationResult,
  PermissionEditorSnapshotView,
  PermissionRequestEvent,
  PromptReasoningDeltaEvent,
  PromptToolEvent,
  PromptSubmitResult,
  ProviderId,
  ProviderView,
  SessionListItemView,
  SessionMessageView,
} from '../../types/stdio/protocol.ts'
import type { RenderableUserMessage } from './messages/types.ts'

type UiConfig = {
  showTasks: boolean
}

type LiveToolState = PromptToolEvent & {
  updatedAt: number
}

type RewindableMessage = SessionMessageView

type Overlay =
  | { kind: 'commands'; options: PickerOption[]; selectedIndex: number; commands: CommandView[] }
  | { kind: 'sessions'; options: PickerOption[]; selectedIndex: number; sessions: SessionListItemView[] }
  | { kind: 'rewind'; options: PickerOption[]; selectedIndex: number; messages: RewindableMessage[] }
  | { kind: 'memory'; options: PickerOption[]; selectedIndex: number; files: MemoryFileEntry[] }
  | { kind: 'providers'; options: PickerOption[]; selectedIndex: number; providers: ProviderView[] }
  | { kind: 'auth'; options: PickerOption[]; selectedIndex: number; provider: ProviderView }
  | { kind: 'permission'; options: PickerOption[]; selectedIndex: number; request: PermissionRequestEvent }
  | PermissionsOverlayState
  | {
      kind: 'ask-user-question'
      request: PermissionRequestEvent
      payload: AskUserQuestionPayload
      planMode: boolean
      currentQuestionIndex: number
      selectedIndex: number
      answers: Record<string, string>
      selectedAnswers: Record<string, string[]>
      annotations: Record<string, AskUserQuestionAnnotation>
      footerFocused: boolean
      footerIndex: number
      editingOther: boolean
      otherText: string
      otherCursorOffset: number
      editingNotes: boolean
      notesText: string
      notesCursorOffset: number
      reviewSelectedIndex: number
    }
  | { kind: 'models'; options: PickerOption[]; selectedIndex: number; models: ModelView[] }
  | {
      kind: 'text-entry'
      mode: 'provider-auth'
      providerId: ProviderId
      authMethod: AuthMethod
      label: string
      value: string
      cursorOffset: number
      placeholder: string
    }
  | {
      kind: 'text-entry'
      mode: 'session-rename'
      label: string
      value: string
      cursorOffset: number
      placeholder: string
    }
  | { kind: 'config'; selectedIndex: number }
  | { kind: 'panel'; title: string; body: string; scrollOffset: number }
  | { kind: 'context'; scrollOffset: number }

type ContextCell = {
  color: ThemeColor
}

type ContextView = {
  summaryLines: string[]
  detailLines: string[]
  cells: ContextCell[]
}

type FooterState = {
  lines: FooterLine[]
}

type StatusEntry = {
  message: string
  tone: 'default' | 'error'
}

type CommandActivity = {
  label: string
  spin: boolean
}

type PromptHistorySearchState = {
  query: string
  queryCursorOffset: number
  selectedIndex: number
  failed: boolean
  originalDisplayValue: string
  originalDisplayCursorOffset: number
}

type CachedPermissionDecision = {
  decision: 'allow' | 'deny'
  payloadJson?: string
  updatedInputJson?: string
  userModified?: boolean
  decisionReason?: string
  interrupt?: boolean
}

type RunScopedPermissionDecision = {
  decision: 'allow'
  payloadJson?: string
  updatedInputJson?: string
  userModified?: boolean
  decisionReason?: string
  interrupt?: boolean
}

type AskUserQuestionOption = {
  label: string
  description: string
  preview?: string
}

type AskUserQuestion = {
  question: string
  header: string
  options: AskUserQuestionOption[]
  multiSelect?: boolean
}

type AskUserQuestionAnnotation = {
  preview?: string
  notes?: string
}

type AskUserQuestionPayload = {
  questions: AskUserQuestion[]
}

const backendLaunch = resolveBackendLaunchConfig()
const MAX_STATUS_LINES_VERBOSE = 6
const MAX_STATUS_LINES_COMPACT = 2
const CONTEXT_GRID_CELLS = 24
const THINKING_FRAMES = ['◐', '◓', '◑', '◒']

type AppProps = {
  clientFactory?: () => OpenClaudeClient
  workspaceFilesLoader?: () => Promise<string[]>
  memoryFilesLoader?: () => Promise<MemoryFileEntry[]>
  memoryFileEditor?: (filePath: string) => Promise<void>
  clipboardWriter?: (text: string) => Promise<void>
  keybindingsFileOpener?: (template: string) => Promise<string>
  exitWriter?: (text: string) => void
  promptHistoryLoader?: (scope: { workspaceRoot?: string | null; sessionId?: string | null }) => Promise<PromptHistoryEntry[]>
  promptHistoryAppender?: (value: string, scope: { workspaceRoot?: string | null; sessionId?: string | null }) => Promise<PromptHistoryEntry>
  showStartupStatusLines?: boolean
}

export function App(props: AppProps = {}): React.ReactElement {
  const {
    clientFactory = createDefaultClient,
    workspaceFilesLoader = listWorkspaceFiles,
    memoryFilesLoader = listMemoryFiles,
    memoryFileEditor = editFileInExternalEditor,
    clipboardWriter = copyToClipboard,
    keybindingsFileOpener = openOrCreateKeybindingsFile,
    exitWriter = (text: string) => {
      process.stdout.write(text)
    },
    promptHistoryLoader = loadPromptHistory,
    promptHistoryAppender = appendPromptHistoryEntry,
    showStartupStatusLines = true,
  } = props
  const { exit } = useApp()
  const [client] = useState<OpenClaudeClient>(() => clientFactory())
  const [snapshot, setSnapshot] = useState<BackendSnapshot | null>(null)
  const [overlay, setOverlay] = useState<Overlay | null>(null)
  const [input, setInput] = useState('')
  const [commandActivity, setCommandActivity] = useState<CommandActivity | null>(null)
  const [inputMode, setInputMode] = useState<PromptInputMode>('prompt')
  const [cursorOffset, setCursorOffset] = useState(0)
  const [stashedInput, setStashedInput] = useState('')
  const [statusLines, setStatusLines] = useState<StatusEntry[]>(() => (
    showStartupStatusLines ? [createStatusEntry('Starting OpenClaude UI...')] : []
  ))
  const [streamingText, setStreamingText] = useState('')
  const [streamingReasoning, setStreamingReasoning] = useState('')
  const [liveToolCalls, setLiveToolCalls] = useState<LiveToolState[]>([])
  const activeLiveToolCalls = useMemo(
    () => liveToolCalls.filter((toolCall) => toolCall.phase !== 'completed' && toolCall.phase !== 'failed'),
    [liveToolCalls],
  )
  const [busy, setBusy] = useState(false)
  const isCompacting = commandActivity?.label === 'Compacting…'
  const [pendingPrompt, setPendingPrompt] = useState('')
  const [thinkingFrameIndex, setThinkingFrameIndex] = useState(0)
  const [config, setConfig] = useState<UiConfig>({ showTasks: false })
  const [workspaceFiles, setWorkspaceFiles] = useState<string[]>([])
  const [promptHistory, setPromptHistory] = useState<PromptHistoryEntry[]>([])
  const [historySearch, setHistorySearch] = useState<PromptHistorySearchState | null>(null)
  const [historyCursor, setHistoryCursor] = useState<number | null>(null)
  const [historyDraft, setHistoryDraft] = useState('')
  const [historyModeFilter, setHistoryModeFilter] = useState<PromptInputMode | null>(null)
  const [promptSuggestionIndex, setPromptSuggestionIndex] = useState(0)
  const [dismissedPromptSuggestionsForKey, setDismissedPromptSuggestionsForKey] = useState<string | null>(null)
  const [startupHomeVisible, setStartupHomeVisible] = useState(true)
  const [transcriptBaseline, setTranscriptBaseline] = useState(0)
  const [resumeSuggestions, setResumeSuggestions] = useState<SessionListItemView[]>([])
  const [permissionQueue, setPermissionQueue] = useState<PermissionRequestEvent[]>([])
  const [interruptRequested, setInterruptRequested] = useState(false)
  const promptStateRef = useRef({ value: '', cursorOffset: 0 })
  const inputModeRef = useRef<PromptInputMode>('prompt')
  const dismissedPromptSuggestionsForKeyRef = useRef<string | null>(null)
  const promptRunCounterRef = useRef(0)
  const activePromptRunIdRef = useRef<number | null>(null)
  const activeBackendPromptRequestIdRef = useRef<string | null>(null)
  const resumeSuggestionsLoadedRef = useRef(false)
  const handledPermissionRequestIdsRef = useRef<Set<string>>(new Set())
  const permissionDecisionCacheRef = useRef<Map<string, CachedPermissionDecision>>(new Map())
  const promptRunPermissionDecisionCacheRef = useRef<Map<string, RunScopedPermissionDecision>>(new Map())

  const applyInputMode = useCallback((nextMode: PromptInputMode) => {
    inputModeRef.current = nextMode
    setInputMode(nextMode)
  }, [])

  const applyPromptState = useCallback((value: string, nextCursorOffset: number) => {
    promptStateRef.current = { value, cursorOffset: nextCursorOffset }
    setInput(value)
    setCursorOffset(nextCursorOffset)
  }, [])

  const applyDisplayPromptState = useCallback((displayValue: string, nextCursorOffset?: number) => {
    const nextMode = getModeFromInput(displayValue)
    const nextValue = getValueFromInput(displayValue)
    const valueCursorOffset = nextCursorOffset == null
      ? nextValue.length
      : Math.max(0, Math.min(nextValue.length, nextCursorOffset - (nextMode === 'bash' ? 1 : 0)))
    applyInputMode(nextMode)
    applyPromptState(nextValue, valueCursorOffset)
  }, [applyInputMode, applyPromptState])

  const syncHistorySearchSelection = useCallback((nextState: PromptHistorySearchState | null) => {
    if (!nextState) {
      setHistorySearch(null)
      return
    }

    if (nextState.query.length === 0) {
      applyDisplayPromptState(nextState.originalDisplayValue, nextState.originalDisplayCursorOffset)
      setHistorySearch({ ...nextState, selectedIndex: 0, failed: false })
      return
    }

    const matches = findPromptHistorySearchMatches(promptHistory, nextState.query)
    if (matches.length === 0) {
      setHistorySearch({ ...nextState, selectedIndex: 0, failed: true })
      return
    }

    const selectedIndex = Math.max(0, Math.min(nextState.selectedIndex, matches.length - 1))
    const selected = matches[selectedIndex]!
    const matchOffset = selected.value.lastIndexOf(nextState.query)
    applyDisplayPromptState(selected.value, matchOffset === -1 ? selected.value.length : matchOffset)
    setHistorySearch({ ...nextState, selectedIndex, failed: false })
  }, [applyDisplayPromptState, promptHistory])

  useEffect(() => {
    promptStateRef.current = { value: input, cursorOffset }
  }, [cursorOffset, input])

  useEffect(() => {
    const onStderr = (line: string) => {
      if (!shouldDisplayStderrLine(line)) {
        return
      }
      setStatusLines((current) => [createStatusEntry(line), ...current].slice(0, MAX_STATUS_LINES_VERBOSE))
    }
    client.on('stderr', onStderr)

    void client
      .request<BackendSnapshot>('initialize')
      .then((initial) => {
        setSnapshot(initial)
        setTranscriptBaseline(initial.messages.length)
        setStatusLines(buildInitialStatusLines(initial, showStartupStatusLines))
        const workspaceRoot = initial.session.workspaceRoot ?? initial.session.workingDirectory ?? null
        const sessionId = initial.session.sessionId ?? null
        if (workspaceRoot) {
          void promptHistoryLoader({ workspaceRoot, sessionId })
            .then((entries) => {
              setPromptHistory(entries)
            })
            .catch((error) => {
              setStatusLines((current) => [createStatusEntry(`Prompt history load failed: ${String(error)}`, 'error'), ...current].slice(0, MAX_STATUS_LINES_VERBOSE))
            })
        }
      })
      .catch((error) => {
        setStatusLines([createStatusEntry(String(error), 'error')])
      })

    return () => {
      client.off('stderr', onStderr)
      client.dispose()
    }
  }, [client, promptHistoryLoader, showStartupStatusLines])

  useEffect(() => {
    void workspaceFilesLoader()
      .then((files) => setWorkspaceFiles(files))
      .catch((error) => setStatusLines((current) => [createStatusEntry(`Workspace file scan failed: ${String(error)}`, 'error'), ...current].slice(0, MAX_STATUS_LINES_VERBOSE)))
  }, [workspaceFilesLoader])

  useEffect(() => {
    if (!busy) {
      setThinkingFrameIndex(0)
      return
    }

    const interval = setInterval(() => {
      setThinkingFrameIndex((current) => (current + 1) % THINKING_FRAMES.length)
    }, 140)

    return () => clearInterval(interval)
  }, [busy])

  useEffect(() => {
    const nextPermissionRequest = permissionQueue[0] ?? null
    if (!nextPermissionRequest) {
      setOverlay((current) => (current?.kind === 'permission' || current?.kind === 'ask-user-question' ? null : current))
      return
    }

    setOverlay((current) => {
      if (
        (current?.kind === 'permission' || current?.kind === 'ask-user-question')
          && current.request.requestId === nextPermissionRequest.requestId
      ) {
        return current
      }
      return createInteractionOverlay(nextPermissionRequest, snapshot?.session.planMode ?? false)
    })
  }, [permissionQueue, snapshot?.session.planMode])

  const refreshSnapshot = useCallback(async () => {
    const next = await client.request<BackendSnapshot>('state.snapshot')
    setSnapshot(next)
  }, [client])

  const addStatus = useCallback((message: string) => {
    if (!message) {
      return
    }
    setStatusLines((current) => [createStatusEntry(message), ...current].slice(0, MAX_STATUS_LINES_VERBOSE))
  }, [])

  const addErrorStatus = useCallback((message: string) => {
    if (!message) {
      return
    }
    setStatusLines((current) => [createStatusEntry(message, 'error'), ...current].slice(0, MAX_STATUS_LINES_VERBOSE))
  }, [])

  useEffect(() => {
    const workspaceRoot = snapshot?.session.workspaceRoot ?? snapshot?.session.workingDirectory ?? null
    const sessionId = snapshot?.session.sessionId ?? null
    if (!workspaceRoot) {
      return
    }

    let cancelled = false
    void promptHistoryLoader({ workspaceRoot, sessionId })
      .then((entries) => {
        if (!cancelled) {
          setPromptHistory(entries)
        }
      })
      .catch((error) => {
        if (!cancelled) {
          addStatus(`Prompt history load failed: ${String(error)}`)
        }
      })

    return () => {
      cancelled = true
    }
  }, [
    addStatus,
    promptHistoryLoader,
    snapshot?.session.sessionId,
    snapshot?.session.workingDirectory,
    snapshot?.session.workspaceRoot,
  ])

  useEffect(() => {
    resumeSuggestionsLoadedRef.current = false
    setResumeSuggestions([])
  }, [snapshot?.session.workspaceRoot, snapshot?.session.workingDirectory])

  useEffect(() => {
    const trimmed = input.trimStart()
    if (inputMode !== 'prompt' || !trimmed.startsWith('/resume')) {
      return
    }
    if (resumeSuggestionsLoadedRef.current) {
      return
    }

    let cancelled = false
    resumeSuggestionsLoadedRef.current = true
    void client.request<{ sessions: SessionListItemView[] }>('sessions.list')
      .then((result) => {
        if (!cancelled) {
          setResumeSuggestions(result.sessions)
        }
      })
      .catch((error) => {
        if (!cancelled) {
          addStatus(`Session suggestions unavailable: ${String(error)}`)
        }
      })

    return () => {
      cancelled = true
    }
  }, [addStatus, client, input, inputMode])

  const exitApp = useCallback(() => {
    const sessionId = snapshot?.session?.sessionId ?? snapshot?.state?.activeSessionId
    if (sessionId) {
      exitWriter(
        `\nResume this session with:\nopenclaude --resume ${sessionId}\n`,
      )
    }
    exit()
  }, [exit, exitWriter, snapshot?.session?.sessionId, snapshot?.state?.activeSessionId])

  const updateSettings = useCallback(
    async (patch: {
      fastMode?: boolean
      verboseOutput?: boolean
      reasoningVisible?: boolean
      alwaysCopyFullResponse?: boolean
      effortLevel?: string | null
    }) => {
      const result = await client.request<MutationResult>('settings.update', patch)
      setSnapshot(result.snapshot)
      addStatus(result.message)
    },
    [addStatus, client],
  )

  const openPanel = useCallback((title: string, body: string) => {
    setOverlay({ kind: 'panel', title, body, scrollOffset: 0 })
  }, [])

  const openProviders = useCallback(() => {
    if (!snapshot) {
      return
    }
    const options = snapshot.providers.map((provider) => ({
      key: provider.providerId,
      label: `${provider.active ? '●' : provider.connected ? '○' : ' '} ${provider.displayName}`,
      detail: [
        provider.supportedAuthMethods.join(', '),
        provider.connection ? `connected via ${provider.connection.authMethod}` : null,
      ]
        .filter(Boolean)
        .join(' · '),
    }))
    setOverlay({ kind: 'providers', options, selectedIndex: 0, providers: snapshot.providers })
  }, [snapshot])

  const openSessions = useCallback(async () => {
    const result = await client.request<{ sessions: SessionListItemView[] }>('sessions.list')
    if (result.sessions.length === 0) {
      addStatus('No resumable sessions found for the current workspace.')
      return
    }
    const options = result.sessions.map((session) => ({
      key: session.sessionId,
      label: session.title,
      detail: [
        `${session.messageCount} messages`,
        new Date(session.updatedAt).toLocaleString(),
        session.preview || null,
      ]
        .filter(Boolean)
        .join(' · '),
    }))
    setOverlay({ kind: 'sessions', options, selectedIndex: 0, sessions: result.sessions })
  }, [addStatus, client])

  const openRewind = useCallback(() => {
    if (!snapshot) {
      return
    }
    const messages = snapshot.messages.filter((message) => message.kind === 'user')
    if (messages.length === 0) {
      addStatus('No rewind checkpoints are available in the current session.')
      return
    }
    const options = [...messages].reverse().map((message) => ({
      key: message.id,
      label: summarizeText(message.text, 72),
      detail: new Date(message.createdAt).toLocaleString(),
    }))
    setOverlay({ kind: 'rewind', options, selectedIndex: 0, messages: [...messages].reverse() })
  }, [addStatus, snapshot])

  const openMemory = useCallback(async () => {
    try {
      const files = await memoryFilesLoader()
      if (files.length === 0) {
        addStatus('No editable AGENTS memory files were found for this workspace.')
        return
      }
      const options = files.map((file) => ({
        key: file.path,
        label: file.label,
        detail: file.detail,
      }))
      setOverlay({ kind: 'memory', options, selectedIndex: 0, files })
    } catch (error) {
      addStatus(`Failed to load memory files: ${String(error)}`)
    }
  }, [addStatus, memoryFilesLoader])

  const openModels = useCallback(() => {
    if (!snapshot || snapshot.models.length === 0) {
      addStatus('No connected models. Use /provider first.')
      return
    }
    const options = snapshot.models.map((model) => ({
      key: `${model.providerId}:${model.id}`,
      label: `${model.active ? '●' : model.providerActive ? '○' : ' '} ${model.displayName}`,
      detail: `${model.providerDisplayName} · ${model.id}`,
    }))
    setOverlay({ kind: 'models', options, selectedIndex: 0, models: snapshot.models })
  }, [addStatus, snapshot])

  const resumeSessionById = useCallback(async (sessionId: string) => {
    const result = await client.request<MutationResult>('sessions.resume', { sessionId })
    setSnapshot(result.snapshot)
    setTranscriptBaseline(0)
    setStartupHomeVisible(false)
    addStatus(result.message)
  }, [addStatus, client])

  const selectModelByToken = useCallback(async (token: string) => {
    if (!snapshot) {
      return
    }

    const trimmed = token.trim()
    if (!trimmed) {
      return
    }

    const scopedSeparator = trimmed.indexOf(':')
    let selectedProviderId: ProviderId | null = null
    let selectedModelId = trimmed
    if (scopedSeparator > 0) {
      selectedProviderId = trimmed.slice(0, scopedSeparator) as ProviderId
      selectedModelId = trimmed.slice(scopedSeparator + 1)
    }

    const model = selectedProviderId
      ? snapshot.models.find((entry) => entry.providerId === selectedProviderId && entry.id === selectedModelId)
      : snapshot.models.find((entry) => entry.id === selectedModelId)

    if (!model) {
      addStatus(`Unknown model: ${trimmed}`)
      return
    }

    const result = await client.request<MutationResult>('models.select', {
      providerId: model.providerId,
      modelId: model.id,
    })
    setSnapshot(result.snapshot)
    addStatus(result.message)
  }, [addStatus, client, snapshot])

  const openConfig = useCallback(() => {
    setOverlay({ kind: 'config', selectedIndex: 0 })
  }, [])

  const openHelp = useCallback(() => {
    openPanel('Help', buildHelpBody())
  }, [openPanel])

  const openPermissionsEditor = useCallback(async () => {
    const result = await client.request<PermissionEditorSnapshotView>('permissions.editor.snapshot')
    setOverlay(createPermissionsOverlayState(result))
  }, [client])

  const openCommandPalette = useCallback(() => {
    if (!snapshot) {
      return
    }
    const commands = snapshot.commands.filter((command) => command.enabled && !command.hidden)
    const options = commands.map((command) => ({
      key: command.name,
      label: `/${command.displayName}${command.argumentHint ? ` ${command.argumentHint}` : ''}`,
      detail: command.description,
    }))
    setOverlay({ kind: 'commands', options, selectedIndex: 0, commands })
  }, [snapshot])

  const runBackendCommand = useCallback(
    async (command: CommandView, args: string) => {
      const activity =
        command.name === 'compact'
          ? { label: 'Compacting…', spin: true }
          : null
      if (activity) {
        setCommandActivity(activity)
      }
      try {
        const result = await client.request<CommandResult>('command.run', {
          commandName: command.name,
          args,
        })
        setSnapshot(result.snapshot)
        if (result.message) {
          addStatus(result.message)
        }
        if (result.panel) {
          openPanel(result.panel.title, flattenPanel(result.panel))
        }
      } finally {
        if (activity) {
          setCommandActivity(null)
        }
      }
    },
    [addStatus, client, openPanel],
  )

  const handleCopyCommand = useCallback(
    (args: string) => {
      if (!snapshot) {
        return
      }

      const assistantMessages = snapshot.messages.filter((message) => message.kind === 'assistant')
      if (assistantMessages.length === 0) {
        addStatus('No assistant response is available to copy yet.')
        return
      }

      const requestedIndex = Number.parseInt(args.trim(), 10)
      const offset = Number.isFinite(requestedIndex) && requestedIndex > 0 ? requestedIndex - 1 : 0
      const message = assistantMessages[assistantMessages.length - 1 - offset]
      if (!message) {
        addStatus(`There is no assistant response at index ${requestedIndex}.`)
        return
      }

      void clipboardWriter(message.text)
        .then(() => addStatus(`Copied ${message.text.length} characters to the clipboard.`))
        .catch((error) => addStatus(String(error)))
    },
    [addStatus, clipboardWriter, snapshot],
  )

  const executeCommand = useCallback(
    async (command: CommandView, args = '') => {
      switch (command.name) {
        case 'resume':
          if (args.trim()) {
            await resumeSessionById(args.trim())
            return
          }
          await openSessions()
          return
        case 'session':
          await runBackendCommand(command, args)
          return
        case 'rename':
          if (args.trim()) {
            const result = await client.request<MutationResult>('sessions.rename', { title: args.trim() })
            setSnapshot(result.snapshot)
            addStatus(result.message)
            return
          }
          setOverlay({
            kind: 'text-entry',
            mode: 'session-rename',
            label: 'Session title',
            value: '',
            cursorOffset: 0,
            placeholder: snapshot?.session.title ?? 'Describe this session',
          })
          return
        case 'login':
          addStatus('Select a provider, then choose how to connect it.')
          openProviders()
          return
        case 'logout': {
          const activeProvider = snapshot?.state.activeProvider
          if (!activeProvider) {
            addStatus('No active provider is connected.')
            return
          }

          const result = await client.request<MutationResult>('provider.disconnect', { providerId: activeProvider })
          setSnapshot(result.snapshot)
          addStatus(result.message)
          return
        }
        case 'provider':
          openProviders()
          return
        case 'models':
        case 'model':
          if (args.trim()) {
            await selectModelByToken(args.trim())
            return
          }
          openModels()
          return
        case 'config':
          openConfig()
          return
        case 'fast': {
          if (!snapshot) {
            return
          }
          const normalized = args.trim().toLowerCase()
          if (normalized && normalized !== 'on' && normalized !== 'off') {
            addStatus('Usage: /fast [on|off]')
            return
          }

          const enable = normalized === 'on' ? true : normalized === 'off' ? false : !snapshot.settings.fastMode
          const result = await client.request<MutationResult>('settings.update', { fastMode: enable })
          setSnapshot(result.snapshot)
          addStatus(`Fast mode ${enable ? 'ON' : 'OFF'}.`)
          return
        }
        case 'tasks':
        case 'bashes':
          setConfig((current) => ({ ...current, showTasks: !current.showTasks }))
          addStatus(`Background task panel ${config.showTasks ? 'hidden' : 'shown'}.`)
          return
        case 'help':
          openHelp()
          return
        case 'keybindings': {
          try {
            const filePath = await keybindingsFileOpener(defaultKeybindingsTemplate())
            addStatus(`Opened ${filePath} for keybinding customization.`)
          } catch (error) {
            addStatus(String(error))
          }
          return
        }
        case 'context':
          await runBackendCommand(command, args)
          return
        case 'permissions':
          if (!snapshot) {
            return
          }
          if (!args.trim()) {
            await openPermissionsEditor()
            return
          }
          await runBackendCommand(findCommand(snapshot.commands, 'permissions') ?? command, args)
          return
        case 'copy':
          handleCopyCommand(args)
          return
        case 'plan': {
          if (!snapshot) {
            return
          }

          const normalizedArgs = args.trim()
          const normalizedVerb = normalizedArgs.toLowerCase()
          if (!snapshot.session.planMode) {
            const result = await client.request<MutationResult>('sessions.plan_mode', { enabled: true })
            setSnapshot(result.snapshot)
            addStatus(result.message)

            if (normalizedArgs && normalizedVerb !== 'open') {
              setStartupHomeVisible(false)
              const submitted = await submitPrompt(normalizedArgs)
              if (submitted) {
                await refreshSnapshot()
              }
            }
            return
          }

          addStatus('Already in plan mode. No plan written yet.')
          if (normalizedVerb === 'open') {
            addStatus('Plan-file editing is not wired yet in OpenClaude v0.')
            return
          }
          openPanel('Plan', buildPlanModeBody(snapshot))
          return
        }
        case 'rewind':
          openRewind()
          return
        case 'memory':
          await openMemory()
          return
        case 'clear':
        case 'reset':
        case 'new': {
          const result = await client.request<MutationResult>('sessions.clear')
          setSnapshot(result.snapshot)
          setTranscriptBaseline(result.snapshot.messages.length)
          setStreamingText('')
          setStreamingReasoning('')
          setLiveToolCalls([])
          setPendingPrompt('')
          setStartupHomeVisible(true)
          addStatus(result.message)
          return
        }
        case 'compact':
          if (isCompacting) {
            addErrorStatus('Cannot run /compact while compaction is already in progress.')
            return
          }
          if (activeLiveToolCalls.length > 0) {
            addErrorStatus('Cannot run /compact while a tool is still in progress.')
            return
          }
          if (busy) {
            addErrorStatus('Cannot run /compact while another prompt is in progress.')
            return
          }
          await runBackendCommand(command, args)
          setTranscriptBaseline(0)
          setStartupHomeVisible(false)
          return
        case 'exit':
        case 'quit':
          exitApp()
          return
      }

      await runBackendCommand(command, args)
    },
    [
      activeLiveToolCalls.length,
      addErrorStatus,
      addStatus,
      client,
      exitApp,
      handleCopyCommand,
      isCompacting,
      keybindingsFileOpener,
      openConfig,
      openHelp,
      openMemory,
      openModels,
      openPermissionsEditor,
      openProviders,
      openRewind,
      openSessions,
      resumeSessionById,
      runBackendCommand,
      selectModelByToken,
      busy,
      snapshot?.session.title,
    ],
  )

  const submitPrompt = useCallback(
    async (text: string) => {
      const runId = ++promptRunCounterRef.current
      activePromptRunIdRef.current = runId
      setInterruptRequested(false)
      setBusy(true)
      setPendingPrompt(text)
      setStreamingText('')
      setStreamingReasoning('')
      setLiveToolCalls([])
      setPermissionQueue([])
      handledPermissionRequestIdsRef.current = new Set()
      promptRunPermissionDecisionCacheRef.current = new Map()
      setOverlay((current) => (current?.kind === 'permission' || current?.kind === 'ask-user-question' ? null : current))
      try {
        const requestHandle = client.startRequest<PromptSubmitResult>('prompt.submit', { text }, (event) => {
          if (activePromptRunIdRef.current !== runId) {
            return
          }
          handleEvent(
            event,
            setStreamingText,
            setStreamingReasoning,
            setLiveToolCalls,
            addStatus,
            (permissionRequest) => {
              const signature = permissionRequestSignature(permissionRequest)
              const cachedDecision = permissionDecisionCacheRef.current.get(signature)
              if (cachedDecision && !permissionRequest.interactionType) {
                handledPermissionRequestIdsRef.current = new Set(handledPermissionRequestIdsRef.current).add(permissionRequest.requestId)
                setPermissionQueue((current) => current.filter((queued) => permissionRequestSignature(queued) !== signature))
                setOverlay((current) => (
                  (current?.kind === 'permission' || current?.kind === 'ask-user-question')
                    && permissionRequestSignature(current.request) === signature
                    ? null
                    : current
                ))
                void client.request<MutationResult>('permission.respond', {
                  requestId: permissionRequest.requestId,
                  decision: cachedDecision.decision,
                  ...(cachedDecision.payloadJson ? { payloadJson: cachedDecision.payloadJson } : {}),
                  ...(cachedDecision.updatedInputJson ? { updatedInputJson: cachedDecision.updatedInputJson } : {}),
                  ...(cachedDecision.userModified ? { userModified: cachedDecision.userModified } : {}),
                  ...(cachedDecision.decisionReason ? { decisionReason: cachedDecision.decisionReason } : {}),
                  ...(cachedDecision.interrupt ? { interrupt: cachedDecision.interrupt } : {}),
                }).then((response) => {
                  setSnapshot(response.snapshot)
                }).catch((error) => {
                  addStatus(formatErrorMessage(error))
                })
                return
              }
              const runScopedSignature = permissionRequestRunSignature(permissionRequest)
              const runScopedDecision = !permissionRequest.interactionType
                ? promptRunPermissionDecisionCacheRef.current.get(runScopedSignature)
                : undefined
              if (runScopedDecision) {
                handledPermissionRequestIdsRef.current = new Set(handledPermissionRequestIdsRef.current).add(permissionRequest.requestId)
                setPermissionQueue((current) => current.filter((queued) => permissionRequestRunSignature(queued) !== runScopedSignature))
                setOverlay((current) => (
                  (current?.kind === 'permission' || current?.kind === 'ask-user-question')
                    && permissionRequestRunSignature(current.request) === runScopedSignature
                    ? null
                    : current
                ))
                void client.request<MutationResult>('permission.respond', {
                  requestId: permissionRequest.requestId,
                  decision: runScopedDecision.decision,
                  ...(runScopedDecision.payloadJson ? { payloadJson: runScopedDecision.payloadJson } : {}),
                  ...(runScopedDecision.updatedInputJson ? { updatedInputJson: runScopedDecision.updatedInputJson } : {}),
                  ...(runScopedDecision.userModified ? { userModified: runScopedDecision.userModified } : {}),
                  ...(runScopedDecision.decisionReason ? { decisionReason: runScopedDecision.decisionReason } : {}),
                  ...(runScopedDecision.interrupt ? { interrupt: runScopedDecision.interrupt } : {}),
                }).then((response) => {
                  setSnapshot(response.snapshot)
                }).catch((error) => {
                  addStatus(formatErrorMessage(error))
                })
                return
              }
              setPermissionQueue((current) => {
                if (handledPermissionRequestIdsRef.current.has(permissionRequest.requestId)) {
                  return current
                }
                return current.some((queued) => queued.requestId === permissionRequest.requestId)
                  ? current
                  : [...current, permissionRequest]
              })
            },
          )
        })
        activeBackendPromptRequestIdRef.current = requestHandle.id
        const result = await requestHandle.promise
        setSnapshot(result.snapshot)
        return true
      } catch (error) {
        if (error instanceof Error && error.message === 'Prompt cancelled.') {
          return false
        }
        addStatus(formatErrorMessage(error))
        return false
      } finally {
        activeBackendPromptRequestIdRef.current = null
        if (activePromptRunIdRef.current === runId) {
          activePromptRunIdRef.current = null
        }
        setBusy(false)
        setInterruptRequested(false)
        setPendingPrompt('')
        setStreamingText('')
        setStreamingReasoning('')
        setLiveToolCalls([])
        setPermissionQueue([])
        handledPermissionRequestIdsRef.current = new Set()
        promptRunPermissionDecisionCacheRef.current = new Map()
        setOverlay((current) => (current?.kind === 'permission' || current?.kind === 'ask-user-question' ? null : current))
      }
    },
    [addStatus, client],
  )

  const cancelActivePrompt = useCallback(async () => {
    const requestId = activeBackendPromptRequestIdRef.current
    if (!requestId) {
      return false
    }
    setInterruptRequested(true)
    try {
      await client.request<MutationResult>('prompt.cancel', { requestId })
      return true
    } catch (error) {
      if (!(error instanceof Error) || error.message !== 'Prompt request is no longer active.') {
        addStatus(formatErrorMessage(error))
      }
      setInterruptRequested(false)
      return false
    }
  }, [addStatus, client])

  const handleSlashCommand = useCallback(
    async (value: string) => {
      const parsed = parseSlashCommand(value)
      if (!parsed) {
        addStatus(`Invalid command: ${value}`)
        return
      }

      const command = findCommand(snapshot?.commands ?? [], parsed.name)
      if (!command) {
        addStatus(`Unsupported command: /${parsed.name}`)
        return
      }

      await executeCommand(command, parsed.args)
    },
    [addStatus, executeCommand, snapshot?.commands],
  )

  const openExternalEditor = useCallback(async () => {
    try {
      const currentInputMode = inputModeRef.current
      const nextInput = (await editTextInExternalEditor(prependModeCharacterToInput(input, currentInputMode))).replace(/\s+$/, '')
      applyDisplayPromptState(nextInput)
      addStatus('Updated input from external editor.')
    } catch (error) {
      addStatus(String(error))
    }
  }, [addStatus, applyDisplayPromptState, input])

  const startHistorySearch = useCallback(() => {
    const currentPromptState = promptStateRef.current
    const currentInputMode = inputModeRef.current
    const displayValue = prependModeCharacterToInput(currentPromptState.value, currentInputMode)
    syncHistorySearchSelection({
      query: displayValue,
      queryCursorOffset: displayValue.length,
      selectedIndex: 0,
      failed: false,
      originalDisplayValue: displayValue,
      originalDisplayCursorOffset: currentPromptState.cursorOffset + (currentInputMode === 'bash' ? 1 : 0),
    })
  }, [syncHistorySearchSelection])

  const advanceHistorySearch = useCallback(() => {
    if (!historySearch) {
      return
    }
    syncHistorySearchSelection({
      ...historySearch,
      selectedIndex: historySearch.selectedIndex + 1,
    })
  }, [historySearch, syncHistorySearchSelection])

  const acceptHistorySearch = useCallback(() => {
    setHistorySearch(null)
    setHistoryCursor(null)
    setHistoryModeFilter(null)
    setHistoryDraft('')
  }, [])

  const cancelHistorySearch = useCallback(() => {
    setHistorySearch((current) => {
      if (!current) {
        return current
      }
      applyDisplayPromptState(current.originalDisplayValue, current.originalDisplayCursorOffset)
      return null
    })
    setHistoryCursor(null)
    setHistoryModeFilter(null)
    setHistoryDraft('')
  }, [applyDisplayPromptState])

  const toggleStash = useCallback(() => {
    if (input.trim()) {
      setStashedInput(prependModeCharacterToInput(input, inputModeRef.current))
      applyPromptState('', 0)
      addStatus('Stashed the current prompt.')
      return
    }

    if (stashedInput) {
      applyDisplayPromptState(stashedInput)
      setStashedInput('')
      addStatus('Restored the stashed prompt.')
      return
    }

    addStatus('Nothing to stash or restore.')
  }, [addStatus, applyDisplayPromptState, applyPromptState, input, stashedInput])

  const commitInput = useCallback(async (overrideValue?: string) => {
    const rawValue = overrideValue ?? promptStateRef.current.value
    const value = rawValue.trim()
    const currentInputMode = inputModeRef.current
    if (!value) {
      return
    }
    if (isCompacting) {
      addErrorStatus('Cannot submit a new prompt while compaction is in progress.')
      return
    }
    if (busy) {
      const parsed = currentInputMode === 'prompt' ? parseSlashCommand(value) : null
      if (parsed?.name === 'compact') {
        if (activeLiveToolCalls.length > 0) {
          addErrorStatus('Cannot run /compact while a tool is still in progress.')
        } else {
          addErrorStatus('Cannot run /compact while another prompt is in progress.')
        }
      }
      return
    }

    if (currentInputMode === 'prompt' && value === '/') {
      applyPromptState('', 0)
      setHistoryCursor(null)
      setHistoryModeFilter(null)
      setHistoryDraft('')
      openCommandPalette()
      return
    }
    if (currentInputMode === 'prompt' && value.startsWith('/')) {
      applyPromptState('', 0)
      setHistoryCursor(null)
      setHistoryModeFilter(null)
      setHistoryDraft('')
      try {
        await handleSlashCommand(value)
      } catch (error) {
        applyPromptState(rawValue, rawValue.length)
        addStatus(formatErrorMessage(error))
      }
      return
    }

    const historyValue = prependModeCharacterToInput(value, currentInputMode)
    const historyScope = {
      workspaceRoot: snapshot?.session.workspaceRoot ?? snapshot?.session.workingDirectory ?? null,
      sessionId: snapshot?.session.sessionId ?? null,
    }
    setPromptHistory((current) => {
      if (current[0]?.value === historyValue) {
        return current
      }
      return [{ value: historyValue, createdAt: Date.now() }, ...current].slice(0, 100)
    })
    void promptHistoryAppender(historyValue, historyScope).catch((error) => {
      addStatus(`Prompt history save failed: ${String(error)}`)
    })
    setStartupHomeVisible(false)
    applyInputMode('prompt')
    applyPromptState('', 0)
    setHistoryCursor(null)
    setHistoryModeFilter(null)
    setHistoryDraft('')
    const submitted = await submitPrompt(historyValue)
    if (!submitted) {
      applyInputMode(currentInputMode)
      applyPromptState(rawValue, rawValue.length)
      return
    }
    try {
      await refreshSnapshot()
    } catch (error) {
      addStatus(formatErrorMessage(error))
    }
  }, [
    addErrorStatus,
    addStatus,
    activeLiveToolCalls.length,
    applyInputMode,
    applyPromptState,
    busy,
    handleSlashCommand,
    isCompacting,
    openCommandPalette,
    promptHistoryAppender,
    refreshSnapshot,
    snapshot?.session.sessionId,
    snapshot?.session.workingDirectory,
    snapshot?.session.workspaceRoot,
    submitPrompt,
  ])

  const executeHistorySearch = useCallback(() => {
    setHistorySearch(null)
    setHistoryCursor(null)
    setHistoryModeFilter(null)
    setHistoryDraft('')
    void commitInput(promptStateRef.current.value)
  }, [commitInput])

  const promptSuggestions = useMemo(
    () => buildPromptSuggestionState(
      inputMode,
      input,
      snapshot,
      workspaceFiles,
      cursorOffset,
      { sessionSuggestions: resumeSuggestions },
    ),
    [cursorOffset, input, inputMode, resumeSuggestions, snapshot, workspaceFiles],
  )
  const promptSuggestionDismissKey = `${inputMode}:${cursorOffset}:${input}`

  useEffect(() => {
    setPromptSuggestionIndex(0)
  }, [promptSuggestions.queryKey])

  useEffect(() => {
    if (dismissedPromptSuggestionsForKey && dismissedPromptSuggestionsForKey !== promptSuggestionDismissKey) {
      dismissedPromptSuggestionsForKeyRef.current = null
      setDismissedPromptSuggestionsForKey(null)
    }
  }, [dismissedPromptSuggestionsForKey, promptSuggestionDismissKey])

  const activePromptSuggestions = useMemo(
    () =>
      dismissedPromptSuggestionsForKey === promptSuggestionDismissKey
        ? {
            ...promptSuggestions,
            kind: null,
            items: [],
            acceptsOnSubmit: false,
            autocompleteValue: null,
            autocompleteCursorOffset: null,
            tabAutocompleteValue: null,
            tabAutocompleteCursorOffset: null,
          }
        : promptSuggestions,
    [dismissedPromptSuggestionsForKey, promptSuggestionDismissKey, promptSuggestions],
  )

  const selectedPromptSuggestion =
    activePromptSuggestions.items[promptSuggestionIndex] ?? activePromptSuggestions.items[0] ?? null

  const promptInlineGhostText = useMemo(() => {
    if (busy || historySearch || overlay || cursorOffset !== input.length) {
      return null
    }
    const completion = activePromptSuggestions.autocompleteValue ?? selectedPromptSuggestion?.replacement ?? null
    if (!completion || completion.length <= input.length || !completion.startsWith(input)) {
      return null
    }
    return completion.slice(input.length)
  }, [
    activePromptSuggestions.autocompleteValue,
    busy,
    cursorOffset,
    historySearch,
    input,
    overlay,
    selectedPromptSuggestion,
  ])

  const acceptPromptSuggestion = useCallback((options: { execute: boolean; preferAutocomplete?: boolean }): boolean => {
    if (busy || (!selectedPromptSuggestion && !activePromptSuggestions.autocompleteValue)) {
      return false
    }

    dismissedPromptSuggestionsForKeyRef.current = null
    setDismissedPromptSuggestionsForKey(null)
    const useAutocomplete = Boolean(
      options.preferAutocomplete
      && activePromptSuggestions.tabAutocompleteValue,
    )
    const replacement = useAutocomplete
      ? activePromptSuggestions.tabAutocompleteValue
      : selectedPromptSuggestion?.replacement ?? activePromptSuggestions.autocompleteValue ?? null
    const replacementCursorOffset = useAutocomplete
      ? activePromptSuggestions.tabAutocompleteCursorOffset ?? replacement?.length ?? 0
      : selectedPromptSuggestion?.cursorOffset ?? activePromptSuggestions.autocompleteCursorOffset ?? replacement?.length ?? 0
    if (!replacement) {
      return false
    }

    if (activePromptSuggestions.kind === 'command') {
      const parsed = parseSlashCommand(replacement)
      const command = parsed ? findCommand(snapshot?.commands ?? [], parsed.name) : undefined
      if (options.execute && command && !command.argumentHint) {
        void commitInput(replacement).catch((error) => {
          addStatus(formatErrorMessage(error))
        })
        return true
      }
    }

    if (activePromptSuggestions.kind === 'command-argument' && options.execute) {
      void commitInput(replacement).catch((error) => {
        addStatus(formatErrorMessage(error))
      })
      return true
    }

    applyPromptState(replacement, replacementCursorOffset)
    return true
  }, [
    activePromptSuggestions.kind,
    activePromptSuggestions.autocompleteCursorOffset,
    activePromptSuggestions.autocompleteValue,
    activePromptSuggestions.tabAutocompleteCursorOffset,
    activePromptSuggestions.tabAutocompleteValue,
    addStatus,
    applyPromptState,
    busy,
    commitInput,
    selectedPromptSuggestion,
    snapshot?.commands,
  ])

  const footerState = useMemo(
    () => buildFooterState(snapshot, historySearch, inputMode),
    [historySearch, inputMode, snapshot],
  )

  const handlePromptPaste = useCallback((pastedText: string) => {
    const currentPromptState = promptStateRef.current
    const transition = applyInputSequence(
      currentPromptState,
      pastedText,
      { pasted: true },
      { multiline: true, columns: editorColumns() },
    )
    const nextPromptState = normalizePromptTransitionState(currentPromptState, transition.state, inputMode)

    if (
      nextPromptState.mode !== inputMode ||
      nextPromptState.value !== currentPromptState.value ||
      nextPromptState.cursorOffset !== currentPromptState.cursorOffset
    ) {
      applyInputMode(nextPromptState.mode)
      applyPromptState(nextPromptState.value, nextPromptState.cursorOffset)
    }
  }, [applyInputMode, applyPromptState, inputMode])

  const handleOverlayTerminalInput = useCallback((inputValue: string, key: InputKey) => {
    if (!overlay) {
      return
    }

    const typedKey = key
    const editorKey = typedKey as EditorKey

    if (typedKey.ctrl && inputValue === 'c') {
      exitApp()
      return
    }

    if (typedKey.ctrl && inputValue === 'z') {
      process.kill(process.pid, 'SIGTSTP')
      return
    }

    void handleOverlayInput({
      inputValue,
      key: typedKey,
      editorKey,
      overlay,
      setOverlay,
      setPermissionQueue,
      markPermissionRequestHandled: (requestId) => {
        handledPermissionRequestIdsRef.current = new Set(handledPermissionRequestIdsRef.current).add(requestId)
      },
      pendingPermissionRequests: permissionQueue,
      rememberPermissionDecision: (request, decision, options) => {
        if (request.interactionType) {
          return
        }
        permissionDecisionCacheRef.current = new Map(permissionDecisionCacheRef.current).set(
          permissionRequestSignature(request),
          { decision, ...options },
        )
        if (decision === 'allow') {
          promptRunPermissionDecisionCacheRef.current = new Map(promptRunPermissionDecisionCacheRef.current).set(
            permissionRequestRunSignature(request),
            { decision, ...options },
          )
        }
      },
      forgetPermissionDecision: (request) => {
        if (request.interactionType) {
          return
        }
        const next = new Map(permissionDecisionCacheRef.current)
        next.delete(permissionRequestSignature(request))
        permissionDecisionCacheRef.current = next
        const nextRunScoped = new Map(promptRunPermissionDecisionCacheRef.current)
        nextRunScoped.delete(permissionRequestRunSignature(request))
        promptRunPermissionDecisionCacheRef.current = nextRunScoped
      },
      addStatus,
      client,
      setSnapshot,
      snapshot,
      config,
      setConfig,
      applyPromptState,
      applyDisplayPromptState,
      setTranscriptBaseline,
      setStartupHomeVisible,
      setHistoryCursor,
      setHistoryDraft,
      refreshSnapshot,
      updateSettings,
      executeCommand,
      memoryFileEditor,
      onSubmitTextEntry: async (overlayState) => {
        const result =
          overlayState.mode === 'provider-auth'
            ? await client.request<MutationResult>('provider.connect', {
                providerId: overlayState.providerId,
                authMethod: overlayState.authMethod,
                apiKeyEnv: overlayState.authMethod === 'api_key' ? overlayState.value : undefined,
                awsProfile: overlayState.authMethod === 'aws_credentials' ? overlayState.value : undefined,
              })
            : await client.request<MutationResult>('sessions.rename', {
                title: overlayState.value,
              })
        setSnapshot(result.snapshot)
        addStatus(result.message)
        setOverlay(null)
      },
    }).catch((error) => {
      addStatus(formatErrorMessage(error))
    })
  }, [
    addStatus,
    applyPromptState,
    client,
    config,
    executeCommand,
    exitApp,
    memoryFileEditor,
    overlay,
    permissionQueue,
    refreshSnapshot,
    snapshot,
    updateSettings,
  ])

  const beforePromptInput = useCallback((inputValue: string, key: InputKey): boolean => {
    const typedKey = key
    const editorKey = key as EditorKey
    const promptSuggestionsDismissed = dismissedPromptSuggestionsForKeyRef.current === promptSuggestionDismissKey

    if (typedKey.ctrl && inputValue === 'c') {
      if (historySearch) {
        cancelHistorySearch()
        return true
      }
      if (busy) {
        void cancelActivePrompt()
        return true
      }
      return false
    }

    if (typedKey.ctrl && inputValue === 'z') {
      process.kill(process.pid, 'SIGTSTP')
      return true
    }

    const currentPromptState = promptStateRef.current
    const action = resolvePromptAction(inputValue, typedKey, {
      busy,
      historySearchActive: Boolean(historySearch),
      inputMode,
      inputValue: historySearch?.query ?? currentPromptState.value,
      cursorOffset: historySearch?.queryCursorOffset ?? currentPromptState.cursorOffset,
      suggestionsVisible: activePromptSuggestions.items.length > 0,
      suggestionsDismissed: promptSuggestionsDismissed,
      inlineGhostTextVisible: Boolean(promptInlineGhostText),
      suggestionSelected: Boolean(selectedPromptSuggestion),
    })

    switch (action) {
      case 'cancelHistorySearch':
        cancelHistorySearch()
        return true
      case 'nextHistorySearchMatch':
        advanceHistorySearch()
        return true
      case 'acceptHistorySearch':
        acceptHistorySearch()
        return true
      case 'executeHistorySearch':
        executeHistorySearch()
        return true
      case 'dismissSuggestions':
        dismissedPromptSuggestionsForKeyRef.current = promptSuggestionDismissKey
        setDismissedPromptSuggestionsForKey(promptSuggestionDismissKey)
        return true
      case 'selectPreviousSuggestion':
        setPromptSuggestionIndex((current) => nextSuggestionIndex(current, activePromptSuggestions.items.length, true))
        return true
      case 'selectNextSuggestion':
        setPromptSuggestionIndex((current) => nextSuggestionIndex(current, activePromptSuggestions.items.length, false))
        return true
      case 'acceptSuggestion':
        return acceptPromptSuggestion({ execute: false, preferAutocomplete: true })
      case 'executeSuggestion':
        return acceptPromptSuggestion({ execute: true })
      case 'openModels':
        openModels()
        return true
      case 'toggleFastMode':
        if (snapshot) {
          void updateSettings({ fastMode: !snapshot.settings.fastMode }).catch((error) => {
            addStatus(formatErrorMessage(error))
          })
        }
        return true
      case 'toggleVerboseOutput':
        if (snapshot) {
          void updateSettings({ verboseOutput: !snapshot.settings.verboseOutput }).catch((error) => {
            addStatus(formatErrorMessage(error))
          })
        }
        return true
      case 'toggleTasks':
        setConfig((current) => ({ ...current, showTasks: !current.showTasks }))
        addStatus(`Background task panel ${config.showTasks ? 'hidden' : 'shown'}.`)
        return true
      case 'toggleStash':
        toggleStash()
        return true
      case 'openExternalEditor':
        void openExternalEditor()
        return true
      case 'startHistorySearch':
        startHistorySearch()
        return true
      case 'suspendApp':
        process.kill(process.pid, 'SIGTSTP')
        return true
      case 'enterBashMode':
        applyInputMode('bash')
        return true
      case 'exitInputMode':
        applyInputMode('prompt')
        return true
      case 'cancelActivePrompt':
        void cancelActivePrompt()
        return true
      default:
        break
    }

    if (historySearch) {
      const transition = applyInputSequence(
        { value: historySearch.query, cursorOffset: historySearch.queryCursorOffset },
        inputValue,
        editorKey,
        { multiline: false, columns: editorColumns() },
      )

      if (
        transition.state.value !== historySearch.query
        || transition.state.cursorOffset !== historySearch.queryCursorOffset
      ) {
        syncHistorySearchSelection({
          ...historySearch,
          query: transition.state.value,
          queryCursorOffset: transition.state.cursorOffset,
          selectedIndex: 0,
        })
      }
      return true
    }

    return false
  }, [
    addStatus,
    busy,
    config.showTasks,
    exitApp,
    inputMode,
    openExternalEditor,
    openModels,
    activePromptSuggestions.items.length,
    promptSuggestionDismissKey,
    promptInlineGhostText,
    selectedPromptSuggestion,
    snapshot,
    historySearch,
    acceptPromptSuggestion,
    acceptHistorySearch,
    advanceHistorySearch,
    cancelHistorySearch,
    cancelActivePrompt,
    executeHistorySearch,
    startHistorySearch,
    syncHistorySearchSelection,
    toggleStash,
    updateSettings,
  ])

  const handlePromptHistoryUp = useCallback(() => {
    const currentPromptState = promptStateRef.current
    const currentInputMode = inputModeRef.current
    const nextModeFilter = historyCursor == null && currentInputMode === 'bash' ? 'bash' : historyModeFilter
    const nextHistory = navigateHistory(promptHistory, historyCursor, 'up', nextModeFilter)
    if (!nextHistory) {
      return
    }
    if (historyCursor == null) {
      setHistoryDraft(prependModeCharacterToInput(currentPromptState.value, currentInputMode))
      setHistoryModeFilter(nextModeFilter)
    }
    setHistoryCursor(nextHistory.index)
    applyDisplayPromptState(nextHistory.value)
  }, [applyDisplayPromptState, historyCursor, historyModeFilter, promptHistory])

  const handlePromptHistoryDown = useCallback(() => {
    if (historyCursor == null) {
      return
    }
    const nextHistory = navigateHistory(promptHistory, historyCursor, 'down', historyModeFilter)
    if (!nextHistory) {
      setHistoryCursor(null)
      setHistoryModeFilter(null)
      applyDisplayPromptState(historyDraft)
      return
    }
    setHistoryCursor(nextHistory.index)
    applyDisplayPromptState(nextHistory.value)
  }, [applyDisplayPromptState, historyCursor, historyDraft, historyModeFilter, promptHistory])

  const handlePromptSubmit = useCallback((value: string) => {
    void commitInput(value).catch((error) => {
      addStatus(formatErrorMessage(error))
    })
  }, [addStatus, commitInput])

  useTerminalInput(handleOverlayTerminalInput, { isActive: overlay != null })

  const visibleStatusLines = useMemo(
    () => statusLines.slice(0, snapshot?.settings.verboseOutput ? MAX_STATUS_LINES_VERBOSE : MAX_STATUS_LINES_COMPACT),
    [snapshot?.settings.verboseOutput, statusLines],
  )

  const contextView = useMemo(() => buildContextView(snapshot), [snapshot])
  const transcriptSnapshot = useMemo(
    () => (snapshot ? { ...snapshot, messages: snapshot.messages.slice(transcriptBaseline) } : null),
    [snapshot, transcriptBaseline],
  )
  const pendingUserMessage = useMemo<RenderableUserMessage | null>(
    () =>
      pendingPrompt
        ? {
            type: 'user',
            id: 'pending-user-turn',
            createdAt: new Date().toISOString(),
            text: pendingPrompt,
          }
        : null,
    [pendingPrompt],
  )
  const liveRenderableMessages = useMemo(
    () =>
      createLiveRenderableMessages({
        liveAssistantText: streamingText,
        liveReasoningText: streamingReasoning,
        liveToolCalls: activeLiveToolCalls,
        fallbackProviderId: snapshot?.state.activeProvider ?? null,
        fallbackModelId: snapshot?.state.activeModelId ?? null,
      }),
    [activeLiveToolCalls, snapshot?.state.activeModelId, snapshot?.state.activeProvider, streamingReasoning, streamingText],
  )
  const hasActiveTurnContent =
    busy
    || pendingPrompt.length > 0
    || liveRenderableMessages.length > 0
    || permissionQueue.length > 0
    || overlay?.kind === 'permission'
    || overlay?.kind === 'ask-user-question'
  const showTranscript = !startupHomeVisible || hasActiveTurnContent
  const showStartupHeader =
    startupHomeVisible
    && !hasActiveTurnContent
    && activePromptSuggestions.items.length === 0
    && !overlay
  const showFallbackThinkingIndicator =
    busy &&
    !streamingText.trim() &&
    !overlay
  const showCommandActivityIndicator =
    !busy &&
    Boolean(commandActivity?.spin) &&
    !overlay
  const thinkingFrame = THINKING_FRAMES[thinkingFrameIndex] ?? THINKING_FRAMES[0]
  const reasoningActive = streamingReasoning.trim().length > 0
  const busyActivityLabel = reasoningActive ? 'Thinking' : 'Working'
  const modalOverlayActive = Boolean(overlay)
  const promptOverlayMessage = overlay
    ? overlay.kind === 'text-entry'
      ? 'Provider auth input is active above.'
      : overlay.kind === 'ask-user-question'
        ? 'Answer the questions above to continue.'
        : 'Overlay active. Press Esc to close it.'
    : null
  const overlayNode = overlay ? (
    <Box marginBottom={1}>
      {renderOverlay({
        overlay,
        snapshot,
        contextView,
        config,
        setOverlay,
        onSubmitTextEntry: async (overlayState) => {
          const result =
            overlayState.mode === 'provider-auth'
              ? await client.request<MutationResult>('provider.connect', {
                  providerId: overlayState.providerId,
                  authMethod: overlayState.authMethod,
                  apiKeyEnv: overlayState.authMethod === 'api_key' ? overlayState.value : undefined,
                  awsProfile: overlayState.authMethod === 'aws_credentials' ? overlayState.value : undefined,
                })
              : await client.request<MutationResult>('sessions.rename', {
                  title: overlayState.value,
                })
          setSnapshot(result.snapshot)
          addStatus(result.message)
          setOverlay(null)
        },
      })}
    </Box>
  ) : null
  const promptNode = modalOverlayActive ? null : (
    <PromptInputPanel
      overlayMessage={promptOverlayMessage}
      historySearch={
        historySearch
          ? {
              query: historySearch.query,
              cursorOffset: historySearch.queryCursorOffset,
              failed: historySearch.failed,
            }
          : null
      }
      promptSuggestions={activePromptSuggestions}
      promptSuggestionIndex={promptSuggestionIndex}
      inputMode={inputMode}
      busy={busy}
      input={input}
      cursorOffset={cursorOffset}
      onChange={applyPromptState}
      onSubmit={handlePromptSubmit}
      onHistoryUp={handlePromptHistoryUp}
      onHistoryDown={handlePromptHistoryDown}
      onClearInput={() => addStatus('Cleared the current input.')}
      onExit={exitApp}
      beforeInput={beforePromptInput}
      onPaste={handlePromptPaste}
      promptInlineGhostText={promptInlineGhostText}
      footerLines={footerState.lines}
      stashedPrompt={Boolean(stashedInput)}
    />
  )
  const statusLineNode = modalOverlayActive ? null : (
    <StatusLine
      snapshot={snapshot}
      inputMode={inputMode}
      busy={busy}
      reasoningActive={reasoningActive}
      commandActivityLabel={commandActivity?.label ?? null}
      interruptRequested={interruptRequested}
      overlayActive={Boolean(overlay)}
      historySearchActive={Boolean(historySearch)}
      showTasks={config.showTasks}
      liveToolCount={activeLiveToolCalls.length}
    />
  )
  const statusFeedNode = modalOverlayActive ? (
    <Box flexDirection="column" marginTop={1}>
      {Array.from({ length: 8 }, (_, index) => (
        <Text key={`overlay-clear-${index}`}>{' '}</Text>
      ))}
    </Box>
  ) : visibleStatusLines.length > 0 ? (
    <Box flexDirection="column" marginTop={1}>
      {visibleStatusLines.map((line, index) => (
        <Text
          key={`${index}-${line.message}`}
          color={line.tone === 'error' ? 'red' : undefined}
          dimColor={line.tone !== 'error'}
        >
          {line.message}
        </Text>
      ))}
    </Box>
  ) : null

  return (
    <ReplShell
      header={!modalOverlayActive && showStartupHeader ? <StartupHeader snapshot={snapshot} /> : null}
      transcript={!modalOverlayActive && showTranscript ? (
        <Transcript
          snapshot={transcriptSnapshot}
          showEmptyState={!hasActiveTurnContent && !pendingUserMessage}
          staticKey={`${snapshot?.session.sessionId ?? 'session'}:${transcriptBaseline}`}
        />
      ) : null}
      pendingTurn={!modalOverlayActive && pendingUserMessage ? (
        <Box marginBottom={1}>
          <Message
            message={pendingUserMessage}
            showReasoning={false}
          />
        </Box>
      ) : null}
      thinking={!modalOverlayActive && showFallbackThinkingIndicator ? (
        <Box marginBottom={1}>
          <Text color={uiTheme.brand}>
            {thinkingFrame} {busyActivityLabel}
          </Text>
        </Box>
      ) : !modalOverlayActive && showCommandActivityIndicator && commandActivity ? (
        <Box marginBottom={1}>
          <Text color={uiTheme.brand}>
            {thinkingFrame} {commandActivity.label}
          </Text>
        </Box>
      ) : null}
      liveMessages={!modalOverlayActive && liveRenderableMessages.length > 0
        ? liveRenderableMessages.map((message) => (
            <Box key={message.id} marginBottom={1}>
              <Message
                message={message}
                showReasoning={Boolean(snapshot?.settings.reasoningVisible || snapshot?.settings.verboseOutput)}
              />
            </Box>
          ))
        : null}
      tasks={!modalOverlayActive && config.showTasks ? (
        <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.promptBorder} paddingX={1} marginBottom={1}>
          <Text bold>{snapshot?.session.planMode ? 'Plan & Tasks' : 'Tasks'}</Text>
          {snapshot?.session.planMode ? <Text color={uiTheme.brand}>Plan mode is active.</Text> : null}
          {snapshot?.session.todos.length ? (
            snapshot.session.todos.map((todo, index) => (
              <Text key={`${todo.content}-${index}`} dimColor={todo.status === 'completed'}>
                {renderTodoStatus(todo.status)} {todo.content}
              </Text>
            ))
          ) : (
            <Text dimColor>No session todos yet.</Text>
          )}
        </Box>
      ) : null}
      overlay={overlayNode}
      prompt={promptNode}
      statusLine={statusLineNode}
      statusFeed={statusFeedNode}
    />
  )
}

function formatErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  const visibleLines = message
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => shouldDisplayStderrLine(line))

  if (visibleLines.length === 0) {
    return 'OpenClaude backend request failed.'
  }

  return visibleLines.join('\n')
}

function createDefaultClient(): OpenClaudeClient {
  return new OpenClaudeStdioClient(backendLaunch.command, backendLaunch.args, backendLaunch.env)
}

function createStatusEntry(message: string, tone: StatusEntry['tone'] = 'default'): StatusEntry {
  return { message, tone }
}

function buildInitialStatusLines(snapshot: BackendSnapshot, visible: boolean): StatusEntry[] {
  if (!visible) {
    return []
  }

  const lines: StatusEntry[] = []
  if ((snapshot.session?.totalMessageCount ?? 0) > 0) {
    lines.unshift(
      createStatusEntry(`Restored session ${snapshot.session.sessionId ?? 'unknown'} with ${snapshot.session.totalMessageCount} messages.`),
    )
  }
  return lines
}

function renderOverlay(args: {
  overlay: Overlay
  snapshot: BackendSnapshot | null
  contextView: ContextView | null
  config: UiConfig
  setOverlay: React.Dispatch<React.SetStateAction<Overlay | null>>
  onSubmitTextEntry: (overlay: Extract<Overlay, { kind: 'text-entry' }>) => Promise<void>
}): React.ReactElement {
  const { overlay, snapshot, contextView, config, setOverlay, onSubmitTextEntry } = args

  if (overlay.kind === 'commands') {
    return (
      <Picker
        title="Commands"
        subtitle="Select a slash command to run or open."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'sessions') {
    return (
      <Picker
        title="Resume"
        subtitle="Resume a previous session from the current workspace."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'rewind') {
    return (
      <Picker
        title="Rewind"
        subtitle="Restore the conversation to just before the selected user turn."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'memory') {
    return (
      <Picker
        title="Memory"
        subtitle="Choose an AGENTS memory file to edit."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'providers') {
    return (
      <Picker
        title="Providers"
        subtitle="Select a provider, then choose how to connect it."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'auth') {
    return (
      <Picker
        title={overlay.provider.displayName}
        subtitle="Choose how OpenClaude should connect this provider."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'permission') {
    return renderPermissionOverlay(overlay)
  }

  if (overlay.kind === 'permissions') {
    return renderPermissionsOverlay(overlay)
  }

  if (overlay.kind === 'ask-user-question') {
    return renderAskUserQuestionOverlay(overlay)
  }

  if (overlay.kind === 'models') {
    return (
      <Picker
        title="Models"
        subtitle="Choose the active model from connected providers."
        options={overlay.options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'config') {
    const options = configOptions(snapshot, config)
    return (
      <Picker
        title="Config"
        subtitle="OpenClaude settings plus session-local TUI toggles."
        options={options}
        selectedIndex={overlay.selectedIndex}
      />
    )
  }

  if (overlay.kind === 'panel') {
    return renderScrollablePanel(overlay.title, overlay.body, overlay.scrollOffset)
  }

  if (overlay.kind === 'context') {
    return renderContextPanel(contextView, overlay.scrollOffset)
  }

  return (
      <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        {overlay.mode === 'provider-auth' ? `Connect ${overlay.providerId}` : 'Rename session'}
      </Text>
      <Text dimColor>{overlay.label}</Text>
      <Box>
        <Text color={uiTheme.promptMarker}>{'> '}</Text>
        <PromptComposer
          value={overlay.value}
          cursorOffset={overlay.cursorOffset}
          placeholder={overlay.placeholder}
          maxVisibleLines={1}
        />
      </Box>
      <Text dimColor>Enter to confirm  Esc to go back</Text>
    </Box>
  )
}

function renderScrollablePanel(title: string, body: string, scrollOffset: number): React.ReactElement {
  const viewport = buildPanelViewport(body, process.stdout.columns, process.stdout.rows, scrollOffset)

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        {title}
      </Text>
      <Text dimColor>
        Lines {viewport.totalLines === 0 ? 0 : viewport.scrollOffset + 1}-{viewport.endOffset} of {viewport.totalLines}
      </Text>
      <Box height={1} />
      {viewport.visibleLines.length === 0
        ? <Text dimColor>(empty)</Text>
        : viewport.visibleLines.map((line, index) => <Text key={`${viewport.scrollOffset}-${index}`}>{line || ' '}</Text>)}
      <Box height={1} />
      <Text dimColor>↑/↓ scroll  PgUp/PgDn jump  Esc close</Text>
    </Box>
  )
}

function renderPermissionOverlay(overlay: Extract<Overlay, { kind: 'permission' }>): React.ReactElement {
  const command = overlay.request.command || overlay.request.reason

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        Allow {overlay.request.toolName}?
      </Text>
      <Box height={1} />
      <Box paddingX={1}>
        <Text color={uiTheme.overlaySelection}>{command}</Text>
      </Box>
      <Box height={1} />
      {overlay.options.map((option, index) => (
        <Box key={option.key}>
          <Text color={index === overlay.selectedIndex ? uiTheme.overlaySelection : undefined}>
            {index === overlay.selectedIndex ? '› ' : '  '}
            <Text bold>{option.label}</Text>
            {option.detail ? (
              <Text dimColor>
                {`: ${option.detail}`}
              </Text>
            ) : null}
          </Text>
        </Box>
      ))}
      <Box height={1} />
      <Text dimColor>↑/↓ move  Enter select  Esc close</Text>
    </Box>
  )
}

function createPermissionOverlay(request: PermissionRequestEvent): Extract<Overlay, { kind: 'permission' }> {
  return {
    kind: 'permission',
    request,
    selectedIndex: 0,
    options: [
      {
        key: 'allow',
        label: 'Allow',
        detail: 'Run this tool call.',
      },
      {
        key: 'deny',
        label: 'Deny',
        detail: 'Block this tool call.',
      },
    ],
  }
}

function createInteractionOverlay(
  request: PermissionRequestEvent,
  planMode: boolean,
): Extract<Overlay, { kind: 'permission' | 'ask-user-question' }> {
  if (request.interactionType === 'ask_user_question') {
    const payload = parseAskUserQuestionPayload(request.interactionJson)
    if (payload) {
      return {
        kind: 'ask-user-question',
        request,
        payload,
        planMode,
        currentQuestionIndex: 0,
        selectedIndex: 0,
        answers: {},
        selectedAnswers: {},
        annotations: {},
        footerFocused: false,
        footerIndex: 0,
        editingOther: false,
        otherText: '',
        otherCursorOffset: 0,
        editingNotes: false,
        notesText: '',
        notesCursorOffset: 0,
        reviewSelectedIndex: 0,
      }
    }
  }
  return createPermissionOverlay(request)
}

function parseAskUserQuestionPayload(raw: string | null | undefined): AskUserQuestionPayload | null {
  if (!raw) {
    return null
  }

  try {
    const parsed = JSON.parse(raw) as Partial<AskUserQuestionPayload>
    const questions = Array.isArray(parsed.questions)
      ? parsed.questions
          .map((question) => ({
            question: typeof question?.question === 'string' ? question.question.trim() : '',
            header: typeof question?.header === 'string' ? question.header.trim() : '',
            multiSelect: Boolean(question?.multiSelect),
            options: Array.isArray(question?.options)
              ? question.options
                  .map((option) => ({
                    label: typeof option?.label === 'string' ? option.label.trim() : '',
                    description: typeof option?.description === 'string' ? option.description.trim() : '',
                    preview: typeof option?.preview === 'string' ? option.preview : undefined,
                  }))
                  .filter((option) => option.label && option.description)
              : [],
          }))
          .filter((question) => question.question && question.header && question.options.length >= 2)
      : []

    if (questions.length === 0) {
      return null
    }

    return {
      questions: questions.slice(0, 4),
    }
  } catch {
    return null
  }
}

function questionHasPreview(question: AskUserQuestion): boolean {
  return !question.multiSelect && question.options.some((option) => Boolean(option.preview?.trim()))
}

function optionLabels(question: AskUserQuestion): Set<string> {
  return new Set(question.options.map((option) => option.label))
}

function selectionsForQuestion(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
  question: AskUserQuestion,
): string[] {
  return overlay.selectedAnswers[question.question] ?? []
}

function otherAnswerForQuestion(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
  question: AskUserQuestion,
): string {
  const labels = optionLabels(question)
  const selections = selectionsForQuestion(overlay, question)
  return selections.find((selection) => !labels.has(selection)) ?? ''
}

function serializeSelections(selections: string[]): string {
  return selections.join(', ')
}

function nextAnnotations(
  current: Record<string, AskUserQuestionAnnotation>,
  questionText: string,
  annotation: AskUserQuestionAnnotation | null,
): Record<string, AskUserQuestionAnnotation> {
  if (!annotation || (!annotation.preview?.trim() && !annotation.notes?.trim())) {
    const { [questionText]: _removed, ...rest } = current
    return rest
  }

  return {
    ...current,
    [questionText]: {
      ...(annotation.preview?.trim() ? { preview: annotation.preview.trim() } : {}),
      ...(annotation.notes?.trim() ? { notes: annotation.notes.trim() } : {}),
    },
  }
}

function buildAskUserQuestionResponsePayload(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
): string {
  let baseInput: Record<string, unknown> = {}
  try {
    baseInput = overlay.request.inputJson ? JSON.parse(overlay.request.inputJson) as Record<string, unknown> : {}
  } catch {
    baseInput = {}
  }
  const annotations = Object.fromEntries(
    Object.entries(overlay.annotations).filter(([, value]) => Boolean(value.preview?.trim() || value.notes?.trim())),
  )

  return JSON.stringify({
    ...baseInput,
    answers: overlay.answers,
    ...(Object.keys(annotations).length > 0 ? { annotations } : {}),
  })
}

function buildAskUserQuestionSummaryLines(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
): string {
  return overlay.payload.questions
    .map((question) => {
      const answer = overlay.answers[question.question]
      if (answer) {
        return `- "${question.question}"\n  Answer: ${answer}`
      }
      return `- "${question.question}"\n  (No answer provided)`
    })
    .join('\n')
}

function buildAskUserQuestionFeedbackPayload(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
  mode: 'respond_to_claude' | 'finish_plan_interview',
): string {
  const questionsWithAnswers = buildAskUserQuestionSummaryLines(overlay)
  const feedback = mode === 'respond_to_claude'
    ? `The user wants to clarify these questions.
This means they may have additional information, context or questions for you.
Take their response into account and then reformulate the questions if appropriate.
Start by asking them what they would like to clarify.

Questions asked:\n${questionsWithAnswers}`
    : `The user has indicated they have provided enough answers for the plan interview.
Stop asking clarifying questions and proceed to finish the plan with the information you have.

Questions asked and answers provided:\n${questionsWithAnswers}`

  return JSON.stringify({ feedback })
}

function renderAskUserQuestionFooter(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
  helpText: string,
): React.ReactElement {
  return (
    <Box flexDirection="column" marginTop={1}>
      <Text dimColor>{'─'.repeat(48)}</Text>
      <Text color={overlay.footerFocused && overlay.footerIndex === 0 ? uiTheme.overlaySelection : undefined}>
        {overlay.footerFocused && overlay.footerIndex === 0 ? '› ' : '  '}
        Chat about this
      </Text>
      {overlay.planMode ? (
        <Text color={overlay.footerFocused && overlay.footerIndex === 1 ? uiTheme.overlaySelection : undefined}>
          {overlay.footerFocused && overlay.footerIndex === 1 ? '› ' : '  '}
          Skip interview and plan immediately
        </Text>
      ) : null}
      <Box height={1} />
      <Text dimColor>{helpText}</Text>
    </Box>
  )
}

function renderAskUserQuestionOverlay(
  overlay: Extract<Overlay, { kind: 'ask-user-question' }>,
): React.ReactElement {
  const inReview = overlay.currentQuestionIndex >= overlay.payload.questions.length

  if (inReview) {
    const options = ['Submit answers', 'Cancel']
    return (
      <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
        <Text bold color={uiTheme.overlayTitle}>
          Review your answers
        </Text>
        <Box height={1} />
        {overlay.payload.questions.map((question) => (
          <Box key={question.question} flexDirection="column" marginBottom={1}>
            <Text>{question.question}</Text>
            <Box marginLeft={2}>
              <Text color={uiTheme.brand}>{overlay.answers[question.question] ?? '(no answer)'}</Text>
            </Box>
            {overlay.annotations[question.question]?.preview ? (
              <Box marginLeft={2} marginTop={1}>
                <Text dimColor>Selected preview available</Text>
              </Box>
            ) : null}
            {overlay.annotations[question.question]?.notes ? (
              <Box marginLeft={2}>
                <Text dimColor>Notes: {overlay.annotations[question.question]?.notes}</Text>
              </Box>
            ) : null}
          </Box>
        ))}
        <Box height={1} />
        {options.map((option, index) => (
          <Text
            key={option}
            color={index === overlay.reviewSelectedIndex ? uiTheme.overlaySelection : undefined}
          >
            {index === overlay.reviewSelectedIndex ? '› ' : '  '}
            {option}
          </Text>
        ))}
        <Box height={1} />
        <Text dimColor>↑/↓ move  Enter select  Esc cancel</Text>
      </Box>
    )
  }

  const question = overlay.payload.questions[overlay.currentQuestionIndex]
  const previewMode = questionHasPreview(question)
  const savedAnswer = overlay.answers[question.question] ?? ''
  const selectedValues = selectionsForQuestion(overlay, question)
  const currentOtherAnswer = otherAnswerForQuestion(overlay, question)

  if (previewMode) {
    const focusedOption = question.options[overlay.selectedIndex] ?? question.options[0]
    const selectedOption = question.options.find((option) => option.label === savedAnswer)
    const previewText = focusedOption?.preview?.trim() || 'No preview available.'
    const selectedPreview = selectedOption?.preview?.trim()
    const savedNotes = overlay.annotations[question.question]?.notes ?? ''

    return (
      <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
        <Text bold color={uiTheme.overlayTitle}>
          {question.header}
        </Text>
        <Text>{question.question}</Text>
        <Text dimColor>
          Question {overlay.currentQuestionIndex + 1} of {overlay.payload.questions.length}
        </Text>
        <Box marginTop={1} flexDirection="row">
          <Box flexDirection="column" width={34} marginRight={2}>
            {question.options.map((option, index) => (
              <Box key={`${question.question}-${option.label}`} flexDirection="column" marginBottom={1}>
                <Text color={index === overlay.selectedIndex ? uiTheme.overlaySelection : undefined}>
                  {index === overlay.selectedIndex ? '› ' : '  '}
                  {savedAnswer === option.label ? '◉ ' : '○ '}
                  {option.label}
                </Text>
                <Box marginLeft={4}>
                  <Text dimColor>{option.description}</Text>
                </Box>
              </Box>
            ))}
          </Box>
          <Box flexGrow={1} flexDirection="column">
            <Text color={uiTheme.brandMuted} dimColor>
              Preview
            </Text>
            <Box borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1} paddingY={0} minHeight={8}>
              <Markdown text={previewText} />
            </Box>
            <Box marginTop={1} flexDirection="column">
              <Text color={uiTheme.brandMuted} dimColor>
                Notes
              </Text>
              <Box borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
                {overlay.editingNotes ? (
                  <PromptComposer
                    value={overlay.notesText}
                    cursorOffset={overlay.notesCursorOffset}
                    placeholder="Add notes on this design…"
                    maxVisibleLines={3}
                  />
                ) : (
                  <Text dimColor={!savedNotes}>
                    {savedNotes || 'press n to add notes'}
                  </Text>
                )}
              </Box>
            </Box>
            {selectedPreview ? (
              <Box marginTop={1}>
                <Text dimColor>
                  {overlay.editingNotes ? 'Enter save notes  Esc cancel' : 'n notes  Enter select'}
                </Text>
              </Box>
            ) : null}
          </Box>
        </Box>
        {renderAskUserQuestionFooter(
          overlay,
          overlay.editingNotes
            ? 'Enter save notes  Esc cancel'
            : 'Enter select · ↑/↓ navigate · n notes · Esc cancel',
        )}
      </Box>
    )
  }

  const options = question.multiSelect
    ? [
        ...question.options,
        { label: 'Other', description: currentOtherAnswer || 'Type a custom answer.' },
        { label: 'Continue', description: 'Move to the next question.' },
      ]
    : [...question.options, { label: 'Other', description: 'Type a custom answer.' }]

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        {question.header}
      </Text>
      <Text>{question.question}</Text>
      <Text dimColor>
        Question {overlay.currentQuestionIndex + 1} of {overlay.payload.questions.length}
      </Text>
      <Box height={1} />
      {options.map((option, index) => {
        const isOther = question.multiSelect ? index === question.options.length : index === options.length - 1
        const isContinue = question.multiSelect && index === options.length - 1
        const selected = isOther
          ? currentOtherAnswer.length > 0
          : isContinue
            ? false
            : question.multiSelect
              ? selectedValues.includes(option.label)
              : savedAnswer === option.label
        return (
          <Box key={`${question.question}-${option.label}`} flexDirection="column">
            <Text color={index === overlay.selectedIndex ? uiTheme.overlaySelection : undefined}>
              {index === overlay.selectedIndex ? '› ' : '  '}
              {isContinue ? '→ ' : question.multiSelect ? (selected ? '☑ ' : '☐ ') : (selected ? '◉ ' : '○ ')}
              {option.label}
            </Text>
            <Box marginLeft={4}>
              <Text dimColor>{option.description}</Text>
            </Box>
          </Box>
        )
      })}
      {overlay.editingOther ? (
        <>
          <Box height={1} />
          <Text dimColor>Other answer</Text>
          <Box>
            <Text color={uiTheme.promptMarker}>{'> '}</Text>
            <PromptComposer value={overlay.otherText} cursorOffset={overlay.otherCursorOffset} maxVisibleLines={3} />
          </Box>
        </>
      ) : null}
      {renderAskUserQuestionFooter(
        overlay,
        overlay.editingOther
          ? 'Enter save  Esc cancel'
          : question.multiSelect
            ? 'Enter toggle/select · ↑/↓ navigate · Esc cancel'
            : 'Enter select · ↑/↓ navigate · Esc cancel',
      )}
    </Box>
  )
}

function renderContextPanel(contextView: ContextView | null, scrollOffset: number): React.ReactElement {
  if (!contextView) {
    return renderScrollablePanel('Context', 'No active context is available yet.', scrollOffset)
  }

  const pageSize = Math.max(8, (process.stdout.rows ?? 24) - 18)
  const visibleDetailLines = contextView.detailLines.slice(scrollOffset, scrollOffset + pageSize)

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        Context
      </Text>
      {contextView.summaryLines.map((line) => (
        <Text key={line} dimColor>
          {line}
        </Text>
      ))}
      <Box height={1} />
      <Box>
        {contextView.cells.map((cell, index) => (
          <Text key={`cell-${index}`} color={cell.color}>
            ■
          </Text>
        ))}
      </Box>
      <Text dimColor>Grid shows relative contribution of visible messages in the current session.</Text>
      <Box height={1} />
      {visibleDetailLines.map((line, index) => (
        <Text key={`${scrollOffset}-${index}`}>{line}</Text>
      ))}
      <Box height={1} />
      <Text dimColor>↑/↓ scroll  Esc close</Text>
    </Box>
  )
}

function handleEvent(
  event: OpenClaudeEvent,
  setStreamingText: React.Dispatch<React.SetStateAction<string>>,
  setStreamingReasoning: React.Dispatch<React.SetStateAction<string>>,
  setLiveToolCalls: React.Dispatch<React.SetStateAction<LiveToolState[]>>,
  addStatus: (message: string) => void,
  onPermissionRequested: (request: PermissionRequestEvent) => void,
): void {
  if (event.event === 'prompt.delta') {
    setStreamingText((current) => current + event.data.text)
    return
  }
  if (event.event === 'prompt.reasoning.delta') {
    const data = event.data as PromptReasoningDeltaEvent
    setStreamingReasoning((current) => current + data.text)
    return
  }
  if (
    event.event === 'prompt.tool.started' ||
    event.event === 'prompt.tool.delta' ||
    event.event === 'prompt.tool.completed' ||
    event.event === 'prompt.tool.failed'
  ) {
    const data = event.data as PromptToolEvent
    setLiveToolCalls((current) => upsertToolCall(current, data))
    return
  }
  if (event.event === 'permission.requested') {
    const data = event.data as PermissionRequestEvent
    setLiveToolCalls((current) =>
      upsertToolCall(current, {
        toolId: data.toolId,
        toolName: data.toolName,
        phase: 'status',
        command: data.command,
        text:
          data.interactionType === 'ask_user_question'
            ? 'Awaiting user answers.'
            : data.command
              ? `Awaiting approval for ${data.command}`
              : data.reason,
      }),
    )
    onPermissionRequested(data)
    return
  }
  if (event.event === 'prompt.status') {
    if (shouldDisplayStatus(event.data.message)) {
      addStatus(event.data.message)
    }
    return
  }
  if (event.event === 'provider.connect.status') {
    addStatus(event.data.message)
  }
}

function shouldDisplayStatus(message: string): boolean {
  const normalized = message.trim().toLowerCase()
  if (!normalized) {
    return false
  }
  if (normalized.startsWith('model response ')) {
    return false
  }
  return normalized != 'permission decision recorded.'
}

function shouldDisplayStderrLine(line: string): boolean {
  const normalized = line.trim()
  if (!normalized) {
    return false
  }
  if (normalized.includes('StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION')) {
    return false
  }
  if (normalized.includes('end-of-input')) {
    return false
  }
  if (normalized.includes('No content to map')) {
    return false
  }
  if (normalized.startsWith('at [Source:') && normalized.includes('end-of-input')) {
    return false
  }
  if (normalized.startsWith('Invalid stdio request:') && normalized.includes('end-of-input')) {
    return false
  }
  return true
}

function permissionRequestSignature(request: PermissionRequestEvent): string {
  return [
    normalizePermissionField(request.toolName).toLowerCase(),
    normalizePermissionField(request.interactionType).toLowerCase(),
    normalizePermissionField(request.command),
    normalizePermissionJson(request.inputJson),
    !request.command && !request.inputJson ? normalizePermissionField(request.reason) : '',
  ].join('\u0000')
}

function permissionRequestRunSignature(request: PermissionRequestEvent): string {
  return [
    normalizePermissionField(request.toolName).toLowerCase(),
    normalizePermissionField(request.interactionType).toLowerCase(),
  ].join('\u0000')
}

function permissionsOverlayRuleRows(
  snapshot: PermissionEditorSnapshotView,
  tab: PermissionsOverlayState['selectedTab'],
  query: string,
) {
  if (tab === 'recent' || tab === 'workspace') {
    return []
  }
  return getPermissionsTabRows(snapshot, tab, query)
}

function permissionsOverlayRowCount(
  snapshot: PermissionEditorSnapshotView,
  tab: PermissionsOverlayState['selectedTab'],
  query: string,
): number {
  if (tab === 'recent') {
    return getPermissionsRecentActivity(snapshot).length
  }
  if (tab === 'workspace') {
    return 0
  }
  return getPermissionsTabRows(snapshot, tab, query).length
}

function normalizePermissionsOverlayState(state: PermissionsOverlayState): PermissionsOverlayState {
  const query = state.mode.type === 'search' ? state.mode.query : ''
  return {
    ...state,
    selectedIndexByTab: {
      ...state.selectedIndexByTab,
      [state.selectedTab]: clampPermissionIndex(
        state.selectedIndexByTab[state.selectedTab] ?? 0,
        permissionsOverlayRowCount(state.snapshot, state.selectedTab, query),
      ),
    },
  }
}

function isPrintableOverlayInput(inputValue: string, key: InputKey): boolean {
  if (!inputValue) {
    return false
  }
  if (
    key.ctrl
    || key.meta
    || key.escape
    || key.tab
    || key.return
    || key.backspace
    || key.delete
    || key.upArrow
    || key.downArrow
    || key.leftArrow
    || key.rightArrow
    || key.pageUp
    || key.pageDown
  ) {
    return false
  }
  if (inputValue.startsWith('\u001B')) {
    return false
  }
  return !/[\r\n\t]/.test(inputValue)
}

function normalizePermissionField(value: string | null | undefined): string {
  return (value ?? '').replace(/\s+/g, ' ').trim()
}

function normalizePermissionJson(value: string | null | undefined): string {
  const trimmed = (value ?? '').trim()
  if (!trimmed) {
    return ''
  }

  try {
    return JSON.stringify(sortJsonKeys(JSON.parse(trimmed)))
  } catch {
    return normalizePermissionField(trimmed)
  }
}

function sortJsonKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortJsonKeys)
  }
  if (value && typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>)
      .sort()
      .reduce<Record<string, unknown>>((sorted, key) => {
        sorted[key] = sortJsonKeys((value as Record<string, unknown>)[key])
        return sorted
      }, {})
  }
  return value
}

async function handleOverlayInput(args: {
  inputValue: string
  key: InputKey
  editorKey: EditorKey
  overlay: Overlay
  setOverlay: React.Dispatch<React.SetStateAction<Overlay | null>>
  setPermissionQueue: React.Dispatch<React.SetStateAction<PermissionRequestEvent[]>>
  markPermissionRequestHandled: (requestId: string) => void
  pendingPermissionRequests: PermissionRequestEvent[]
  rememberPermissionDecision: (
    request: PermissionRequestEvent,
    decision: 'allow' | 'deny',
    options?: {
      payloadJson?: string
      updatedInputJson?: string
      userModified?: boolean
      decisionReason?: string
      interrupt?: boolean
    },
  ) => void
  forgetPermissionDecision: (request: PermissionRequestEvent) => void
  addStatus: (message: string) => void
  client: OpenClaudeClient
  setSnapshot: React.Dispatch<React.SetStateAction<BackendSnapshot | null>>
  snapshot: BackendSnapshot | null
  config: UiConfig
  setConfig: React.Dispatch<React.SetStateAction<UiConfig>>
  applyPromptState: (value: string, nextCursorOffset: number) => void
  applyDisplayPromptState: (displayValue: string, nextCursorOffset?: number) => void
  setTranscriptBaseline: React.Dispatch<React.SetStateAction<number>>
  setStartupHomeVisible: React.Dispatch<React.SetStateAction<boolean>>
  setHistoryCursor: React.Dispatch<React.SetStateAction<number | null>>
  setHistoryDraft: React.Dispatch<React.SetStateAction<string>>
  refreshSnapshot: () => Promise<void>
  updateSettings: (patch: {
    fastMode?: boolean
    verboseOutput?: boolean
    reasoningVisible?: boolean
    alwaysCopyFullResponse?: boolean
    effortLevel?: string | null
  }) => Promise<void>
  executeCommand: (command: CommandView, args?: string) => Promise<void>
  memoryFileEditor: (filePath: string) => Promise<void>
  onSubmitTextEntry: (overlay: Extract<Overlay, { kind: 'text-entry' }>) => Promise<void>
}): Promise<void> {
  const { inputValue, key, editorKey, overlay, setOverlay, setPermissionQueue, markPermissionRequestHandled, pendingPermissionRequests, rememberPermissionDecision, forgetPermissionDecision, addStatus, client, setSnapshot, snapshot, config, setConfig, applyPromptState, applyDisplayPromptState, setTranscriptBaseline, setStartupHomeVisible, setHistoryCursor, setHistoryDraft, refreshSnapshot, updateSettings, executeCommand, memoryFileEditor, onSubmitTextEntry } = args
  const rawEnter = inputValue === '\r' || inputValue === '\n'
  const rawUp = inputValue === '\u001B[A'
  const rawDown = inputValue === '\u001B[B'
  const rawLeft = inputValue === '\u001B[D'
  const rawRight = inputValue === '\u001B[C'
  const rawTab = inputValue === '\t'

  const clearInteractionOverlay = (requestIds: Set<string>) => {
    setPermissionQueue((current) => current.filter((request) => !requestIds.has(request.requestId)))
    setOverlay((current) => (current?.kind === 'permission' || current?.kind === 'ask-user-question' ? null : current))
  }

  const restoreInteractionOverlay = (requests: PermissionRequestEvent[]) => {
    setPermissionQueue((current) =>
      requests.reduceRight((next, request) => (
        next.some((queued) => queued.requestId === request.requestId)
          ? next
          : [request, ...next]
      ), current),
    )
  }

  const respondToPermissionRequest = async (
    request: PermissionRequestEvent,
    decision: 'allow' | 'deny',
    options?: {
      payloadJson?: string
      updatedInputJson?: string
      userModified?: boolean
      decisionReason?: string
      interrupt?: boolean
    },
  ) => {
    const exactSignature = permissionRequestSignature(request)
    const runScopedSignature = permissionRequestRunSignature(request)
    const matchingRequests = request.interactionType
      ? [request]
      : [request, ...pendingPermissionRequests.filter((queued) =>
          queued.requestId !== request.requestId
            && !queued.interactionType
            && (
              permissionRequestSignature(queued) === exactSignature
              || (decision === 'allow' && permissionRequestRunSignature(queued) === runScopedSignature)
            ))]
    const matchingRequestIds = new Set(matchingRequests.map((queued) => queued.requestId))

    for (const matchingRequest of matchingRequests) {
      markPermissionRequestHandled(matchingRequest.requestId)
      rememberPermissionDecision(matchingRequest, decision, options)
    }
    clearInteractionOverlay(matchingRequestIds)
    try {
      for (const matchingRequest of matchingRequests) {
        const result = await client.request<MutationResult>('permission.respond', {
          requestId: matchingRequest.requestId,
          decision,
          ...(options?.payloadJson ? { payloadJson: options.payloadJson } : {}),
          ...(options?.updatedInputJson ? { updatedInputJson: options.updatedInputJson } : {}),
          ...(options?.userModified ? { userModified: options.userModified } : {}),
          ...(options?.decisionReason ? { decisionReason: options.decisionReason } : {}),
          ...(options?.interrupt ? { interrupt: options.interrupt } : {}),
        })
        setSnapshot(result.snapshot)
        if (shouldDisplayStatus(result.message)) {
          addStatus(result.message)
        }
      }
    } catch (error) {
      for (const matchingRequest of matchingRequests) {
        forgetPermissionDecision(matchingRequest)
      }
      restoreInteractionOverlay(matchingRequests)
      addStatus(formatErrorMessage(error))
    }
  }

  if (key.escape) {
    if (overlay.kind === 'ask-user-question' && overlay.editingOther) {
      setOverlay({ ...overlay, editingOther: false, otherText: '', otherCursorOffset: 0 })
      return
    }
    if (overlay.kind === 'ask-user-question' && overlay.editingNotes) {
      setOverlay({ ...overlay, editingNotes: false, notesText: '', notesCursorOffset: 0 })
      return
    }
    if (overlay.kind === 'ask-user-question' && overlay.footerFocused) {
      const question = overlay.payload.questions[overlay.currentQuestionIndex]
      const maxIndex = question
        ? questionHasPreview(question)
          ? question.options.length - 1
          : question.multiSelect
            ? question.options.length + 1
            : question.options.length
        : 0
      setOverlay({ ...overlay, footerFocused: false, footerIndex: 0, selectedIndex: maxIndex })
      return
    }
    if (overlay.kind === 'permission') {
      await respondToPermissionRequest(overlay.request, 'deny', {
        interrupt: true,
        decisionReason: 'Permission request interrupted from OpenClaude UI.',
      })
      return
    }
    if (overlay.kind === 'permissions') {
      if (overlay.mode.type === 'search') {
        setOverlay(normalizePermissionsOverlayState({
          ...overlay,
          mode: { type: 'browse' },
        }))
        return
      }
      if (overlay.mode.type === 'add-rule-source') {
        setOverlay({
          ...overlay,
          mode: {
            type: 'add-rule-input',
            behavior: overlay.mode.behavior,
            value: overlay.mode.ruleString,
            cursorOffset: overlay.mode.ruleString.length,
          },
        })
        return
      }
      if (overlay.mode.type === 'add-rule-input' || overlay.mode.type === 'rule-details') {
        setOverlay(normalizePermissionsOverlayState({
          ...overlay,
          mode: { type: 'browse' },
        }))
        return
      }
      setOverlay(null)
      return
    }
    if (overlay.kind === 'ask-user-question') {
      await respondToPermissionRequest(overlay.request, 'deny', {
        interrupt: true,
        decisionReason: 'Question flow interrupted from OpenClaude UI.',
      })
      return
    }
    setOverlay(null)
    return
  }

  if (overlay.kind === 'text-entry') {
    const transition = applyInputSequence(
      { value: overlay.value, cursorOffset: overlay.cursorOffset },
      inputValue,
      editorKey,
      { multiline: false, columns: editorColumns() },
    )
    const nextOverlay = { ...overlay, value: transition.state.value, cursorOffset: transition.state.cursorOffset }
    setOverlay(nextOverlay)
    if (transition.action === 'submit' && nextOverlay.value.trim()) {
      await onSubmitTextEntry(nextOverlay)
    }
    return
  }

  if (overlay.kind === 'permissions') {
    const query = overlay.mode.type === 'search' ? overlay.mode.query : ''
    const recentRows = getPermissionsRecentActivity(overlay.snapshot)
    const ruleRows = permissionsOverlayRuleRows(overlay.snapshot, overlay.selectedTab, query)
    const rowCount = permissionsOverlayRowCount(overlay.snapshot, overlay.selectedTab, query)
    const selectedIndex = clampPermissionIndex(overlay.selectedIndexByTab[overlay.selectedTab] ?? 0, rowCount)
    const updatePermissionsOverlay = (
      updater: (current: PermissionsOverlayState) => PermissionsOverlayState,
    ) => {
      setOverlay((current) => (
        current?.kind === 'permissions'
          ? normalizePermissionsOverlayState(updater(current))
          : current
      ))
    }

    const mutatePermissionsEditor = async (
      params: {
        action: 'add' | 'remove' | 'clear' | 'retry-denials'
        source?: string | null
        behavior?: PermissionRuleBehavior | null
        rule?: string | null
      },
      after: (snapshot: PermissionEditorSnapshotView) => PermissionsOverlayState | null,
    ) => {
      const result = await client.request<PermissionEditorMutationResult>('permissions.editor.mutate', params)
      addStatus(result.message)
      setOverlay(after(result.snapshot))
    }

    if (overlay.mode.type === 'add-rule-source') {
      const sources = getPermissionsEditableSources(overlay.snapshot, overlay.mode.behavior)
      const sourceCount = sources.length
      if (key.upArrow || rawUp) {
        setOverlay({
          ...overlay,
          mode: {
            ...overlay.mode,
            selectedIndex: clampPermissionIndex(overlay.mode.selectedIndex - 1, sourceCount),
          },
        })
        return
      }
      if (key.downArrow || rawDown) {
        setOverlay({
          ...overlay,
          mode: {
            ...overlay.mode,
            selectedIndex: clampPermissionIndex(overlay.mode.selectedIndex + 1, sourceCount),
          },
        })
        return
      }
      if (key.return || rawEnter) {
        const source = sources[overlay.mode.selectedIndex]
        if (!source) {
          addStatus('No editable permission source is available for this rule.')
          setOverlay(normalizePermissionsOverlayState({
            ...overlay,
            mode: { type: 'browse' },
          }))
          return
        }
        const behavior = overlay.mode.behavior
        const ruleString = overlay.mode.ruleString.trim()
        await mutatePermissionsEditor(
          {
            action: 'add',
            source: source.source,
            behavior,
            rule: ruleString,
          },
          (nextSnapshot) => {
            const nextState = normalizePermissionsOverlayState({
              ...overlay,
              snapshot: nextSnapshot,
              selectedTab: behavior,
              mode: { type: 'browse' },
            })
            const nextRows = getPermissionsTabRows(nextSnapshot, behavior, '')
            const nextIndex = nextRows.findIndex((row) =>
              row.kind === 'rule'
              && row.rule.source === source.source
              && row.rule.behavior === behavior
              && row.rule.ruleString === ruleString,
            )
            return normalizePermissionsOverlayState({
              ...nextState,
              selectedIndexByTab: {
                ...nextState.selectedIndexByTab,
                [behavior]: nextIndex >= 0 ? nextIndex : nextState.selectedIndexByTab[behavior],
              },
            })
          },
        )
        return
      }
      return
    }

    if (overlay.mode.type === 'rule-details') {
      const optionCount = overlay.mode.rule.editable ? 2 : 0
      if (overlay.mode.rule.editable && (key.upArrow || rawUp)) {
        setOverlay({
          ...overlay,
          mode: {
            ...overlay.mode,
            selectedIndex: clampPermissionIndex(overlay.mode.selectedIndex - 1, optionCount),
          },
        })
        return
      }
      if (overlay.mode.rule.editable && (key.downArrow || rawDown)) {
        setOverlay({
          ...overlay,
          mode: {
            ...overlay.mode,
            selectedIndex: clampPermissionIndex(overlay.mode.selectedIndex + 1, optionCount),
          },
        })
        return
      }
      if (key.return || rawEnter) {
        if (!overlay.mode.rule.editable || overlay.mode.selectedIndex === 1) {
          setOverlay(normalizePermissionsOverlayState({
            ...overlay,
            mode: { type: 'browse' },
          }))
          return
        }
        await mutatePermissionsEditor(
          {
            action: 'remove',
            source: overlay.mode.rule.source,
            behavior: overlay.mode.rule.behavior as PermissionRuleBehavior,
            rule: overlay.mode.rule.ruleString,
          },
          (nextSnapshot) => normalizePermissionsOverlayState({
            ...overlay,
            snapshot: nextSnapshot,
            mode: { type: 'browse' },
          }),
        )
        return
      }
      return
    }

    if (overlay.mode.type === 'add-rule-input') {
      const transition = applyInputSequence(
        { value: overlay.mode.value, cursorOffset: overlay.mode.cursorOffset },
        inputValue,
        editorKey,
        { multiline: false, columns: editorColumns() },
      )
      const nextValue = transition.state.value
      const nextCursorOffset = transition.state.cursorOffset
      setOverlay({
        ...overlay,
        mode: {
          ...overlay.mode,
          value: nextValue,
          cursorOffset: nextCursorOffset,
        },
      })
      if (transition.action === 'submit') {
        const ruleString = nextValue.trim()
        if (!ruleString) {
          return
        }
        const sources = getPermissionsEditableSources(overlay.snapshot, overlay.mode.behavior)
        if (sources.length === 0) {
          addStatus('No editable permission source is available for this rule.')
          setOverlay(normalizePermissionsOverlayState({
            ...overlay,
            mode: { type: 'browse' },
          }))
          return
        }
        setOverlay({
          ...overlay,
          mode: {
            type: 'add-rule-source',
            behavior: overlay.mode.behavior,
            ruleString,
            selectedIndex: 0,
          },
        })
      }
      return
    }

    if (key.leftArrow || rawLeft || (key.shift && key.tab)) {
      updatePermissionsOverlay((current) => ({
        ...current,
        selectedTab: getPermissionsNextTab(current.selectedTab, -1),
        mode: { type: 'browse' },
      }))
      return
    }

    if (key.rightArrow || rawRight || key.tab || rawTab) {
      updatePermissionsOverlay((current) => ({
        ...current,
        selectedTab: getPermissionsNextTab(current.selectedTab, 1),
        mode: { type: 'browse' },
      }))
      return
    }

    if (key.upArrow || rawUp || key.pageUp) {
      updatePermissionsOverlay((current) => ({
        ...current,
        selectedIndexByTab: {
          ...current.selectedIndexByTab,
          [current.selectedTab]: clampPermissionIndex(
            (current.selectedIndexByTab[current.selectedTab] ?? 0) - 1,
            permissionsOverlayRowCount(
              current.snapshot,
              current.selectedTab,
              current.mode.type === 'search' ? current.mode.query : '',
            ),
          ),
        },
      }))
      return
    }

    if (key.downArrow || rawDown || key.pageDown) {
      updatePermissionsOverlay((current) => ({
        ...current,
        selectedIndexByTab: {
          ...current.selectedIndexByTab,
          [current.selectedTab]: clampPermissionIndex(
            (current.selectedIndexByTab[current.selectedTab] ?? 0) + 1,
            permissionsOverlayRowCount(
              current.snapshot,
              current.selectedTab,
              current.mode.type === 'search' ? current.mode.query : '',
            ),
          ),
        },
      }))
      return
    }

    if ((overlay.selectedTab === 'allow' || overlay.selectedTab === 'ask' || overlay.selectedTab === 'deny') && isPrintableOverlayInput(inputValue, key)) {
      const initialQuery = inputValue === '/' ? '' : inputValue
      setOverlay(normalizePermissionsOverlayState({
        ...overlay,
        mode: {
          type: 'search',
          query: initialQuery,
          cursorOffset: initialQuery.length,
        },
        selectedIndexByTab: {
          ...overlay.selectedIndexByTab,
          [overlay.selectedTab]: 0,
        },
      }))
      return
    }

    if (overlay.mode.type === 'search') {
      const transition = applyInputSequence(
        { value: overlay.mode.query, cursorOffset: overlay.mode.cursorOffset },
        inputValue,
        editorKey,
        { multiline: false, columns: editorColumns() },
      )
      setOverlay(normalizePermissionsOverlayState({
        ...overlay,
        mode: {
          type: 'search',
          query: transition.state.value,
          cursorOffset: transition.state.cursorOffset,
        },
        selectedIndexByTab: {
          ...overlay.selectedIndexByTab,
          [overlay.selectedTab]: 0,
        },
      }))
      if (transition.action !== 'submit') {
        return
      }
    }

    if (overlay.selectedTab === 'recent') {
      if (inputValue === 'r' || key.return || rawEnter) {
        if (!recentRows.some((row) => row.status.toLowerCase() === 'denied')) {
          addStatus('No denied permission requests were found in the recent activity tab.')
          return
        }
        await mutatePermissionsEditor(
          { action: 'retry-denials' },
          () => null,
        )
        return
      }
      return
    }

    if (overlay.selectedTab === 'workspace') {
      return
    }

    if (key.return || rawEnter) {
      const row = ruleRows[selectedIndex]
      if (!row) {
        return
      }
      if (row.kind === 'add') {
        setOverlay({
          ...overlay,
          mode: {
            type: 'add-rule-input',
            behavior: overlay.selectedTab,
            value: '',
            cursorOffset: 0,
          },
        })
        return
      }
      setOverlay({
        ...overlay,
        mode: {
          type: 'rule-details',
          rule: row.rule,
          selectedIndex: 0,
        },
      })
      return
    }

    return
  }

  if (overlay.kind === 'panel' || overlay.kind === 'context') {
    const nextOffset = scrollOffsetForOverlay(overlay, key, inputValue)
    if (nextOffset != null) {
      setOverlay({ ...overlay, scrollOffset: nextOffset })
    }
    return
  }

  if (overlay.kind === 'ask-user-question') {
    if (overlay.editingOther) {
      const transition = applyInputSequence(
        { value: overlay.otherText, cursorOffset: overlay.otherCursorOffset },
        inputValue,
        editorKey,
        { multiline: false, columns: editorColumns() },
      )
      const nextOverlay = {
        ...overlay,
        otherText: transition.state.value,
        otherCursorOffset: transition.state.cursorOffset,
      }
      setOverlay(nextOverlay)
      if (transition.action === 'submit' && nextOverlay.otherText.trim()) {
        const question = nextOverlay.payload.questions[nextOverlay.currentQuestionIndex]
        const labels = optionLabels(question)
        const nextSelections = question.multiSelect
          ? [
              ...selectionsForQuestion(nextOverlay, question).filter((selection) => labels.has(selection)),
              nextOverlay.otherText.trim(),
            ]
          : [nextOverlay.otherText.trim()]
        setOverlay({
          ...nextOverlay,
          selectedAnswers: {
            ...nextOverlay.selectedAnswers,
            [question.question]: nextSelections,
          },
          answers: {
            ...nextOverlay.answers,
            [question.question]: question.multiSelect
              ? serializeSelections(nextSelections)
              : nextOverlay.otherText.trim(),
          },
          currentQuestionIndex: question.multiSelect
            ? nextOverlay.currentQuestionIndex
            : nextOverlay.currentQuestionIndex + 1,
          selectedIndex: question.multiSelect ? question.options.length + 1 : 0,
          footerFocused: false,
          footerIndex: 0,
          editingOther: false,
          otherText: '',
          otherCursorOffset: 0,
        })
      }
      return
    }

    if (overlay.editingNotes) {
      if (key.escape) {
        setOverlay({
          ...overlay,
          editingNotes: false,
          notesText: '',
          notesCursorOffset: 0,
        })
        return
      }

      const transition = applyInputSequence(
        { value: overlay.notesText, cursorOffset: overlay.notesCursorOffset },
        inputValue,
        editorKey,
        { multiline: false, columns: editorColumns() },
      )
      const nextOverlay = {
        ...overlay,
        notesText: transition.state.value,
        notesCursorOffset: transition.state.cursorOffset,
      }
      setOverlay(nextOverlay)
      if (transition.action === 'submit') {
        const question = nextOverlay.payload.questions[nextOverlay.currentQuestionIndex]
        const focusedOption = question.options[nextOverlay.selectedIndex] ?? question.options[0]
        setOverlay({
          ...nextOverlay,
          annotations: nextAnnotations(
            nextOverlay.annotations,
            question.question,
            {
              ...(focusedOption?.preview ? { preview: focusedOption.preview } : {}),
              ...(nextOverlay.notesText.trim() ? { notes: nextOverlay.notesText } : {}),
            },
          ),
          editingNotes: false,
          notesText: '',
          notesCursorOffset: 0,
        })
      }
      return
    }

    if (overlay.currentQuestionIndex >= overlay.payload.questions.length) {
      if (key.upArrow || rawUp) {
        setOverlay({ ...overlay, reviewSelectedIndex: Math.max(0, overlay.reviewSelectedIndex - 1) })
        return
      }
      if (key.downArrow || rawDown) {
        setOverlay({ ...overlay, reviewSelectedIndex: Math.min(1, overlay.reviewSelectedIndex + 1) })
        return
      }
      if (!(key.return || rawEnter)) {
        return
      }

      if (overlay.reviewSelectedIndex === 1) {
        await respondToPermissionRequest(overlay.request, 'deny')
        return
      }

      await respondToPermissionRequest(
        overlay.request,
        'allow',
        {
          updatedInputJson: buildAskUserQuestionResponsePayload(overlay),
          userModified: true,
          decisionReason: 'Answered from OpenClaude UI.',
        },
      )
      return
    }

    const question = overlay.payload.questions[overlay.currentQuestionIndex]
    const footerOptionCount = overlay.planMode ? 2 : 1
    const maxIndex = questionHasPreview(question)
      ? question.options.length - 1
      : question.multiSelect
        ? question.options.length + 1
        : question.options.length
    if (key.upArrow || rawUp) {
      if (overlay.footerFocused) {
        if (overlay.footerIndex > 0) {
          setOverlay({ ...overlay, footerIndex: overlay.footerIndex - 1 })
          return
        }
        setOverlay({ ...overlay, footerFocused: false, footerIndex: 0, selectedIndex: maxIndex })
        return
      }
      setOverlay({ ...overlay, selectedIndex: Math.max(0, overlay.selectedIndex - 1), footerFocused: false, footerIndex: 0 })
      return
    }
    if (key.downArrow || rawDown) {
      if (overlay.footerFocused) {
        setOverlay({ ...overlay, footerIndex: Math.min(footerOptionCount - 1, overlay.footerIndex + 1) })
        return
      }
      if (overlay.selectedIndex >= maxIndex) {
        setOverlay({ ...overlay, footerFocused: true, footerIndex: 0 })
        return
      }
      setOverlay({ ...overlay, selectedIndex: Math.min(maxIndex, overlay.selectedIndex + 1), footerFocused: false, footerIndex: 0 })
      return
    }
    if (!(key.return || rawEnter)) {
      if (!overlay.footerFocused && questionHasPreview(question) && !key.ctrl && !key.meta && !key.shift && inputValue.toLowerCase() === 'n') {
        const existingNotes = overlay.annotations[question.question]?.notes ?? ''
        setOverlay({
          ...overlay,
          editingNotes: true,
          notesText: existingNotes,
          notesCursorOffset: existingNotes.length,
        })
      }
      return
    }

    if (overlay.footerFocused) {
      await respondToPermissionRequest(
        overlay.request,
        'deny',
        {
          payloadJson: buildAskUserQuestionFeedbackPayload(
            overlay,
            overlay.footerIndex === 0 ? 'respond_to_claude' : 'finish_plan_interview',
          ),
          decisionReason: 'Plan interview deferred from OpenClaude UI.',
        },
      )
      return
    }

    if (questionHasPreview(question)) {
      const selectedOption = question.options[overlay.selectedIndex]
      if (!selectedOption) {
        return
      }
      setOverlay({
        ...overlay,
        answers: {
          ...overlay.answers,
          [question.question]: selectedOption.label,
        },
        selectedAnswers: {
          ...overlay.selectedAnswers,
          [question.question]: [selectedOption.label],
        },
        annotations: nextAnnotations(
          overlay.annotations,
          question.question,
          selectedOption.preview || overlay.annotations[question.question]?.notes
            ? {
                ...(selectedOption.preview ? { preview: selectedOption.preview } : {}),
                ...(overlay.annotations[question.question]?.notes ? { notes: overlay.annotations[question.question]?.notes } : {}),
              }
            : null,
        ),
        currentQuestionIndex: overlay.currentQuestionIndex + 1,
        selectedIndex: 0,
        footerFocused: false,
        footerIndex: 0,
        editingNotes: false,
        notesText: '',
        notesCursorOffset: 0,
        reviewSelectedIndex: 0,
      })
      return
    }

    if (!question.multiSelect && overlay.selectedIndex === maxIndex) {
      const initialOther = otherAnswerForQuestion(overlay, question)
      setOverlay({
        ...overlay,
        editingOther: true,
        otherText: initialOther,
        otherCursorOffset: initialOther.length,
      })
      return
    }

    if (question.multiSelect) {
      if (overlay.selectedIndex === question.options.length) {
        const initialOther = otherAnswerForQuestion(overlay, question)
        setOverlay({
          ...overlay,
          editingOther: true,
          otherText: initialOther,
          otherCursorOffset: initialOther.length,
        })
        return
      }

      if (overlay.selectedIndex === question.options.length + 1) {
        setOverlay({
          ...overlay,
          currentQuestionIndex: overlay.currentQuestionIndex + 1,
          selectedIndex: 0,
          footerFocused: false,
          footerIndex: 0,
          reviewSelectedIndex: 0,
        })
        return
      }

      const answer = question.options[overlay.selectedIndex]?.label
      if (!answer) {
        return
      }

      const nextSelections = selectionsForQuestion(overlay, question).includes(answer)
        ? selectionsForQuestion(overlay, question).filter((selection) => selection !== answer)
        : [...selectionsForQuestion(overlay, question), answer]

      setOverlay({
        ...overlay,
        selectedAnswers: {
          ...overlay.selectedAnswers,
          [question.question]: nextSelections,
        },
        answers: {
          ...overlay.answers,
          [question.question]: serializeSelections(nextSelections),
        },
      })
      return
    }

    const answer = question.options[overlay.selectedIndex]?.label
    if (!answer) {
      return
    }

    setOverlay({
      ...overlay,
      answers: {
        ...overlay.answers,
        [question.question]: answer,
      },
      selectedAnswers: {
        ...overlay.selectedAnswers,
        [question.question]: [answer],
      },
      currentQuestionIndex: overlay.currentQuestionIndex + 1,
      selectedIndex: 0,
      footerFocused: false,
      footerIndex: 0,
      reviewSelectedIndex: 0,
    })
    return
  }

  if (key.upArrow || rawUp) {
    setOverlay({ ...overlay, selectedIndex: Math.max(0, overlay.selectedIndex - 1) })
    return
  }

  if (key.downArrow || rawDown) {
    const maxIndex = overlay.kind === 'config' ? configOptions(snapshot, config).length - 1 : overlay.options.length - 1
    setOverlay({ ...overlay, selectedIndex: Math.min(maxIndex, overlay.selectedIndex + 1) })
    return
  }

  if (!(key.return || rawEnter)) {
    return
  }

  if (overlay.kind === 'commands') {
    const command = overlay.commands[overlay.selectedIndex]
    if (!command) {
      return
    }
    setOverlay(null)
    await executeCommand(command)
    return
  }

  if (overlay.kind === 'sessions') {
    const session = overlay.sessions[overlay.selectedIndex]
    if (!session) {
      return
    }
    const result = await client.request<MutationResult>('sessions.resume', { sessionId: session.sessionId })
    setSnapshot(result.snapshot)
    addStatus(result.message)
    setOverlay(null)
    return
  }

  if (overlay.kind === 'rewind') {
    const message = overlay.messages[overlay.selectedIndex]
    if (!message) {
      return
    }
    const result = await client.request<MutationResult>('sessions.rewind', { messageId: message.id })
    setSnapshot(result.snapshot)
    applyPromptState(message.text, message.text.length)
    setTranscriptBaseline(0)
    setStartupHomeVisible(false)
    addStatus(result.message)
    setOverlay(null)
    return
  }

  if (overlay.kind === 'memory') {
    const file = overlay.files[overlay.selectedIndex]
    if (!file) {
      return
    }
    try {
      await memoryFileEditor(file.path)
      addStatus(`Opened memory file at ${file.displayPath}.`)
    } catch (error) {
      addStatus(`Error opening memory file: ${String(error)}`)
    }
    setOverlay(null)
    return
  }

  if (overlay.kind === 'config') {
    const option = configOptions(snapshot, config)[overlay.selectedIndex]
    if (!option) {
      return
    }
    if (option.key === 'close') {
      setOverlay(null)
      return
    }
    if (option.key === 'verbose') {
      if (snapshot) {
        await updateSettings({ verboseOutput: !snapshot.settings.verboseOutput })
      }
      return
    }
    if (option.key === 'tasks') {
      setConfig((current) => ({ ...current, showTasks: !current.showTasks }))
      addStatus(`Background task panel ${config.showTasks ? 'hidden' : 'shown'}.`)
      return
    }
    if (option.key === 'fast') {
      if (snapshot) {
        await updateSettings({ fastMode: !snapshot.settings.fastMode })
      }
      return
    }
    if (option.key === 'reasoning') {
      if (snapshot) {
        await updateSettings({ reasoningVisible: !snapshot.settings.reasoningVisible })
      }
      return
    }
    if (option.key === 'copyFullResponse') {
      if (snapshot) {
        await updateSettings({ alwaysCopyFullResponse: !snapshot.settings.alwaysCopyFullResponse })
      }
    }
    return
  }

  if (overlay.kind === 'providers') {
    const provider = overlay.providers[overlay.selectedIndex]
    if (!provider) {
      return
    }
    const options: PickerOption[] = []
    if (provider.connected) {
      options.push({
        key: 'use-existing',
        label: 'Use existing connection',
        detail: `Keep ${provider.displayName} connected and make it active.`,
      })
    }
    provider.supportedAuthMethods.forEach((authMethod) => {
      options.push({
        key: authMethod,
        label: authLabel(authMethod),
        detail: authDetail(authMethod),
      })
    })
    setOverlay({ kind: 'auth', provider, options, selectedIndex: 0 })
    return
  }

  if (overlay.kind === 'auth') {
    const option = overlay.options[overlay.selectedIndex]
    if (!option) {
      return
    }

    if (option.key === 'use-existing') {
      const result = await client.request<MutationResult>('provider.use', { providerId: overlay.provider.providerId })
      setSnapshot(result.snapshot)
      addStatus(result.message)
      setOverlay(null)
      return
    }

    if (option.key === 'browser_sso') {
      const result = await client.request<MutationResult>(
        'provider.connect',
        { providerId: overlay.provider.providerId, authMethod: 'browser_sso' },
        (event) => {
          if (event.event === 'provider.connect.status') {
            addStatus(event.data.message)
          }
        },
      )
      setSnapshot(result.snapshot)
      addStatus(result.message)
      setOverlay(null)
      return
    }

    const authMethod = option.key as AuthMethod
    setOverlay({
      kind: 'text-entry',
      mode: 'provider-auth',
      providerId: overlay.provider.providerId,
      authMethod,
      label: authMethod === 'api_key' ? 'API key or environment variable' : 'AWS profile',
      placeholder: authMethod === 'api_key' ? `${suggestedEnv(overlay.provider.providerId)} or paste key` : 'default',
      value: '',
      cursorOffset: 0,
    })
    return
  }

  if (overlay.kind === 'permission') {
    const option = overlay.options[overlay.selectedIndex]
    if (!option) {
      return
    }
    await respondToPermissionRequest(overlay.request, option.key as 'allow' | 'deny')
    return
  }

  if (overlay.kind === 'models') {
    const model = overlay.models[overlay.selectedIndex]
    if (!model) {
      return
    }
    const result = await client.request<MutationResult>('models.select', { providerId: model.providerId, modelId: model.id })
    setSnapshot(result.snapshot)
    addStatus(result.message)
    setOverlay(null)
  }
}

function scrollOffsetForOverlay(
  overlay: Extract<Overlay, { kind: 'panel' | 'context' }>,
  key: InputKey,
  inputValue: string,
): number | null {
  if (overlay.kind === 'panel') {
    if (key.upArrow || inputValue === '\u001B[A') {
      return nextPanelScrollOffset(overlay.body, process.stdout.columns, process.stdout.rows, overlay.scrollOffset, 'up')
    }
    if (key.downArrow || inputValue === '\u001B[B') {
      return nextPanelScrollOffset(overlay.body, process.stdout.columns, process.stdout.rows, overlay.scrollOffset, 'down')
    }
    if (key.pageUp) {
      return nextPanelScrollOffset(overlay.body, process.stdout.columns, process.stdout.rows, overlay.scrollOffset, 'pageUp')
    }
    if (key.pageDown) {
      return nextPanelScrollOffset(overlay.body, process.stdout.columns, process.stdout.rows, overlay.scrollOffset, 'pageDown')
    }
    return null
  }

  const pageSize = Math.max(8, (process.stdout.rows ?? 24) - 18)
  const maxOffset = 1000

  if (key.upArrow || inputValue === '\u001B[A') {
    return Math.max(0, overlay.scrollOffset - 1)
  }
  if (key.downArrow || inputValue === '\u001B[B') {
    return Math.min(maxOffset, overlay.scrollOffset + 1)
  }
  if (key.pageUp) {
    return Math.max(0, overlay.scrollOffset - pageSize)
  }
  if (key.pageDown) {
    return Math.min(maxOffset, overlay.scrollOffset + pageSize)
  }
  return null
}

function authLabel(method: AuthMethod): string {
  switch (method) {
    case 'api_key':
      return 'API key'
    case 'browser_sso':
      return 'Browser auth'
    case 'aws_credentials':
      return 'AWS credentials'
  }
}

function authDetail(method: AuthMethod): string {
  switch (method) {
    case 'api_key':
      return 'Read credentials from an environment variable.'
    case 'browser_sso':
      return 'Open a browser and sign in to OpenAI.'
    case 'aws_credentials':
      return 'Use an AWS profile for Bedrock credentials.'
  }
}

function suggestedEnv(providerId: ProviderId): string {
  switch (providerId) {
    case 'anthropic':
      return 'ANTHROPIC_API_KEY'
    case 'openai':
      return 'OPENAI_API_KEY'
    case 'gemini':
      return 'GEMINI_API_KEY'
    case 'mistral':
      return 'MISTRAL_API_KEY'
    case 'kimi':
      return 'MOONSHOT_API_KEY'
    case 'bedrock':
      return 'AWS_PROFILE'
  }
}

function parseSlashCommand(input: string): { name: string; args: string } | null {
  const trimmed = input.trim()
  if (!trimmed.startsWith('/')) {
    return null
  }
  const withoutSlash = trimmed.slice(1).trim()
  if (!withoutSlash) {
    return null
  }
  const [name, ...rest] = withoutSlash.split(/\s+/)
  return {
    name: name.toLowerCase(),
    args: rest.join(' '),
  }
}

function findCommand(commands: CommandView[], name: string): CommandView | undefined {
  const normalized = name.toLowerCase()
  return commands.find((command) => command.name === normalized || command.aliases.includes(normalized))
}

function configOptions(snapshot: BackendSnapshot | null, config: UiConfig): PickerOption[] {
  return [
    {
      key: 'verbose',
      label: `Verbose output: ${snapshot?.settings.verboseOutput ? 'ON' : 'OFF'}`,
      detail: 'Persisted setting for backend/status trace verbosity.',
    },
    {
      key: 'tasks',
      label: `Background tasks: ${config.showTasks ? 'ON' : 'OFF'}`,
      detail: 'Session-local toggle for the task panel at the bottom of the screen.',
    },
    {
      key: 'fast',
      label: `Fast mode: ${snapshot?.settings.fastMode ? 'ON' : 'OFF'}`,
      detail: 'Persisted fast-mode preference for the active OpenClaude session.',
    },
    {
      key: 'reasoning',
      label: `Reasoning visible: ${snapshot?.settings.reasoningVisible ? 'ON' : 'OFF'}`,
      detail: 'Persisted preference for showing model reasoning/thinking blocks.',
    },
    {
      key: 'copyFullResponse',
      label: `Always copy full response: ${snapshot?.settings.alwaysCopyFullResponse ? 'ON' : 'OFF'}`,
      detail: 'Persisted copy preference for /copy.',
    },
    {
      key: 'close',
      label: 'Close config',
      detail: 'Return to the transcript.',
    },
  ]
}

function flattenPanel(panel: PanelView): string {
  const lines: string[] = []
  if (panel.subtitle) {
    lines.push(panel.subtitle, '')
  }
  if (panel.contextUsage) {
    lines.push(
      `${renderContextBar(panel.contextUsage.usedCells, panel.contextUsage.totalCells)} ${panel.contextUsage.estimatedTokens}/${panel.contextUsage.contextWindowTokens} estimated tokens`,
      `Context status: ${panel.contextUsage.status}`,
      '',
    )
  }
  if (panel.kind === 'context') {
    const summaryPrefixes = [
      'Projected prompt messages:',
      'Provider-visible session messages:',
      'Estimated input context:',
      'Context window:',
      'Remaining headroom:',
      'Usage:',
      'Status:',
    ]
    const summaryLines = panel.sections
      .flatMap((section) => section.lines)
      .filter((line) => summaryPrefixes.some((prefix) => line.startsWith(prefix)))
    if (summaryLines.length > 0) {
      lines.push(...summaryLines, '')
    }
  }
  panel.sections.forEach((section) => {
    lines.push(section.title)
    lines.push(...section.lines)
    lines.push('')
  })
  return lines.join('\n').trimEnd()
}

function buildPlanModeBody(snapshot: BackendSnapshot | null): string {
  if (!snapshot) {
    return 'Plan mode is not available until the backend snapshot is loaded.'
  }

  const lines = [
    snapshot.session.planMode ? 'Plan mode is active.' : 'Plan mode is not active.',
    `Workspace: ${snapshot.session.workspaceRoot ?? snapshot.session.workingDirectory ?? 'unknown'}`,
    '',
    'Current plan',
    'No plan written yet.',
  ]

  if (snapshot.session.todos.length > 0) {
    lines.push('', 'Session todos')
    lines.push(...snapshot.session.todos.map((todo) => `${renderTodoStatus(todo.status)} ${todo.content}`))
  }

  lines.push('', 'Tip: use /plan <description> to enter plan mode and immediately start a planning turn.')
  return lines.join('\n')
}

function summarizeText(text: string, maxLength: number): string {
  const normalized = text.replaceAll(/\s+/g, ' ').trim()
  if (normalized.length <= maxLength) {
    return normalized
  }
  return `${normalized.slice(0, Math.max(0, maxLength - 1))}…`
}

function renderContextBar(usedCells: number, totalCells: number): string {
  return '■'.repeat(Math.max(0, usedCells)) + '□'.repeat(Math.max(0, totalCells - usedCells))
}

function defaultKeybindingsTemplate(): string {
  return JSON.stringify(
    {
      Global: {
        'ctrl+o': 'app:toggleTranscript',
        'ctrl+t': 'app:toggleTodos',
      },
      Chat: {
        'meta+p': 'chat:modelPicker',
        'meta+o': 'chat:fastMode',
        'ctrl+g': 'chat:externalEditor',
        'ctrl+s': 'chat:stash',
      },
    },
    null,
    2,
  )
}

function editorColumns(): number {
  return Math.max(16, (process.stdout.columns ?? 80) - 10)
}

function upsertToolCall(current: LiveToolState[], event: PromptToolEvent): LiveToolState[] {
  const next = [...current]
  const index = next.findIndex((toolCall) => toolCall.toolId === event.toolId)
  const merged: LiveToolState = {
    ...event,
    text:
      event.phase === 'delta' && index >= 0
        ? `${next[index]!.text ?? ''}${event.text ?? ''}`
        : event.text ?? next[index]?.text ?? '',
    updatedAt: Date.now(),
  }
  if (index >= 0) {
    next[index] = merged
  } else {
    next.push(merged)
  }
  return next.slice(-8)
}

function buildFooterState(
  snapshot: BackendSnapshot | null,
  historySearch: PromptHistorySearchState | null,
  inputMode: PromptInputMode,
): FooterState {
  const shortcutLines = promptFooterShortcutLines({
    historySearchActive: Boolean(historySearch),
    inputMode,
  })
  const mode = snapshot?.session.planMode ? 'plan' : inputMode
  return {
    lines: [
      {
        key: 'shortcuts-primary',
        text: shortcutLines[0] ?? '',
        dim: true,
      },
      {
        key: 'shortcuts-secondary',
        text: shortcutLines[1] ?? '',
        dim: true,
      },
      {
        key: 'composer',
        text: `Enter submit  shift/meta+enter newline  esc esc clear  ctrl+z suspend  ctrl+c ${snapshot ? 'interrupt/exit' : 'exit'}  ${mode} mode`,
        dim: true,
      },
    ],
  }
}

function findPromptHistorySearchMatches(
  history: PromptHistoryEntry[],
  query: string,
): PromptHistoryEntry[] {
  if (query.length === 0) {
    return []
  }

  const seen = new Set<string>()
  const matches: PromptHistoryEntry[] = []
  for (let index = 0; index < history.length; index += 1) {
    const entry = history[index]
    if (!entry || seen.has(entry.value)) {
      continue
    }
    seen.add(entry.value)
    if (entry.value.lastIndexOf(query) !== -1) {
      matches.push(entry)
    }
  }
  return matches
}

function renderTodoStatus(status: string): string {
  switch (status) {
    case 'completed':
      return '✓'
    case 'in_progress':
      return '●'
    default:
      return '○'
  }
}

function navigateHistory(
  history: PromptHistoryEntry[],
  currentIndex: number | null,
  direction: 'up' | 'down',
  modeFilter: PromptInputMode | null = null,
): { index: number; value: string } | null {
  if (history.length === 0) {
    return null
  }

  if (direction === 'up') {
    const startIndex = currentIndex == null ? 0 : currentIndex + 1
    for (let index = startIndex; index < history.length; index += 1) {
      const entry = history[index]
      if (!entry || !historyEntryMatchesMode(entry.value, modeFilter)) {
        continue
      }
      return { index, value: entry.value }
    }
    return null
  }

  if (currentIndex == null) {
    return null
  }
  for (let index = currentIndex - 1; index >= 0; index -= 1) {
    const entry = history[index]
    if (!entry || !historyEntryMatchesMode(entry.value, modeFilter)) {
      continue
    }
    return { index, value: entry.value }
  }
  return null
}

function historyEntryMatchesMode(
  displayValue: string,
  modeFilter: PromptInputMode | null,
): boolean {
  if (modeFilter == null) {
    return true
  }
  return getModeFromInput(displayValue) === modeFilter
}

function normalizePromptTransitionState(
  previousState: { value: string; cursorOffset: number },
  nextState: { value: string; cursorOffset: number },
  mode: PromptInputMode,
): { mode: PromptInputMode; value: string; cursorOffset: number } {
  const detectedMode = getModeFromInput(nextState.value)
  const isSingleCharInsertion = nextState.value.length === previousState.value.length + 1
  const insertedAtStart = previousState.cursorOffset === 0

  if (insertedAtStart && detectedMode !== 'prompt') {
    if (isSingleCharInsertion) {
      return {
        mode: detectedMode,
        value: previousState.value,
        cursorOffset: previousState.cursorOffset,
      }
    }

    if (previousState.value.length === 0) {
      const nextValue = getValueFromInput(nextState.value)
      return {
        mode: detectedMode,
        value: nextValue,
        cursorOffset: nextValue.length,
      }
    }
  }

  return {
    mode,
    value: nextState.value,
    cursorOffset: nextState.cursorOffset,
  }
}

function buildHelpBody(): string {
  return [
    'OpenClaude command palette',
    '',
    '/resume      Resume a previous session from this workspace',
    '/session     Show current session details',
    '/rename      Rename the current session',
    '/login       Sign in with a provider account or switch auth',
    '/logout      Sign out from the active provider',
    '/provider    Connect or switch a provider',
    '/models      Pick the active model',
    '/config      Open the session config panel',
    '/context     Visualize current session context',
    '/copy [N]    Copy the latest or Nth latest assistant response',
    '/compact     Compact conversation history into a summary',
    '/cost        Show session duration and text volume',
    '/diff        Show uncommitted workspace changes',
    '/doctor      Run backend diagnostics',
    '/keybindings Open the keybindings.json template in your editor',
    '/exit        Exit the CLI',
    '',
    'Shortcuts',
    '  /          open the command palette',
    '  meta+p     open model picker',
    '  ctrl+o     toggle verbose output',
    '  ctrl+t     toggle task panel',
    '  meta+o     toggle fast mode',
    '  ctrl+s     stash or restore the prompt',
    '  ctrl+g     edit the prompt in $EDITOR',
    '  shift+enter insert newline',
    '  \\+enter    insert newline',
    '  esc esc    clear the current input',
    '  ctrl+z     suspend the process',
    '  ctrl+c     exit',
  ].join('\n')
}

function buildContextView(snapshot: BackendSnapshot | null): ContextView | null {
  if (!snapshot) {
    return null
  }

  const visibleMessages = snapshot.messages.filter((message) =>
    message.kind === 'user'
      || message.kind === 'assistant'
      || message.kind === 'system'
      || message.kind === 'compact_summary',
  )
  if (visibleMessages.length === 0) {
    return {
      summaryLines: ['No visible messages in the current session.'],
      detailLines: [],
      cells: [],
    }
  }

  const totalChars = visibleMessages.reduce((sum, message) => sum + message.text.length, 0)
  const estimatedTokens = Math.ceil(totalChars / 4)
  const messages = visibleMessages.slice(-12)
  const weights = messages.map((message) => Math.max(1, message.text.length))
  const totalWeight = weights.reduce((sum, weight) => sum + weight, 0)
  const rawCells = weights.map((weight) => (weight / totalWeight) * CONTEXT_GRID_CELLS)
  const cellsPerMessage = rawCells.map((value) => Math.floor(value))
  let assigned = cellsPerMessage.reduce((sum, value) => sum + value, 0)

  while (assigned < CONTEXT_GRID_CELLS && cellsPerMessage.length > 0) {
    let bestIndex = 0
    let bestRemainder = -1
    for (let index = 0; index < rawCells.length; index += 1) {
      const remainder = rawCells[index]! - cellsPerMessage[index]!
      if (remainder > bestRemainder) {
        bestRemainder = remainder
        bestIndex = index
      }
    }
    cellsPerMessage[bestIndex] = (cellsPerMessage[bestIndex] ?? 0) + 1
    assigned += 1
  }

  const cells: ContextCell[] = []
  messages.forEach((message, index) => {
    for (let count = 0; count < (cellsPerMessage[index] ?? 0); count += 1) {
      cells.push({ color: messageKindColor(message.kind) })
    }
  })

  const detailLines = messages.map((message) => {
    const preview = truncateSingleLine(message.text, 72)
    return `${message.kind.padEnd(9)} ${String(message.text.length).padStart(6)} chars  ${preview}`
  })

  return {
    summaryLines: [
      `Session: ${snapshot.session?.sessionId ?? 'none'}`,
      `Visible messages: ${visibleMessages.length}`,
      `Total chars: ${totalChars}`,
      `Estimated tokens: ${estimatedTokens}`,
      `Provider/model: ${snapshot.state.activeProvider ?? 'none'} · ${snapshot.state.activeModelId ?? 'default'}`,
    ],
    detailLines,
    cells,
  }
}

function messageKindColor(kind: SessionMessageView['kind']): ContextCell['color'] {
  switch (kind) {
    case 'user':
      return 'cyan'
    case 'assistant':
      return 'yellow'
    default:
      return 'magenta'
  }
}

function truncateSingleLine(text: string, maxLength: number): string {
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLength) {
    return normalized
  }
  return `${normalized.slice(0, Math.max(0, maxLength - 1))}…`
}
