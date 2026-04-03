import React from 'react'
import { Box, Text } from 'ink'

import type {
  PermissionActivityView,
  PermissionEditorSnapshotView,
  PermissionRuleSourceGroupView,
  PermissionRuleView,
} from '../../../types/stdio/protocol.ts'
import { PromptComposer } from './PromptComposer.tsx'
import { uiTheme } from './theme.ts'

export type PermissionsTab = 'recent' | 'allow' | 'ask' | 'deny' | 'workspace'
export type PermissionRuleBehavior = Exclude<PermissionsTab, 'recent' | 'workspace'>

export const PERMISSIONS_TABS: PermissionsTab[] = ['recent', 'allow', 'ask', 'deny', 'workspace']

export type PermissionListRow =
  | { kind: 'add'; key: 'add-new-rule'; label: string }
  | { kind: 'rule'; key: string; rule: PermissionRuleView }

export type PermissionsOverlayMode =
  | { type: 'browse' }
  | { type: 'search'; query: string; cursorOffset: number }
  | { type: 'add-rule-input'; behavior: PermissionRuleBehavior; value: string; cursorOffset: number }
  | { type: 'add-rule-source'; behavior: PermissionRuleBehavior; ruleString: string; selectedIndex: number }
  | { type: 'rule-details'; rule: PermissionRuleView; selectedIndex: number }

export type PermissionsOverlayState = {
  kind: 'permissions'
  snapshot: PermissionEditorSnapshotView
  selectedTab: PermissionsTab
  selectedIndexByTab: Record<PermissionsTab, number>
  mode: PermissionsOverlayMode
}

const EDITABLE_SOURCES = ['session', 'user', 'project', 'local'] as const

export function createPermissionsOverlayState(snapshot: PermissionEditorSnapshotView): PermissionsOverlayState {
  return {
    kind: 'permissions',
    snapshot,
    selectedTab: getInitialPermissionsTab(snapshot),
    selectedIndexByTab: {
      recent: 0,
      allow: 0,
      ask: 0,
      deny: 0,
      workspace: 0,
    },
    mode: { type: 'browse' },
  }
}

export function permissionsTabLabel(tab: PermissionsTab): string {
  switch (tab) {
    case 'recent':
      return 'Recent'
    case 'allow':
      return 'Allow'
    case 'ask':
      return 'Ask'
    case 'deny':
      return 'Deny'
    case 'workspace':
      return 'Workspace'
  }
}

export function getPermissionsNextTab(current: PermissionsTab, direction: -1 | 1): PermissionsTab {
  const index = PERMISSIONS_TABS.indexOf(current)
  const next = (index + direction + PERMISSIONS_TABS.length) % PERMISSIONS_TABS.length
  return PERMISSIONS_TABS[next] ?? current
}

export function clampPermissionIndex(index: number, rowCount: number): number {
  if (rowCount <= 0) {
    return 0
  }
  return Math.max(0, Math.min(rowCount - 1, index))
}

export function getPermissionsCurrentQuery(state: PermissionsOverlayState): string {
  return state.mode.type === 'search' ? state.mode.query : ''
}

export function getPermissionsRecentActivity(snapshot: PermissionEditorSnapshotView): PermissionActivityView[] {
  return permissionTab(snapshot, 'recent')?.recentActivities ?? []
}

export function getPermissionsEditableSources(
  snapshot: PermissionEditorSnapshotView,
  behavior: PermissionRuleBehavior,
): PermissionRuleSourceGroupView[] {
  const tab = permissionTab(snapshot, behavior)
  if (!tab) {
    return []
  }
  return tab.sourceGroups.filter((group) => group.editable && EDITABLE_SOURCES.includes(group.source as typeof EDITABLE_SOURCES[number]))
}

export function getPermissionsTabRows(
  snapshot: PermissionEditorSnapshotView,
  tab: PermissionsTab,
  query = '',
): PermissionListRow[] {
  if (tab === 'recent' || tab === 'workspace') {
    return []
  }
  const groupRows = groupedRuleRows(snapshot, tab, query)
  const rows: PermissionListRow[] = []
  if (!query.trim()) {
    rows.push({ kind: 'add', key: 'add-new-rule', label: 'Add a new rule...' })
  }
  for (const group of groupRows) {
    for (const row of group.rows) {
      rows.push({
        kind: 'rule',
        key: `${group.sourceId}:${row.source}:${row.behavior}:${row.ruleString}`,
        rule: row,
      })
    }
  }
  return rows
}

