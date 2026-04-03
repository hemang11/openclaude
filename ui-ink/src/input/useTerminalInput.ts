import { useEffect, useRef } from 'react'
import { useStdin } from 'ink'

import { INITIAL_STATE, parseMultipleKeypresses, type KeyParseState } from './claude-ink/parse-keypress.ts'
import { toTerminalInput } from './claude-ink/inputAdapter.ts'

export type InputKey = {
  ctrl?: boolean
  meta?: boolean
  shift?: boolean
  pasted?: boolean
  tab?: boolean
  return?: boolean
  escape?: boolean
  backspace?: boolean
  delete?: boolean
  upArrow?: boolean
  downArrow?: boolean
  leftArrow?: boolean
  rightArrow?: boolean
  home?: boolean
  end?: boolean
  pageUp?: boolean
  pageDown?: boolean
}

export function useTerminalInput(
  inputHandler: (input: string, key: InputKey) => void,
  options: { isActive?: boolean } = {},
): void {
  const { stdin, setRawMode, isRawModeSupported } = useStdin()
  const inputHandlerRef = useRef(inputHandler)
  const parseStateRef = useRef<KeyParseState>(INITIAL_STATE)

  useEffect(() => {
    inputHandlerRef.current = inputHandler
  }, [inputHandler])

  useEffect(() => {
    if (options.isActive === false || !isRawModeSupported) {
      return
    }

    setRawMode(true)
    return () => {
      setRawMode(false)
    }
  }, [isRawModeSupported, options.isActive, setRawMode])

  useEffect(() => {
    if (options.isActive === false) {
      return
    }

    const handleData = (chunk: Buffer | string) => {
      const [parsedInputs, nextState] = parseMultipleKeypresses(parseStateRef.current, chunk)
      parseStateRef.current = nextState

      for (const parsedInput of parsedInputs) {
        const terminalInput = toTerminalInput(parsedInput)
        if (!terminalInput) {
          continue
        }
        inputHandlerRef.current(terminalInput.input, terminalInput.key)
      }
    }

    stdin.on('data', handleData)
    return () => {
      stdin.off('data', handleData)
      parseStateRef.current = INITIAL_STATE
    }
  }, [options.isActive, stdin])
}
