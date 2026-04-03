import assert from 'node:assert/strict'
import test from 'node:test'

import React from 'react'
import { Text } from 'ink'

import { sendRawInput, renderApp, waitFor } from '../testing/testTerminal.ts'
import { BaseTextInput } from './BaseTextInput.tsx'

test('BaseTextInput renders a custom placeholder element when provided', async () => {
  const harness = await renderApp(
    <BaseTextInput
      value=""
      placeholder="Type here"
      placeholderElement={<Text>Custom placeholder</Text>}
      inputState={{
        onInput: () => {},
        renderedValue: '',
        cursorLine: 0,
        absoluteCursorLine: 0,
        cursorColumn: 0,
        viewportCharOffset: 0,
        viewportCharEnd: 0,
        totalLines: 1,
        visibleLineCount: 1,
      }}
    />,
  )

  try {
    await waitFor(() => harness.stdout.output.includes('Custom placeholder'))
    assert.equal(harness.stdout.output.includes('Type here'), false)
  } finally {
    await harness.cleanup()
  }
})

test('BaseTextInput line counter uses the absolute wrapped cursor line', async () => {
  const harness = await renderApp(
    <BaseTextInput
      value={'one\ntwo'}
      showCursor={false}
      inputState={{
        onInput: () => {},
        renderedValue: 'line four\nline five',
        cursorLine: 1,
        absoluteCursorLine: 4,
        cursorColumn: 2,
        viewportCharOffset: 24,
        viewportCharEnd: 42,
        totalLines: 8,
        visibleLineCount: 2,
      }}
    />,
  )

  try {
    await waitFor(() => harness.stdout.output.includes('5/8 lines'))
    assert.equal(harness.stdout.output.includes('2/8 lines'), false)
  } finally {
    await harness.cleanup()
  }
})

test('BaseTextInput reports bracketed paste state changes and delivered text', async () => {
  const pasted: string[] = []
  const pasteStateChanges: boolean[] = []

  const harness = await renderApp(
    <BaseTextInput
      value=""
      inputState={{
        onInput: () => {},
        renderedValue: '',
        cursorLine: 0,
        absoluteCursorLine: 0,
        cursorColumn: 0,
        viewportCharOffset: 0,
        viewportCharEnd: 0,
        totalLines: 1,
        visibleLineCount: 1,
      }}
      onPaste={(text) => {
        pasted.push(text)
      }}
      onIsPastingChange={(isPasting) => {
        pasteStateChanges.push(isPasting)
      }}
    />,
  )

  try {
    await sendRawInput(harness.stdin, '\u001b[200~first line\r\nsecond line\u001b[201~')

    await waitFor(() => pasted.length === 1, {
      timeoutMs: 1000,
      message: 'Bracketed paste was not delivered to BaseTextInput.',
    })
    await waitFor(() => pasteStateChanges.at(-1) === false, {
      timeoutMs: 1000,
      message: 'BaseTextInput did not emit the completed non-pasting state.',
    })

    assert.deepEqual(pasted, ['first line\r\nsecond line'])
    assert.equal(pasteStateChanges.includes(true), true)
    assert.equal(pasteStateChanges.at(-1), false)
  } finally {
    await harness.cleanup()
  }
})

test('BaseTextInput renders inline ghost text after the visible cursor', async () => {
  const harness = await renderApp(
    <BaseTextInput
      value="/pro"
      inlineGhostText="vider"
      inputState={{
        onInput: () => {},
        renderedValue: '/pro',
        cursorLine: 0,
        absoluteCursorLine: 0,
        cursorColumn: 4,
        viewportCharOffset: 0,
        viewportCharEnd: 4,
        totalLines: 1,
        visibleLineCount: 1,
      }}
    />,
  )

  try {
    await waitFor(() => harness.stdout.output.includes('vider'))
  } finally {
    await harness.cleanup()
  }
})