export function renderPermissionsOverlay(state: PermissionsOverlayState): React.ReactElement {
  if (state.mode.type === 'add-rule-input') {
    return renderAddRuleInput(state.mode)
  }
  if (state.mode.type === 'add-rule-source') {
    return renderAddRuleSource(state.snapshot, state.mode)
  }
  if (state.mode.type === 'rule-details') {
    return renderRuleDetails(state.mode.rule, state.mode.selectedIndex)
  }

  const query = getPermissionsCurrentQuery(state)
  const recentRows = getPermissionsRecentActivity(state.snapshot)
  const ruleRows = getPermissionsTabRows(state.snapshot, state.selectedTab, query)
  const selectedIndex = clampPermissionIndex(state.selectedIndexByTab[state.selectedTab] ?? 0, state.selectedTab === 'recent' ? recentRows.length : ruleRows.length)

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        Permissions
      </Text>
      <Text dimColor>
        {state.snapshot.activeProvider ? `Provider: ${state.snapshot.activeProvider}` : 'No active provider'}
        {state.snapshot.activeModel ? `  Model: ${state.snapshot.activeModel}` : ''}
      </Text>
      <Text dimColor>{state.snapshot.workspaceDisplayPath}</Text>
      <Box height={1} />
      <TabBar state={state} />
      <Box height={1} />
      {state.selectedTab === 'recent' ? renderRecentTab(recentRows, selectedIndex) : null}
      {state.selectedTab === 'workspace' ? renderWorkspaceTab(state.snapshot) : null}
      {state.selectedTab !== 'recent' && state.selectedTab !== 'workspace'
        ? renderRuleTab(state, query, selectedIndex)
        : null}
      <Box height={1} />
      <Text dimColor>{footerForState(state, recentRows)}</Text>
    </Box>
  )
}

function renderAddRuleInput(mode: Extract<PermissionsOverlayMode, { type: 'add-rule-input' }>): React.ReactElement {
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        Add {mode.behavior} rule
      </Text>
      <Text dimColor>Enter a Claude-style rule string such as `Bash(ls -1 ~/Desktop)`.</Text>
      <Box height={1} />
      <Box>
        <Text color={uiTheme.promptMarker}>{'> '}</Text>
        <PromptComposer
          value={mode.value}
          cursorOffset={mode.cursorOffset}
          placeholder="Bash(ls -1 ~/Desktop)"
          maxVisibleLines={2}
        />
      </Box>
      <Box height={1} />
      <Text dimColor>Enter continue  Esc cancel</Text>
    </Box>
  )
}

function renderAddRuleSource(
  snapshot: PermissionEditorSnapshotView,
  mode: Extract<PermissionsOverlayMode, { type: 'add-rule-source' }>,
): React.ReactElement {
  const sources = getPermissionsEditableSources(snapshot, mode.behavior)
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        Save {mode.behavior} rule
      </Text>
      <Text>{mode.ruleString}</Text>
      <Box height={1} />
      {sources.map((source, index) => (
        <Text key={source.source} color={index === mode.selectedIndex ? uiTheme.overlaySelection : undefined}>
          {index === mode.selectedIndex ? '› ' : '  '}
          {source.displayName}
        </Text>
      ))}
      <Box height={1} />
      <Text dimColor>↑/↓ move  Enter save  Esc back</Text>
    </Box>
  )
}

function renderRuleDetails(
  rule: PermissionRuleView,
  selectedIndex: number,
): React.ReactElement {
  const behaviorLabel = rule.behavior === 'allow'
    ? 'allowed'
    : rule.behavior === 'deny'
      ? 'denied'
      : 'ask'

  if (!rule.editable) {
    return (
      <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
        <Text bold color={uiTheme.overlayTitle}>
          Rule details
        </Text>
        <Box height={1} />
        <Text bold>{rule.ruleString}</Text>
        <Text>{rule.summary}</Text>
        <Text dimColor>{`From ${rule.displayName}`}</Text>
        <Box height={1} />
        <Text dimColor>Managed rules are read-only. Esc closes.</Text>
      </Box>
    )
  }

  return (
    <Box flexDirection="column" borderStyle="round" borderColor="red" paddingX={1}>
      <Text bold color="red">
        Delete {behaviorLabel} tool?
      </Text>
      <Box height={1} />
      <Text bold>{rule.ruleString}</Text>
      <Text>{rule.summary}</Text>
      <Text dimColor>{`From ${rule.displayName}`}</Text>
      <Box height={1} />
      <Text>Are you sure you want to delete this permission rule?</Text>
      <Box height={1} />
      {['Yes', 'No'].map((option, index) => (
        <Text key={option} color={index === selectedIndex ? uiTheme.overlaySelection : undefined}>
          {index === selectedIndex ? '› ' : '  '}
          {option}
        </Text>
      ))}
      <Box height={1} />
      <Text dimColor>↑/↓ move  Enter select  Esc cancel</Text>
    </Box>
  )
}

