import React from 'react'

import type { InputKey } from './useTerminalInput.ts'

const PASTE_THRESHOLD = 800
const PASTE_COMPLETION_TIMEOUT_MS = 100

type PasteHandlerProps = {
  onPaste?: (text: string) => void
  onInput: (input: string, key: InputKey) => void
}

export function usePasteHandler({
  onPaste,
  onInput,
}: PasteHandlerProps): {
  wrappedOnInput: (input: string, key: InputKey) => void
  isPasting: boolean
} {
  const [pasteState, setPasteState] = React.useState<{
    chunks: string[]
    timeoutId: ReturnType<typeof setTimeout> | null
  }>({ chunks: [], timeoutId: null })
  const [isPasting, setIsPasting] = React.useState(false)
  const pastePendingRef = React.useRef(false)
  const isPastingRef = React.useRef(false)
  const pasteChunksRef = React.useRef<string[]>([])
  const pasteTimeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)

  React.useEffect(() => {
    isPastingRef.current = isPasting
  }, [isPasting])

  const flushPaste = React.useCallback(() => {
    pastePendingRef.current = false
    isPastingRef.current = false
    if (pasteTimeoutRef.current) {
      clearTimeout(pasteTimeoutRef.current)
      pasteTimeoutRef.current = null
    }
    const pastedText = pasteChunksRef.current.join('').replace(/\[I$/, '').replace(/\[O$/, '')
    pasteChunksRef.current = []
    setPasteState({ chunks: [], timeoutId: null })
    if (onPaste && pastedText) {
      onPaste(pastedText)
    }
    setIsPasting(false)
  }, [onPaste])

  const resetPasteTimeout = React.useCallback(
    (currentTimeoutId: ReturnType<typeof setTimeout> | null) => {
      if (currentTimeoutId) {
        clearTimeout(currentTimeoutId)
      }

      const timeoutId = setTimeout(() => {
        flushPaste()
      }, PASTE_COMPLETION_TIMEOUT_MS)
      pasteTimeoutRef.current = timeoutId
      return timeoutId
    },
    [flushPaste],
  )

  const wrappedOnInput = React.useCallback(
    (input: string, key: InputKey): void => {
      const isFromPaste = Boolean(key.pasted)
      if (isFromPaste) {
        setIsPasting(true)
      }

      const shouldHandleAsPaste =
        Boolean(onPaste) &&
        (
          input.length > PASTE_THRESHOLD ||
          pastePendingRef.current ||
          isFromPaste ||
          (input.length > 1 && (input.includes('\n') || input.includes('\r')))
        )

      if (shouldHandleAsPaste) {
        pastePendingRef.current = true
        setPasteState(({ chunks, timeoutId }) => ({
          chunks: (() => {
            const nextChunks = [...chunks, input]
            pasteChunksRef.current = nextChunks
            return nextChunks
          })(),
          timeoutId: (() => {
            const nextTimeoutId = resetPasteTimeout(timeoutId)
            pasteTimeoutRef.current = nextTimeoutId
            return nextTimeoutId
          })(),
        }))
        return
      }

      if (pastePendingRef.current && key.return) {
        flushPaste()
      }

      if (isPastingRef.current && key.return) {
        return
      }

      onInput(input, key)
      if (input.length > 10) {
        setIsPasting(false)
      }
    },
    [flushPaste, onInput, onPaste, resetPasteTimeout],
  )

  return {
    wrappedOnInput,
    isPasting,
  }
}
