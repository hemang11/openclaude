import { useCallback, useMemo, useRef } from 'react'

import { applyInputSequence, computeWrappedLayout, type EditorKey } from './editor.ts'
import type { InputKey } from './useTerminalInput.ts'
import type { TextInputState } from '../types/textInputTypes.ts'

const ESCAPE_CLEAR_WINDOW_MS = 500
const CTRL_C_CLEAR_WINDOW_MS = 500

type Props = {
  value: string
  cursorOffset: number
  columns: number
  multiline?: boolean
  maxVisibleLines?: number
  onChange: (value: string, cursorOffset: number) => void
  onSubmit?: (value: string) => void
  onHistoryUp?: () => void
  onHistoryDown?: () => void
  onClearInput?: () => void
  onExit?: () => void
  disableCursorMovementForUpDownKeys?: boolean
  disableEscapeDoublePress?: boolean
  inputFilter?: (input: string, key: InputKey) => string
}

export function useTextInput({
  value,
  cursorOffset,
  columns,
  multiline = true,
  maxVisibleLines = 6,
  onChange,
  onSubmit,
  onHistoryUp,
  onHistoryDown,
  onClearInput,
  onExit,
  disableCursorMovementForUpDownKeys = false,
  disableEscapeDoublePress = false,
  inputFilter,
}: Props): TextInputState {
  const lastEscapeAt = useRef(0)
  const lastCtrlCAt = useRef(0)

  const layout = useMemo(
    () => computeWrappedLayout(value, cursorOffset, columns),
    [columns, cursorOffset, value],
  )
  const startRow = Math.max(0, layout.cursorRow - maxVisibleLines + 1)
  const visibleLines = useMemo(
    () => layout.lines.slice(startRow, startRow + maxVisibleLines),
    [layout.lines, maxVisibleLines, startRow],
  )
  const renderedValue = useMemo(
    () => visibleLines.join('\n'),
    [visibleLines],
  )
  const viewportCharOffset = useMemo(
    () => findViewportOffset(layout.positions, startRow),
    [layout.positions, startRow],
  )
  const viewportCharEnd = useMemo(
    () => findViewportOffset(layout.positions, startRow + maxVisibleLines),
    [layout.positions, maxVisibleLines, startRow],
  )

  const handleInput = useCallback((rawInput: string, key: InputKey) => {
    const input = inputFilter ? inputFilter(rawInput, key) : rawInput
    if (rawInput.length > 0 && input.length === 0) {
      return
    }

    if (!(key.ctrl && input === 'c')) {
      lastCtrlCAt.current = 0
    }

    if (key.ctrl && input === 'c') {
      if (value.length === 0) {
        onExit?.()
        return
      }
      const now = Date.now()
      if (now - lastCtrlCAt.current <= CTRL_C_CLEAR_WINDOW_MS) {
        onClearInput?.()
        onChange('', 0)
        lastCtrlCAt.current = 0
        return
      }
      lastCtrlCAt.current = now
      return
    }

    if (key.ctrl && input === 'd' && value.length === 0) {
      onExit?.()
      return
    }

    if (key.escape && !disableEscapeDoublePress) {
      const now = Date.now()
      if (now - lastEscapeAt.current <= ESCAPE_CLEAR_WINDOW_MS) {
        onClearInput?.()
        onChange('', 0)
        lastEscapeAt.current = 0
        return
      }
      lastEscapeAt.current = now
      return
    }

    if (key.ctrl && input === 'p') {
      onHistoryUp?.()
      return
    }

    if (key.ctrl && input === 'n') {
      onHistoryDown?.()
      return
    }

    const editorKey = key as EditorKey
    if (disableCursorMovementForUpDownKeys && (editorKey.upArrow || editorKey.downArrow)) {
      if (editorKey.upArrow) {
        onHistoryUp?.()
      } else {
        onHistoryDown?.()
      }
      return
    }

    const transition = applyInputSequence(
      { value, cursorOffset },
      input,
      editorKey,
      { multiline, columns },
    )

    if (
      transition.state.value !== value
      || transition.state.cursorOffset !== cursorOffset
    ) {
      onChange(transition.state.value, transition.state.cursorOffset)
    }

    if (transition.action === 'historyUp') {
      onHistoryUp?.()
      return
    }
    if (transition.action === 'historyDown') {
      onHistoryDown?.()
      return
    }
    if (transition.action === 'submit') {
      onSubmit?.(transition.state.value)
    }
  }, [
    columns,
    cursorOffset,
    disableCursorMovementForUpDownKeys,
    disableEscapeDoublePress,
    inputFilter,
    multiline,
    onChange,
    onClearInput,
    onExit,
    onHistoryDown,
    onHistoryUp,
    onSubmit,
    value,
  ])

  return {
    onInput: handleInput,
    renderedValue,
    cursorLine: layout.cursorRow - startRow,
    absoluteCursorLine: layout.cursorRow,
    cursorColumn: layout.cursorColumn,
    viewportCharOffset,
    viewportCharEnd,
    totalLines: layout.lines.length,
    visibleLineCount: visibleLines.length,
  }
}

function findViewportOffset(
  positions: Array<{ row: number; column: number }>,
  targetRow: number,
): number {
  for (let index = 0; index < positions.length; index += 1) {
    if ((positions[index]?.row ?? 0) >= targetRow) {
      return index
    }
  }
  return positions.length - 1
}
