import React from 'react'

import { useTextInput } from '../input/useTextInput.ts'
import type { InputKey } from '../input/useTerminalInput.ts'
import type { BaseTextInputProps } from '../types/textInputTypes.ts'
import { BaseTextInput } from './BaseTextInput.tsx'

export type TextInputProps = Omit<BaseTextInputProps, 'inputState'> & {
  value: string
  cursorOffset: number
  columns: number
  maxVisibleLines?: number
  multiline?: boolean
  onChange: (value: string, cursorOffset: number) => void
  onSubmit?: (value: string) => void
  onHistoryUp?: () => void
  onHistoryDown?: () => void
  onClearInput?: () => void
  onExit?: () => void
  beforeInput?: (input: string, key: InputKey) => boolean
  disableCursorMovementForUpDownKeys?: boolean
  disableEscapeDoublePress?: boolean
  inputFilter?: (input: string, key: InputKey) => string
}

export function TextInput({
  value,
  cursorOffset,
  placeholder,
  maxVisibleLines,
  focus = true,
  columns,
  multiline = true,
  onChange,
  onSubmit,
  onHistoryUp,
  onHistoryDown,
  onClearInput,
  onExit,
  onPaste,
  onIsPastingChange,
  argumentHint,
  inlineGhostText,
  beforeInput,
  disableCursorMovementForUpDownKeys,
  disableEscapeDoublePress,
  inputFilter,
}: TextInputProps): React.ReactElement {
  const inputState = useTextInput({
    value,
    cursorOffset,
    columns,
    multiline,
    maxVisibleLines,
    onChange,
    onSubmit,
    onHistoryUp,
    onHistoryDown,
    onClearInput,
    onExit,
    disableCursorMovementForUpDownKeys,
    disableEscapeDoublePress,
    inputFilter,
  })
  const gatedInputState = React.useMemo(
    () => ({
      ...inputState,
      onInput: (input: string, key: InputKey) => {
        if (beforeInput?.(input, key)) {
          return
        }
        inputState.onInput(input, key)
      },
    }),
    [beforeInput, inputState],
  )

  return (
    <BaseTextInput
      value={value}
      placeholder={placeholder}
      focus={focus}
      inputState={gatedInputState}
      onPaste={onPaste}
      onIsPastingChange={onIsPastingChange}
      argumentHint={argumentHint}
      inlineGhostText={inlineGhostText}
    />
  )
}
