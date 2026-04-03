import assert from 'node:assert/strict'
import test from 'node:test'

import React from 'react'
import { Text } from 'ink'

import { renderApp, waitFor } from '../testing/testTerminal.ts'
import type { TextInputState } from '../types/textInputTypes.ts'
import { useTextInput } from './useTextInput.ts'

type HarnessSnapshot = {
  value: string
  cursorOffset: number
  inputState: TextInputState
}

type HarnessProps = {
  initialValue: string
  initialCursorOffset: number
  disableEscapeDoublePress?: boolean
  disableCursorMovementForUpDownKeys?: boolean
  inputFilter?: (input: string) => string
  onSnapshot: (snapshot: HarnessSnapshot) => void
  onClearInput?: () => void
  onSubmit?: (value: string) => void
  onHistoryUp?: () => void
  onHistoryDown?: () => void
  onExit?: () => void
}

function requireSnapshot(snapshot: HarnessSnapshot | null): HarnessSnapshot {
  if (snapshot == null) {
    throw new Error('Expected useTextInput harness snapshot to be available.')
  }
  return snapshot
}

function UseTextInputHarness({
  initialValue,
  initialCursorOffset,
  disableEscapeDoublePress,
  disableCursorMovementForUpDownKeys,
  inputFilter,
  onSnapshot,
  onClearInput,
  onSubmit,
  onHistoryUp,
  onHistoryDown,
  onExit,
}: HarnessProps): React.ReactElement {
  const [value, setValue] = React.useState(initialValue)
  const [cursorOffset, setCursorOffset] = React.useState(initialCursorOffset)
  const inputState = useTextInput({
    value,
    cursorOffset,
    columns: 8,
    multiline: true,
    onChange: (nextValue, nextCursorOffset) => {
      setValue(nextValue)
      setCursorOffset(nextCursorOffset)
    },
    onClearInput,
    onSubmit,
    onHistoryUp,
    onHistoryDown,
    onExit,
    disableEscapeDoublePress,
    disableCursorMovementForUpDownKeys,
    inputFilter: inputFilter
      ? (input, _key) => inputFilter(input)
      : undefined,
  })

  React.useEffect(() => {
    onSnapshot({
      value,
      cursorOffset,
      inputState,
    })
  }, [cursorOffset, inputState, onSnapshot, value])

  return <Text>{inputState.renderedValue}</Text>
}

test('double escape clears the input through useTextInput', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  let clearCount = 0

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue="hello"
      initialCursorOffset={5}
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onClearInput={() => {
        clearCount += 1
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('', { escape: true })
    requireSnapshot(latestSnapshot).inputState.onInput('', { escape: true })

    await waitFor(() => latestSnapshot?.value === '' && latestSnapshot?.cursorOffset === 0)
    assert.equal(clearCount, 1)
    assert.equal(requireSnapshot(latestSnapshot).cursorOffset, 0)
  } finally {
    await harness.cleanup()
  }
})

test('disableEscapeDoublePress prevents the text-level double-escape clear', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  let clearCount = 0

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue="hello"
      initialCursorOffset={5}
      disableEscapeDoublePress
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onClearInput={() => {
        clearCount += 1
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('', { escape: true })
    requireSnapshot(latestSnapshot).inputState.onInput('', { escape: true })

    await waitFor(() => latestSnapshot?.value === 'hello')
    assert.equal(clearCount, 0)
    assert.equal(requireSnapshot(latestSnapshot).cursorOffset, 5)
  } finally {
    await harness.cleanup()
  }
})

test('disableCursorMovementForUpDownKeys routes arrow keys to history callbacks', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  let historyUpCount = 0
  let historyDownCount = 0

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue={'abc\ndef'}
      initialCursorOffset={5}
      disableCursorMovementForUpDownKeys
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onHistoryUp={() => {
        historyUpCount += 1
      }}
      onHistoryDown={() => {
        historyDownCount += 1
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('', { upArrow: true })
    requireSnapshot(latestSnapshot).inputState.onInput('', { downArrow: true })

    await waitFor(() => historyDownCount === 1)
    assert.equal(historyUpCount, 1)
    assert.equal(requireSnapshot(latestSnapshot).value, 'abc\ndef')
    assert.equal(requireSnapshot(latestSnapshot).cursorOffset, 5)
  } finally {
    await harness.cleanup()
  }
})

test('inputFilter can drop non-empty input before it reaches the editor reducer', async () => {
  let latestSnapshot: HarnessSnapshot | null = null

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue=""
      initialCursorOffset={0}
      inputFilter={(input) => (input === 'x' ? '' : input)}
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('x', {})
    requireSnapshot(latestSnapshot).inputState.onInput('y', {})

    await waitFor(() => latestSnapshot?.value === 'y' && latestSnapshot?.cursorOffset === 1)
    assert.equal(requireSnapshot(latestSnapshot).cursorOffset, 1)
  } finally {
    await harness.cleanup()
  }
})

test('submit flows through useTextInput when return is pressed', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  const submissions: string[] = []

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue="pwd"
      initialCursorOffset={3}
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onSubmit={(value) => {
        submissions.push(value)
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('\r', { return: true })

    await waitFor(() => submissions.length === 1)
    assert.deepEqual(submissions, ['pwd'])
  } finally {
    await harness.cleanup()
  }
})

test('double ctrl+c clears non-empty input through useTextInput', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  let clearCount = 0
  let exitCount = 0

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue="hello"
      initialCursorOffset={5}
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onClearInput={() => {
        clearCount += 1
      }}
      onExit={() => {
        exitCount += 1
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('c', { ctrl: true })
    requireSnapshot(latestSnapshot).inputState.onInput('c', { ctrl: true })

    await waitFor(() => latestSnapshot?.value === '' && latestSnapshot?.cursorOffset === 0)
    assert.equal(clearCount, 1)
    assert.equal(exitCount, 0)
  } finally {
    await harness.cleanup()
  }
})

test('empty ctrl+c exits through useTextInput', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  let exitCount = 0

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue=""
      initialCursorOffset={0}
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onExit={() => {
        exitCount += 1
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('c', { ctrl: true })
    await waitFor(() => exitCount === 1)
  } finally {
    await harness.cleanup()
  }
})

test('ctrl+p and ctrl+n route through useTextInput history callbacks', async () => {
  let latestSnapshot: HarnessSnapshot | null = null
  let historyUpCount = 0
  let historyDownCount = 0

  const harness = await renderApp(
    <UseTextInputHarness
      initialValue="prompt"
      initialCursorOffset={6}
      onSnapshot={(snapshot) => {
        latestSnapshot = snapshot
      }}
      onHistoryUp={() => {
        historyUpCount += 1
      }}
      onHistoryDown={() => {
        historyDownCount += 1
      }}
    />,
  )

  try {
    await waitFor(() => latestSnapshot !== null)
    requireSnapshot(latestSnapshot).inputState.onInput('p', { ctrl: true })
    requireSnapshot(latestSnapshot).inputState.onInput('n', { ctrl: true })

    await waitFor(() => historyUpCount === 1 && historyDownCount === 1)
    assert.equal(requireSnapshot(latestSnapshot).value, 'prompt')
  } finally {
    await harness.cleanup()
  }
})
