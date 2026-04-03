import assert from 'node:assert/strict'
import test from 'node:test'

import { createSnapshot } from '../testing/fakeClient.ts'
import { buildPromptSuggestionState } from './suggestions.ts'
import {
  getModeFromInput,
  getValueFromInput,
  isInputModeCharacter,
  prependModeCharacterToInput,
} from './inputModes.ts'

test('input mode helpers follow Claude prompt/bash behavior', () => {
  assert.equal(prependModeCharacterToInput('pwd', 'prompt'), 'pwd')
  assert.equal(prependModeCharacterToInput('pwd', 'bash'), '!pwd')
  assert.equal(getModeFromInput('hello'), 'prompt')
  assert.equal(getModeFromInput('!pwd'), 'bash')
  assert.equal(getValueFromInput('hello'), 'hello')
  assert.equal(getValueFromInput('!pwd'), 'pwd')
  assert.equal(isInputModeCharacter('!'), true)
  assert.equal(isInputModeCharacter('/'), false)
})

test('bash mode suppresses prompt suggestions', () => {
  const snapshot = createSnapshot()

  const promptState = buildPromptSuggestionState('prompt', '/provider', snapshot, [])
  assert.equal(promptState.kind, 'command')
  assert.ok(promptState.items.length > 0)

  const bashState = buildPromptSuggestionState('bash', '/provider', snapshot, [])
  assert.equal(bashState.kind, null)
  assert.equal(bashState.items.length, 0)
  assert.equal(bashState.acceptsOnSubmit, false)
})
