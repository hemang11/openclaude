import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { createSnapshot } from '../testing/fakeClient.ts'
import { buildPromptSuggestionState } from './suggestions.ts'

test('quoted @ file suggestions preserve quotes and spaces in replacements', () => {
  const input = 'read @"docs/my'
  const state = buildPromptSuggestionState(
    'prompt',
    input,
    null,
    ['docs/my file.md'],
    input.length,
  )

  assert.equal(state.kind, 'file')
  assert.equal(state.items.length, 1)
  assert.equal(state.items[0]?.label, '@"docs/my file.md"')
  assert.equal(state.items[0]?.replacement, 'read @"docs/my file.md" ')
  assert.equal(state.items[0]?.cursorOffset, 'read @"docs/my file.md" '.length)
})

test('cursor-aware @ file suggestions replace the whole token around the cursor', () => {
  const input = 'read @src/comp later'
  const cursorOffset = 'read @src/co'.length
  const state = buildPromptSuggestionState(
    'prompt',
    input,
    null,
    ['src/components/App.tsx', 'src/components/Button.tsx'],
    cursorOffset,
  )

  assert.equal(state.kind, 'file')
  assert.equal(state.autocompleteValue, 'read @src/components/ later')
  assert.equal(state.autocompleteCursorOffset, 'read @src/components/'.length)
  assert.equal(state.items[0]?.replacement, 'read @src/components/App.tsx  later')
})

test('path-like @ tokens use direct path completions instead of fuzzy file matching', () => {
  const tempDir = mkdtempSync(path.join(os.tmpdir(), 'openclaude-suggestions-'))
  mkdirSync(path.join(tempDir, 'src'))
  mkdirSync(path.join(tempDir, 'scripts'))
  writeFileSync(path.join(tempDir, 'README.md'), '# test\n', 'utf8')

  const previousCwd = process.cwd()
  process.chdir(tempDir)
  try {
    const input = 'open @./sr'
    const state = buildPromptSuggestionState('prompt', input, null, [], input.length)

    assert.equal(state.kind, 'directory')
    assert.equal(state.items.length, 1)
    assert.equal(state.items[0]?.label, '@src/')
    assert.equal(state.items[0]?.replacement, 'open @src/')
  } finally {
    process.chdir(previousCwd)
  }
})

test('/resume argument suggestions surface scoped sessions and execute-ready replacements', () => {
  const snapshot = createSnapshot()
  const input = '/resume sess'
  const state = buildPromptSuggestionState(
    'prompt',
    input,
    snapshot,
    [],
    input.length,
    {
      sessionSuggestions: [
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
    },
  )

  assert.equal(state.kind, 'command-argument')
  assert.equal(state.acceptsOnSubmit, true)
  assert.equal(state.items[0]?.replacement, '/resume session-older')
})

test('/models argument suggestions include provider-scoped model tokens', () => {
  const snapshot = createSnapshot()
  snapshot.models = [
    ...snapshot.models,
    {
      id: 'claude-sonnet-4',
      displayName: 'Claude Sonnet 4',
      providerId: 'anthropic',
      providerDisplayName: 'Anthropic',
      providerActive: false,
      active: false,
    },
  ]

  const input = '/models claude'
  const state = buildPromptSuggestionState(
    'prompt',
    input,
    snapshot,
    [],
    input.length,
  )

  assert.equal(state.kind, 'command-argument')
  assert.equal(state.acceptsOnSubmit, true)
  assert.equal(state.items[0]?.replacement, '/models anthropic:claude-sonnet-4')
})
