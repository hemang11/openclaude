import assert from 'node:assert/strict'
import test from 'node:test'

import { applyInputSequence, resetEditorForTests } from './editor.ts'

test('physical backspace sequence deletes the previous character', () => {
  resetEditorForTests()
  const transition = applyInputSequence(
    { value: 'abcd', cursorOffset: 4 },
    '\u007f',
    { backspace: true },
    { multiline: true, columns: 80 },
  )

  assert.equal(transition.state.value, 'abc')
  assert.equal(transition.state.cursorOffset, 3)
  assert.equal(transition.action, 'none')
})

test('forward delete escape sequence deletes the character under the cursor', () => {
  resetEditorForTests()
  const transition = applyInputSequence(
    { value: 'abcd', cursorOffset: 1 },
    '\u001b[3~',
    { delete: true },
    { multiline: true, columns: 80 },
  )

  assert.equal(transition.state.value, 'acd')
  assert.equal(transition.state.cursorOffset, 1)
  assert.equal(transition.action, 'none')
})

test('return sequence submits the prompt', () => {
  resetEditorForTests()
  const transition = applyInputSequence(
    { value: 'who are you?', cursorOffset: 12 },
    '\r',
    { return: true },
    { multiline: true, columns: 80 },
  )

  assert.equal(transition.state.value, 'who are you?')
  assert.equal(transition.action, 'submit')
})

test('pasted multiline input inserts text without submitting the prompt', () => {
  resetEditorForTests()
  const transition = applyInputSequence(
    { value: '', cursorOffset: 0 },
    'first line\r\nsecond line',
    { pasted: true },
    { multiline: true, columns: 80 },
  )

  assert.equal(transition.state.value, 'first line\nsecond line')
  assert.equal(transition.state.cursorOffset, 'first line\nsecond line'.length)
  assert.equal(transition.action, 'none')
})

test('ctrl+k kills to the wrapped line end and ctrl+y yanks it back', () => {
  resetEditorForTests()
  const killed = applyInputSequence(
    { value: '0123456789', cursorOffset: 5 },
    'k',
    { ctrl: true },
    { multiline: true, columns: 8 },
  )

  assert.equal(killed.state.value, '01234789')
  assert.equal(killed.state.cursorOffset, 5)

  const yanked = applyInputSequence(
    killed.state,
    'y',
    { ctrl: true },
    { multiline: true, columns: 8 },
  )

  assert.equal(yanked.state.value, '0123456789')
  assert.equal(yanked.state.cursorOffset, 7)
})

test('meta+y yank-pop cycles through earlier kill-ring entries', () => {
  resetEditorForTests()
  let state = { value: 'alpha beta gamma', cursorOffset: 'alpha beta gamma'.length }

  state = applyInputSequence(state, '\u007f', { meta: true, backspace: true }, { multiline: true, columns: 80 }).state
  assert.equal(state.value, 'alpha beta ')

  state = applyInputSequence(state, '', { leftArrow: true }, { multiline: true, columns: 80 }).state
  state = applyInputSequence(state, '', { rightArrow: true }, { multiline: true, columns: 80 }).state

  state = applyInputSequence(state, '\u007f', { meta: true, backspace: true }, { multiline: true, columns: 80 }).state
  assert.equal(state.value, 'alpha ')

  state = applyInputSequence(state, 'y', { ctrl: true }, { multiline: true, columns: 80 }).state
  assert.equal(state.value, 'alpha beta ')

  state = applyInputSequence(state, 'y', { meta: true }, { multiline: true, columns: 80 }).state
  assert.equal(state.value, 'alpha gamma')
})

test('home and end follow wrapped-line semantics', () => {
  resetEditorForTests()
  const home = applyInputSequence(
    { value: 'abcdefghij', cursorOffset: 9 },
    '',
    { home: true },
    { multiline: true, columns: 8 },
  )
  assert.equal(home.state.cursorOffset, 8)

  const end = applyInputSequence(
    { value: 'abcdefghij', cursorOffset: 1 },
    '',
    { end: true },
    { multiline: true, columns: 8 },
  )
  assert.equal(end.state.cursorOffset, 7)
})

test('up and down fall back to logical lines when wrapped movement is exhausted', () => {
  resetEditorForTests()
  const up = applyInputSequence(
    { value: 'abc\ndef', cursorOffset: 5 },
    '',
    { upArrow: true },
    { multiline: true, columns: 80 },
  )
  assert.equal(up.state.cursorOffset, 1)

  const down = applyInputSequence(
    { value: 'abc\ndef', cursorOffset: 1 },
    '',
    { downArrow: true },
    { multiline: true, columns: 80 },
  )
  assert.equal(down.state.cursorOffset, 5)
})
