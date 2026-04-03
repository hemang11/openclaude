import { nonAlphanumericKeys, type ParsedInput, type ParsedKey } from './parse-keypress.ts'
import type { InputKey } from '../useTerminalInput.ts'

export function toTerminalInput(
  parsed: ParsedInput,
): { input: string; key: InputKey } | null {
  if (parsed.kind !== 'key') {
    return null
  }

  const [key, input] = parseKey(parsed)
  return { key, input }
}

function parseKey(keypress: ParsedKey): [InputKey, string] {
  const key: InputKey = {
    upArrow: keypress.name === 'up',
    downArrow: keypress.name === 'down',
    leftArrow: keypress.name === 'left',
    rightArrow: keypress.name === 'right',
    pageDown: keypress.name === 'pagedown',
    pageUp: keypress.name === 'pageup',
    home: keypress.name === 'home',
    end: keypress.name === 'end',
    return: keypress.name === 'return',
    escape: keypress.name === 'escape',
    ctrl: keypress.ctrl,
    shift: keypress.shift,
    tab: keypress.name === 'tab',
    backspace: keypress.name === 'backspace',
    delete: keypress.name === 'delete',
    meta: keypress.meta || keypress.name === 'escape' || keypress.option,
    pasted: keypress.isPasted,
  }

  let input = keypress.ctrl ? keypress.name : keypress.sequence

  if (input === undefined) {
    input = ''
  }

  if (keypress.ctrl && input === 'space') {
    input = ' '
  }

  if (keypress.code && !keypress.name) {
    input = ''
  }

  if (!keypress.name && /^\[<\d+;\d+;\d+[Mm]/.test(input)) {
    input = ''
  }

  if (input.startsWith('\u001B')) {
    input = input.slice(1)
  }

  let processedAsSpecialSequence = false

  if (/^\[\d/.test(input) && input.endsWith('u')) {
    if (!keypress.name) {
      input = ''
    } else {
      input =
        keypress.name === 'space'
          ? ' '
          : keypress.name === 'escape'
            ? ''
            : keypress.name
    }
    processedAsSpecialSequence = true
  }

  if (input.startsWith('[27;') && input.endsWith('~')) {
    if (!keypress.name) {
      input = ''
    } else {
      input =
        keypress.name === 'space'
          ? ' '
          : keypress.name === 'escape'
            ? ''
            : keypress.name
    }
    processedAsSpecialSequence = true
  }

  if (
    input.startsWith('O') &&
    input.length === 2 &&
    keypress.name &&
    keypress.name.length === 1
  ) {
    input = keypress.name
    processedAsSpecialSequence = true
  }

  if (
    !processedAsSpecialSequence &&
    keypress.name &&
    nonAlphanumericKeys.includes(keypress.name)
  ) {
    input = ''
  }

  if (
    input.length === 1 &&
    typeof input[0] === 'string' &&
    input[0] >= 'A' &&
    input[0] <= 'Z'
  ) {
    key.shift = true
  }

  return [key, input]
}
