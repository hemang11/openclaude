import assert from 'node:assert/strict'
import test from 'node:test'

import React from 'react'

import { renderApp, waitFor } from '../testing/testTerminal.ts'
import type { PermissionEditorSnapshotView } from '../../../types/stdio/protocol.ts'
import {
  createPermissionsOverlayState,
  renderPermissionsOverlay,
  type PermissionsOverlayState,
} from '../ui/PermissionsOverlay.tsx'

test('permissions overlay renders recent activity with the retry footer', async () => {
  const state = createPermissionsOverlayState(permissionSnapshotFixture())
  const harness = await renderApp(<PermissionsOverlayFixture state={state} />)

  try {
    await waitFor(() => harness.stdout.output.includes('Recent activity'), {
      message: 'The recent activity tab did not render.',
    })
    assert.equal(harness.stdout.output.includes('Bash — denied'), true)
    assert.equal(harness.stdout.output.includes('Enter retry denied requests  r retry  Esc close'), true)
  } finally {
    await harness.cleanup()
  }
})

test('permissions overlay renders the delete confirmation for an editable rule', async () => {
  const snapshot = permissionSnapshotFixture()
  const rule = snapshot.tabs
    .find((tab) => tab.id === 'allow')
    ?.sourceGroups.find((group) => group.source === 'session')
    ?.rules.find((entry) => entry.ruleString === 'Bash(ls -1 ~/Desktop)')
  assert.ok(rule)

  const state: PermissionsOverlayState = {
    ...createPermissionsOverlayState(snapshot),
    selectedTab: 'allow',
    mode: { type: 'rule-details', rule, selectedIndex: 0 },
  }
  const harness = await renderApp(<PermissionsOverlayFixture state={state} />)

  try {
    await waitFor(() => harness.stdout.output.includes('Delete allowed tool?'), {
      message: 'The editable rule confirmation did not render.',
    })
    assert.equal(harness.stdout.output.includes('Are you sure you want to delete this permission rule?'), true)
    assert.equal(harness.stdout.output.includes('From Current session'), true)
  } finally {
    await harness.cleanup()
  }
})

test('permissions overlay renders the add-rule source picker', async () => {
  const snapshot = permissionSnapshotFixture()
  const state: PermissionsOverlayState = {
    ...createPermissionsOverlayState(snapshot),
    selectedTab: 'allow',
    mode: {
      type: 'add-rule-source',
      behavior: 'allow',
      ruleString: 'Bash(cat README.md)',
      selectedIndex: 0,
    },
  }
  const harness = await renderApp(<PermissionsOverlayFixture state={state} />)

  try {
    await waitFor(() => harness.stdout.output.includes('Save allow rule'), {
      message: 'The add-rule source picker did not render.',
    })
    assert.equal(harness.stdout.output.includes('Current session'), true)
    assert.equal(harness.stdout.output.includes('User settings'), true)
    assert.equal(harness.stdout.output.includes('Project local settings'), true)
  } finally {
    await harness.cleanup()
  }
})

function permissionSnapshotFixture(): PermissionEditorSnapshotView {
  return {
    sessionId: 'session-test',
    workspaceDisplayPath: '/tmp/workspace',
    workspaceRoot: '/tmp/workspace',
    activeProvider: 'openai',
    activeModel: 'gpt-5.3-codex',
    tabs: [
      {
        id: 'recent',
        title: 'Recent activity',
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
      behaviorTab('allow', {
        session: ['Bash(ls -1 ~/Desktop)'],
        user: ['Bash(cat README.md)'],
      }),
      behaviorTab('ask', {
        project: ['WebFetch(domain:example.com)'],
      }),
      behaviorTab('deny', {
        local: ['Bash(rm -rf /tmp/demo)'],
        policy: ['Bash(sudo reboot)'],
      }),
    ],
  }
}

function behaviorTab(
  behavior: 'allow' | 'ask' | 'deny',
  rulesBySource: Record<string, string[]>,
): PermissionEditorSnapshotView['tabs'][number] {
  return {
    id: behavior,
    title: behavior[0]!.toUpperCase() + behavior.slice(1),
    description: `Rules in the ${behavior} tab.`,
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

function PermissionsOverlayFixture({ state }: { state: PermissionsOverlayState }) {
  return renderPermissionsOverlay(state)
}
