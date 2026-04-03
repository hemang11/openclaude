import path from 'node:path'

import React from 'react'
import { Box, Text } from 'ink'

import type { BackendSnapshot } from '../../../types/stdio/protocol.ts'
import type { PromptInputMode } from '../prompt/inputModes.ts'
import { uiTheme } from './theme.ts'

type Props = {
  snapshot: BackendSnapshot | null
  inputMode: PromptInputMode
  busy: boolean
  reasoningActive: boolean
  commandActivityLabel: string | null
  interruptRequested: boolean
  overlayActive: boolean
  historySearchActive: boolean
  showTasks: boolean
  liveToolCount: number
}

export function StatusLine({
  snapshot,
  inputMode,
  busy,
  reasoningActive,
  commandActivityLabel,
  interruptRequested,
  overlayActive,
  historySearchActive,
  showTasks,
  liveToolCount,
}: Props): React.ReactElement | null {
  if (!snapshot) {
    return null
  }

  const workspaceLabel = formatWorkspace(snapshot.session.workspaceRoot ?? snapshot.session.workingDirectory)
  const contextPercent = formatContextRemainingPercent(
    snapshot.session.estimatedContextTokens,
    snapshot.session.contextWindowTokens,
  )
  const todoCount = snapshot.session.todos.length
  const providerLabel = snapshot.state.activeProvider ?? 'none'
  const modelLabel = snapshot.state.activeModelId ?? 'default'
  const effortLabel = snapshot.settings.effortLevel ?? 'auto'
  const modeLabel = snapshot.session.planMode ? 'plan' : inputMode
  const activityLabel = interruptRequested
    ? 'Interrupting active prompt…'
    : commandActivityLabel
      ? commandActivityLabel
    : busy
      ? liveToolCount > 0
        ? `Working with ${liveToolCount} live tool${liveToolCount === 1 ? '' : 's'}`
        : reasoningActive
          ? 'Thinking'
          : 'Working'
      : overlayActive
        ? 'Overlay active'
        : historySearchActive
          ? 'Searching prompt history'
          : 'Ready'

  return (
    <Box flexDirection="column" marginTop={1}>
      <Text dimColor>
        {providerLabel}
        {' · '}
        {modelLabel}
        {' · '}
        {contextPercent}
        {' left'}
        {' · '}
        {snapshot.settings.fastMode ? 'fast' : 'standard'}
        {' · '}
        {effortLabel}
        {' effort'}
        {' · '}
        {modeLabel}
        {' mode'}
        {' · '}
        {todoCount}
        {' todo'}
        {todoCount === 1 ? '' : 's'}
        {' · '}
        {workspaceLabel}
      </Text>
      <Text color={interruptRequested ? uiTheme.warning : uiTheme.brandMuted} dimColor={!interruptRequested}>
        {activityLabel}
        {showTasks ? ' · task pane visible' : ''}
      </Text>
    </Box>
  )
}

function formatWorkspace(workspacePath: string | null | undefined): string {
  if (!workspacePath) {
    return 'no workspace'
  }
  return path.basename(workspacePath) || workspacePath
}

export function formatContextRemainingPercent(estimatedTokens: number, contextWindowTokens: number): string {
  if (!contextWindowTokens || contextWindowTokens <= 0) {
    return '100%'
  }
  const remainingPercent = Math.max(0, Math.min(100, (1 - (estimatedTokens / contextWindowTokens)) * 100))
  let rounded = Math.round(remainingPercent)
  if (estimatedTokens > 0 && rounded >= 100) {
    rounded = 99
  }
  if (estimatedTokens < contextWindowTokens && rounded <= 0) {
    rounded = 1
  }
  return `${rounded}%`
}