function TabBar({ state }: { state: PermissionsOverlayState }): React.ReactElement {
  return (
    <Box flexDirection="row" flexWrap="wrap">
      {PERMISSIONS_TABS.map((tab) => {
        const selected = tab === state.selectedTab
        return (
          <Text key={tab} bold={selected} color={selected ? uiTheme.overlaySelection : undefined}>
            {selected ? `› ${permissionsTabLabel(tab)} ` : `  ${permissionsTabLabel(tab)} `}
          </Text>
        )
      })}
    </Box>
  )
}

function renderRecentTab(rows: PermissionActivityView[], selectedIndex: number): React.ReactElement {
  if (rows.length === 0) {
    return (
      <Box flexDirection="column">
        <Text bold>Recent activity</Text>
        <Text dimColor>No recent permission activity in this session yet.</Text>
      </Box>
    )
  }

  return (
    <Box flexDirection="column">
      <Text bold>Recent activity</Text>
      <Text dimColor>Commands recently denied by the auto mode classifier.</Text>
      <Box height={1} />
      {rows.map((row, index) => (
        <Box key={row.toolUseId} flexDirection="column">
          <Text color={index === selectedIndex ? uiTheme.overlaySelection : undefined}>
            {index === selectedIndex ? '› ' : '  '}
            <Text bold>{row.toolName}</Text>
            <Text>{` — ${row.status}`}</Text>
          </Text>
          <Box marginLeft={2}>
            <Text dimColor>{row.detail}</Text>
          </Box>
        </Box>
      ))}
    </Box>
  )
}

function renderRuleTab(
  state: PermissionsOverlayState,
  query: string,
  selectedIndex: number,
): React.ReactElement {
  const tab = permissionTab(state.snapshot, state.selectedTab)
  if (!tab) {
    return <Text dimColor>No rules are currently visible in this tab.</Text>
  }
  const groups = groupedRuleRows(state.snapshot, state.selectedTab as PermissionRuleBehavior, query)
  const rows = getPermissionsTabRows(state.snapshot, state.selectedTab, query)
  if (rows.length === 0) {
    return (
      <Box flexDirection="column">
        <Text>{tab.description}</Text>
        {query ? (
          <>
            <Box height={1} />
            <SearchBox query={query} cursorOffset={state.mode.type === 'search' ? state.mode.cursorOffset : query.length} />
          </>
        ) : null}
        <Box height={1} />
        <Text dimColor>No rules are currently visible in this tab.</Text>
      </Box>
    )
  }

  let flatIndex = 0
  return (
    <Box flexDirection="column">
      <Text>{tab.description}</Text>
      {query || state.mode.type === 'search' ? (
        <>
          <Box height={1} />
          <SearchBox query={query} cursorOffset={state.mode.type === 'search' ? state.mode.cursorOffset : query.length} />
        </>
      ) : null}
      <Box height={1} />
      {!query ? (
        <Text color={selectedIndex === flatIndex ? uiTheme.overlaySelection : undefined}>
          {selectedIndex === flatIndex ? '› ' : '  '}
          Add a new rule...
        </Text>
      ) : null}
      {!query ? (() => {
        flatIndex += 1
        return null
      })() : null}
      {groups.map((group) => (
        <Box key={group.sourceId} flexDirection="column" marginBottom={1}>
          <Text bold color={group.editable ? uiTheme.brand : uiTheme.overlaySelection}>
            {group.sourceLabel}
          </Text>
          {group.rows.map((rule) => {
            const isSelected = flatIndex === selectedIndex
            flatIndex += 1
            return (
              <Box key={`${group.sourceId}:${rule.ruleString}`} flexDirection="column">
                <Text color={isSelected ? uiTheme.overlaySelection : undefined}>
                  {isSelected ? '› ' : '  '}
                  <Text bold>{rule.ruleString}</Text>
                  {rule.editable ? null : <Text dimColor>{' (read-only)'}</Text>}
                </Text>
                <Box marginLeft={2}>
                  <Text dimColor>{`From ${rule.displayName}`}</Text>
                </Box>
              </Box>
            )
          })}
        </Box>
      ))}
    </Box>
  )
}

