import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

import { appendPromptHistoryEntry, loadPromptHistory, resetPromptHistoryForTests } from './promptHistory.ts'

test('loadPromptHistory returns current-session entries first within the current project', async () => {
  const tempRoot = await mkdtemp(path.join(os.tmpdir(), 'openclaude-prompt-history-'))
  const home = path.join(tempRoot, 'home')
  const workspace = path.join(tempRoot, 'workspace')
  const otherWorkspace = path.join(tempRoot, 'other-workspace')

  await mkdir(home, { recursive: true })
  const historyFile = path.join(home, 'history.jsonl')
  const lines = [
    JSON.stringify({ value: 'other project', createdAt: 1, project: otherWorkspace, sessionId: 'session-x' }),
    JSON.stringify({ value: 'other session old', createdAt: 2, project: workspace, sessionId: 'session-b' }),
    JSON.stringify({ value: 'current session old', createdAt: 3, project: workspace, sessionId: 'session-a' }),
    JSON.stringify({ value: 'other session new', createdAt: 4, project: workspace, sessionId: 'session-b' }),
    JSON.stringify({ value: 'current session new', createdAt: 5, project: workspace, sessionId: 'session-a' }),
  ]
  await writeFile(historyFile, `${lines.join('\n')}\n`, 'utf8')

  const originalHome = process.env.OPENCLAUDE_HOME
  process.env.OPENCLAUDE_HOME = home
  resetPromptHistoryForTests()

  try {
    const history = await loadPromptHistory({ workspaceRoot: workspace, sessionId: 'session-a' })
    assert.deepEqual(
      history.map((entry) => entry.value),
      ['current session new', 'current session old', 'other session new', 'other session old'],
    )
  } finally {
    resetPromptHistoryForTests()
    if (originalHome === undefined) {
      delete process.env.OPENCLAUDE_HOME
    } else {
      process.env.OPENCLAUDE_HOME = originalHome
    }
  }
})

test('appendPromptHistoryEntry is visible to loadPromptHistory immediately', async () => {
  const tempRoot = await mkdtemp(path.join(os.tmpdir(), 'openclaude-prompt-history-'))
  const home = path.join(tempRoot, 'home')
  const workspace = path.join(tempRoot, 'workspace')

  await mkdir(home, { recursive: true })

  const originalHome = process.env.OPENCLAUDE_HOME
  process.env.OPENCLAUDE_HOME = home
  resetPromptHistoryForTests()

  try {
    await appendPromptHistoryEntry('latest prompt', { workspaceRoot: workspace, sessionId: 'session-a' })
    const history = await loadPromptHistory({ workspaceRoot: workspace, sessionId: 'session-a' })
    assert.equal(history[0]?.value, 'latest prompt')
  } finally {
    resetPromptHistoryForTests()
    if (originalHome === undefined) {
      delete process.env.OPENCLAUDE_HOME
    } else {
      process.env.OPENCLAUDE_HOME = originalHome
    }
  }
})
