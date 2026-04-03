import type React from 'react'

import type { InputKey } from '../input/useTerminalInput.ts'

export type TextInputState = {
  onInput: (input: string, key: InputKey) => void
  renderedValue: string
  cursorLine: number
  absoluteCursorLine: number
  cursorColumn: number
  viewportCharOffset: number
  viewportCharEnd: number
  totalLines: number
  visibleLineCount: number
}

export type BaseTextInputProps = {
  value: string
  placeholder?: string
  focus?: boolean
  showCursor?: boolean
  dimColor?: boolean
  terminalFocus?: boolean
  hidePlaceholderText?: boolean
  placeholderElement?: React.ReactNode
  inputState: TextInputState
  onPaste?: (text: string) => void
  onIsPastingChange?: (isPasting: boolean) => void
  argumentHint?: string
  inlineGhostText?: string | null
  children?: React.ReactNode
}