function renderWorkspaceTab(snapshot: PermissionEditorSnapshotView): React.ReactElement {
  const groups = (['allow', 'ask', 'deny'] as const)
    .map((behavior) => permissionTab(snapshot, behavior))
    .filter((tab): tab is NonNullable<typeof tab> => tab != null)
    .flatMap((tab) => tab.sourceGroups)
  const sourceSummary = groups.length === 0
    ? ['No permission sources were reported by the backend.']
    : groups
        .filter((group, index, all) => all.findIndex((candidate) => candidate.source === group.source) === index)
        .map((group) => `${group.displayName}: ${countRulesForSource(snapshot, group.source)} rule${countRulesForSource(snapshot, group.source) === 1 ? '' : 's'}`)

  return (
    <Box flexDirection="column">
      <Text bold>Workspace</Text>
      <Text>{`Current scope: ${snapshot.workspaceDisplayPath}`}</Text>
      {snapshot.workspaceRoot ? <Text dimColor>{`Workspace root: ${snapshot.workspaceRoot}`}</Text> : null}
      <Box height={1} />
      <Text bold>Source hierarchy</Text>
      {sourceSummary.map((line) => (
        <Text key={line}>{line}</Text>
      ))}
    </Box>
  )
}

function footerForState(
  state: PermissionsOverlayState,
  recentRows: PermissionActivityView[],
): string {
  if (state.mode.type === 'search') {
    return 'Type to filter · Enter select · ↑/↓ navigate · Esc clear'
  }
  switch (state.selectedTab) {
    case 'recent':
      return recentRows.length > 0 ? 'Enter retry denied requests  r retry  Esc close' : 'Esc close'
    case 'workspace':
      return 'Esc close'
    default:
      return '↑/↓ navigate  Enter select  Type to search  ←/→ switch  Esc close'
  }
}

function permissionTab(snapshot: PermissionEditorSnapshotView, tab: PermissionsTab | PermissionRuleBehavior) {
  return snapshot.tabs.find((candidate) => candidate.id === tab)
}

function groupedRuleRows(
  snapshot: PermissionEditorSnapshotView,
  behavior: PermissionRuleBehavior,
  query: string,
): Array<{ sourceId: string; sourceLabel: string; editable: boolean; rows: PermissionRuleView[] }> {
  const tab = permissionTab(snapshot, behavior)
  if (!tab) {
    return []
  }
  const lowerQuery = query.trim().toLowerCase()
  return tab.sourceGroups
    .map((group) => ({
      sourceId: group.source,
      sourceLabel: group.displayName,
      editable: group.editable,
      rows: group.rules.filter((rule) => !lowerQuery || rule.ruleString.toLowerCase().includes(lowerQuery)),
    }))
    .filter((group) => group.rows.length > 0)
}

function getInitialPermissionsTab(snapshot: PermissionEditorSnapshotView): PermissionsTab {
  const recent = getPermissionsRecentActivity(snapshot)
  return recent.length > 0 ? 'recent' : 'allow'
}

function countRulesForSource(snapshot: PermissionEditorSnapshotView, source: string): number {
  return snapshot.tabs
    .filter((tab) => tab.id !== 'recent')
    .flatMap((tab) => tab.sourceGroups)
    .filter((group) => group.source === source)
    .reduce((count, group) => count + group.rules.length, 0)
}

function SearchBox({ query, cursorOffset }: { query: string; cursorOffset: number }): React.ReactElement {
  return (
    <Box flexDirection="column">
      <Text dimColor>Search rules</Text>
      <Box>
        <Text color={uiTheme.promptMarker}>{'/ '}</Text>
        <PromptComposer
          value={query}
          cursorOffset={cursorOffset}
          placeholder="Type to filter"
          maxVisibleLines={1}
        />
      </Box>
    </Box>
  )
}
