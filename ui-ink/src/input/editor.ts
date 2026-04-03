export type EditorKey = {
  ctrl?: boolean
  meta?: boolean
  shift?: boolean
  pasted?: boolean
  return?: boolean
  tab?: boolean
  escape?: boolean
  backspace?: boolean
  delete?: boolean
  upArrow?: boolean
  downArrow?: boolean
  leftArrow?: boolean
  rightArrow?: boolean
  home?: boolean
  end?: boolean
}

export type EditorState = {
  value: string
  cursorOffset: number
}

export type WrappedLayout = {
  lines: string[]
  positions: Array<{ row: number; column: number }>
  cursorRow: number
  cursorColumn: number
}

export type EditorTransition = {
  state: EditorState
  action: 'none' | 'submit' | 'historyUp' | 'historyDown'
}

type Options = {
  multiline: boolean
  columns: number
}

const KILL_RING_MAX_SIZE = 10

let killRing: string[] = []
let killRingIndex = 0
let lastActionWasKill = false
let lastActionWasYank = false
let lastYankStart = 0
let lastYankLength = 0

export function resetEditorForTests(): void {
  killRing = []
  killRingIndex = 0
  lastActionWasKill = false
  lastActionWasYank = false
  lastYankStart = 0
  lastYankLength = 0
}

export function applyKeypress(
  state: EditorState,
  input: string,
  key: EditorKey,
  options: Options,
): EditorTransition {
  const columns = normalizeColumns(options.columns)

  if (!isKillKey(key, input)) {
    resetKillAccumulation()
  }
  if (!isYankKey(key, input)) {
    resetYankState()
  }

  if (key.pasted) {
    return nextState(insertText(state, normalizePastedInput(input)))
  }

  if (isBackspaceInput(input)) {
    if (key.ctrl || key.meta) {
      return nextState(killWordBackward(state))
    }
    if (state.cursorOffset === 0) {
      return unchanged(state)
    }
    return nextState(replaceRange(state, state.cursorOffset - 1, state.cursorOffset, ''))
  }

  if (isDeleteInput(input)) {
    if (key.meta) {
      return nextState(killToLineEnd(state, columns))
    }
    if (state.cursorOffset >= state.value.length) {
      return unchanged(state)
    }
    return nextState(replaceRange(state, state.cursorOffset, state.cursorOffset + 1, ''))
  }

  if (isReturnInput(input)) {
    if (options.multiline && state.cursorOffset > 0 && state.value[state.cursorOffset - 1] === '\\') {
      return nextState(replaceRange(state, state.cursorOffset - 1, state.cursorOffset, '\n'))
    }
    return { state, action: 'submit' }
  }

  if (key.tab || key.escape) {
    return unchanged(state)
  }

  if (key.return) {
    if (options.multiline && state.cursorOffset > 0 && state.value[state.cursorOffset - 1] === '\\') {
      return nextState(replaceRange(state, state.cursorOffset - 1, state.cursorOffset, '\n'))
    }
    if (options.multiline && (key.shift || key.meta)) {
      return nextState(insertText(state, '\n'))
    }
    return { state, action: 'submit' }
  }

  if (key.upArrow) {
    const nextCursor = moveCursorUp(state.value, state.cursorOffset, columns, options.multiline)
    if (nextCursor === state.cursorOffset) {
      return { state, action: 'historyUp' }
    }
    return nextState({ ...state, cursorOffset: nextCursor })
  }

  if (key.downArrow) {
    const nextCursor = moveCursorDown(state.value, state.cursorOffset, columns, options.multiline)
    if (nextCursor === state.cursorOffset) {
      return { state, action: 'historyDown' }
    }
    return nextState({ ...state, cursorOffset: nextCursor })
  }

  if (key.leftArrow) {
    if (key.ctrl || key.meta) {
      return nextState({ ...state, cursorOffset: moveToPreviousWord(state.value, state.cursorOffset) })
    }
    return nextState({ ...state, cursorOffset: Math.max(0, state.cursorOffset - 1) })
  }

  if (key.rightArrow) {
    if (key.ctrl || key.meta) {
      return nextState({ ...state, cursorOffset: moveToNextWord(state.value, state.cursorOffset) })
    }
    return nextState({ ...state, cursorOffset: Math.min(state.value.length, state.cursorOffset + 1) })
  }

  if (key.home) {
    return nextState({ ...state, cursorOffset: wrappedLineStart(state.value, state.cursorOffset, columns) })
  }

  if (key.end) {
    return nextState({ ...state, cursorOffset: wrappedLineEnd(state.value, state.cursorOffset, columns) })
  }

  if (key.backspace) {
    if (key.ctrl || key.meta) {
      return nextState(killWordBackward(state))
    }
    if (state.cursorOffset === 0) {
      return unchanged(state)
    }
    return nextState(replaceRange(state, state.cursorOffset - 1, state.cursorOffset, ''))
  }

  if (key.delete) {
    if (key.meta) {
      return nextState(killToLineEnd(state, columns))
    }
    if (key.ctrl && state.value.length === 0) {
      return unchanged(state)
    }
    if (state.cursorOffset >= state.value.length) {
      return unchanged(state)
    }
    return nextState(replaceRange(state, state.cursorOffset, state.cursorOffset + 1, ''))
  }

  if (key.ctrl) {
    switch (input) {
      case 'a':
        return nextState({ ...state, cursorOffset: wrappedLineStart(state.value, state.cursorOffset, columns) })
      case 'b':
        return nextState({ ...state, cursorOffset: Math.max(0, state.cursorOffset - 1) })
      case 'd':
        if (state.value.length === 0) {
          return unchanged(state)
        }
        if (state.cursorOffset >= state.value.length) {
          return unchanged(state)
        }
        return nextState(replaceRange(state, state.cursorOffset, state.cursorOffset + 1, ''))
      case 'e':
        return nextState({ ...state, cursorOffset: wrappedLineEnd(state.value, state.cursorOffset, columns) })
      case 'f':
        return nextState({ ...state, cursorOffset: Math.min(state.value.length, state.cursorOffset + 1) })
      case 'h':
        if (state.cursorOffset === 0) {
          return unchanged(state)
        }
        return nextState(replaceRange(state, state.cursorOffset - 1, state.cursorOffset, ''))
      case 'k':
        return nextState(killToLineEnd(state, columns))
      case 'u':
        return nextState(killToLineStart(state, columns))
      case 'w':
        return nextState(killWordBackward(state))
      case 'y':
        return nextState(yank(state))
      default:
        return unchanged(state)
    }
  }

  if (key.meta) {
    switch (input) {
      case 'b':
        return nextState({ ...state, cursorOffset: moveToPreviousWord(state.value, state.cursorOffset) })
      case 'f':
        return nextState({ ...state, cursorOffset: moveToNextWord(state.value, state.cursorOffset) })
      case 'd':
        return nextState(deleteWordForward(state))
      case 'y':
        return nextState(yankPop(state))
      default:
        return unchanged(state)
    }
  }

  const inserted = normalizeInsertedInput(input)
  if (!inserted) {
    return unchanged(state)
  }

  return nextState(insertText(state, inserted))
}

export function applyInputSequence(
  state: EditorState,
  input: string,
  key: EditorKey,
  options: Options,
): EditorTransition {
  if (!input || input.length <= 1 || hasExplicitKeyFlags(key)) {
    return applyKeypress(state, input, key, options)
  }

  let next = state
  let action: EditorTransition['action'] = 'none'
  for (const char of [...input]) {
    const transition = applyKeypress(next, char, {}, options)
    next = transition.state
    if (transition.action !== 'none') {
      action = transition.action
      if (transition.action === 'submit' || transition.action === 'historyUp' || transition.action === 'historyDown') {
        break
      }
    }
  }

  return { state: next, action }
}

export function computeWrappedLayout(
  value: string,
  cursorOffset: number,
  columns: number,
): WrappedLayout {
  const width = normalizeColumns(columns)
  const lines = ['']
  const positions: Array<{ row: number; column: number }> = new Array(value.length + 1)
  let row = 0
  let column = 0
  positions[0] = { row, column }

  for (let index = 0; index < value.length; index += 1) {
    const char = value[index]!
    if (char === '\n') {
      row += 1
      column = 0
      lines[row] = ''
      positions[index + 1] = { row, column }
      continue
    }

    if (column >= width) {
      row += 1
      column = 0
      lines[row] = ''
      positions[index] = { row, column }
    }

    lines[row] += char
    column += 1
    positions[index + 1] = { row, column }
  }

  const safeCursor = clamp(cursorOffset, 0, value.length)
  const cursor = positions[safeCursor] ?? { row, column }

  return {
    lines,
    positions,
    cursorRow: cursor.row,
    cursorColumn: cursor.column,
  }
}

function moveVertical(
  value: string,
  cursorOffset: number,
  columns: number,
  rowDelta: -1 | 1,
): number {
  const layout = computeWrappedLayout(value, cursorOffset, columns)
  const targetRow = layout.cursorRow + rowDelta
  if (targetRow < 0) {
    return cursorOffset
  }

  let foundOnTargetRow = false
  let bestOffset = cursorOffset
  let bestColumnDelta = Number.POSITIVE_INFINITY
  let bestColumn = -1

  for (let offset = 0; offset < layout.positions.length; offset += 1) {
    const position = layout.positions[offset]!
    if (position.row !== targetRow) {
      continue
    }
    foundOnTargetRow = true
    const columnDelta = Math.abs(position.column - layout.cursorColumn)
    if (
      columnDelta < bestColumnDelta ||
      (columnDelta === bestColumnDelta && position.column > bestColumn)
    ) {
      bestOffset = offset
      bestColumnDelta = columnDelta
      bestColumn = position.column
    }
  }

  return foundOnTargetRow ? bestOffset : cursorOffset
}

function moveCursorUp(
  value: string,
  cursorOffset: number,
  columns: number,
  multiline: boolean,
): number {
  const wrapped = moveVertical(value, cursorOffset, columns, -1)
  if (wrapped !== cursorOffset) {
    return wrapped
  }
  if (!multiline) {
    return cursorOffset
  }
  return moveLogicalVertical(value, cursorOffset, -1)
}

function moveCursorDown(
  value: string,
  cursorOffset: number,
  columns: number,
  multiline: boolean,
): number {
  const wrapped = moveVertical(value, cursorOffset, columns, 1)
  if (wrapped !== cursorOffset) {
    return wrapped
  }
  if (!multiline) {
    return cursorOffset
  }
  return moveLogicalVertical(value, cursorOffset, 1)
}

function moveLogicalVertical(
  value: string,
  cursorOffset: number,
  rowDelta: -1 | 1,
): number {
  const currentStart = logicalLineStart(value, cursorOffset)
  const currentEnd = logicalLineEnd(value, cursorOffset)
  const currentColumn = clamp(cursorOffset, currentStart, currentEnd) - currentStart

  if (rowDelta < 0) {
    if (currentStart === 0) {
      return 0
    }
    const previousEnd = currentStart - 1
    const previousStart = logicalLineStart(value, previousEnd)
    return clamp(previousStart + currentColumn, previousStart, previousEnd)
  }

  if (currentEnd >= value.length) {
    return value.length
  }
  const nextStart = currentEnd + 1
  const nextEnd = logicalLineEnd(value, nextStart)
  return clamp(nextStart + currentColumn, nextStart, nextEnd)
}

function insertText(state: EditorState, text: string): EditorState {
  return replaceRange(state, state.cursorOffset, state.cursorOffset, text)
}

function isBackspaceInput(input: string): boolean {
  return input === '\u007f' || input === '\b'
}

function isDeleteInput(input: string): boolean {
  return input === '\u001b[3~'
}

function isReturnInput(input: string): boolean {
  return input === '\r' || input === '\n'
}

function replaceRange(state: EditorState, start: number, end: number, replacement: string): EditorState {
  const safeStart = clamp(start, 0, state.value.length)
  const safeEnd = clamp(end, safeStart, state.value.length)
  return {
    value: `${state.value.slice(0, safeStart)}${replacement}${state.value.slice(safeEnd)}`,
    cursorOffset: safeStart + replacement.length,
  }
}

function deleteWordBackward(state: EditorState): EditorState {
  const start = moveToPreviousWord(state.value, state.cursorOffset)
  return replaceRange(state, start, state.cursorOffset, '')
}

function deleteWordForward(state: EditorState): EditorState {
  const end = moveToNextWord(state.value, state.cursorOffset)
  return replaceRange(state, state.cursorOffset, end, '')
}

function moveToPreviousWord(value: string, cursorOffset: number): number {
  let offset = clamp(cursorOffset, 0, value.length)
  while (offset > 0 && isWordBoundary(value[offset - 1]!)) {
    offset -= 1
  }
  while (offset > 0 && !isWordBoundary(value[offset - 1]!)) {
    offset -= 1
  }
  return offset
}

function moveToNextWord(value: string, cursorOffset: number): number {
  let offset = clamp(cursorOffset, 0, value.length)
  while (offset < value.length && !isWordBoundary(value[offset]!)) {
    offset += 1
  }
  while (offset < value.length && isWordBoundary(value[offset]!)) {
    offset += 1
  }
  return offset
}

function lineStart(value: string, cursorOffset: number): number {
  const lastNewline = value.lastIndexOf('\n', clamp(cursorOffset, 0, value.length) - 1)
  return lastNewline >= 0 ? lastNewline + 1 : 0
}

function lineEnd(value: string, cursorOffset: number): number {
  const nextNewline = value.indexOf('\n', clamp(cursorOffset, 0, value.length))
  return nextNewline >= 0 ? nextNewline : value.length
}

function logicalLineStart(value: string, cursorOffset: number): number {
  return lineStart(value, cursorOffset)
}

function logicalLineEnd(value: string, cursorOffset: number): number {
  return lineEnd(value, cursorOffset)
}

function wrappedLineStart(value: string, cursorOffset: number, columns: number): number {
  const layout = computeWrappedLayout(value, cursorOffset, columns)
  const position = layout.positions[clamp(cursorOffset, 0, value.length)] ?? { row: 0, column: 0 }
  const targetRow = position.column === 0 && position.row > 0 ? position.row - 1 : position.row
  return wrappedLineStartRaw(layout, targetRow)
}

function wrappedLineStartRaw(layout: WrappedLayout, row: number): number {
  for (let offset = 0; offset < layout.positions.length; offset += 1) {
    if (layout.positions[offset]?.row === row) {
      return offset
    }
  }
  return 0
}

function wrappedLineEnd(value: string, cursorOffset: number, columns: number): number {
  const layout = computeWrappedLayout(value, cursorOffset, columns)
  const position = layout.positions[clamp(cursorOffset, 0, value.length)] ?? { row: 0, column: 0 }
  return wrappedLineEndRaw(layout, position.row)
}

function wrappedLineEndRaw(layout: WrappedLayout, row: number): number {
  let lastOffset = 0
  for (let offset = 0; offset < layout.positions.length; offset += 1) {
    if (layout.positions[offset]?.row === row) {
      lastOffset = offset
    }
  }
  return lastOffset
}

function killToLineEnd(state: EditorState, columns: number): EditorState {
  const end = wrappedLineEnd(state.value, state.cursorOffset, columns)
  if (end <= state.cursorOffset) {
    return state
  }
  pushToKillRing(state.value.slice(state.cursorOffset, end), 'append')
  return replaceRange(state, state.cursorOffset, end, '')
}

function killToLineStart(state: EditorState, columns: number): EditorState {
  const layout = computeWrappedLayout(state.value, state.cursorOffset, columns)
  const position = layout.positions[clamp(state.cursorOffset, 0, state.value.length)] ?? { row: 0, column: 0 }
  const start = wrappedLineStartRaw(layout, position.row)
  if (start >= state.cursorOffset) {
    return state
  }
  pushToKillRing(state.value.slice(start, state.cursorOffset), 'prepend')
  return replaceRange(state, start, state.cursorOffset, '')
}

function killWordBackward(state: EditorState): EditorState {
  const start = moveToPreviousWord(state.value, state.cursorOffset)
  if (start >= state.cursorOffset) {
    return state
  }
  pushToKillRing(state.value.slice(start, state.cursorOffset), 'prepend')
  return replaceRange(state, start, state.cursorOffset, '')
}

function yank(state: EditorState): EditorState {
  const text = getLastKill()
  if (!text) {
    return state
  }
  const next = insertText(state, text)
  recordYank(state.cursorOffset, text.length)
  return next
}

function yankPop(state: EditorState): EditorState {
  const popResult = getNextYank()
  if (!popResult) {
    return state
  }
  const { text, start, length } = popResult
  const next = replaceRange(state, start, start + length, text)
  updateYankLength(text.length)
  return { value: next.value, cursorOffset: start + text.length }
}

function normalizeInsertedInput(input: string): string {
  if (!input) {
    return ''
  }
  return input
    .replace(/\u001b\[[0-9;]*[A-Za-z~]/g, '')
    .replace(/[\u0000-\u001f\u007f]/g, '')
}

function normalizePastedInput(input: string): string {
  if (!input) {
    return ''
  }
  return input.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

function normalizeColumns(columns: number): number {
  return Math.max(8, columns)
}

function isWordBoundary(char: string): boolean {
  return /\s|[()[\]{}.,:;'"`/\\<>!?+=*\-]/.test(char)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function nextState(state: EditorState): EditorTransition {
  return { state, action: 'none' }
}

function unchanged(state: EditorState): EditorTransition {
  return { state, action: 'none' }
}

function hasExplicitKeyFlags(key: EditorKey): boolean {
  return Boolean(
    key.ctrl ||
      key.meta ||
      key.shift ||
      key.pasted ||
      key.return ||
      key.tab ||
      key.escape ||
      key.backspace ||
      key.delete ||
      key.upArrow ||
      key.downArrow ||
      key.leftArrow ||
      key.rightArrow ||
      key.home ||
      key.end,
  )
}

function isKillKey(key: EditorKey, input: string): boolean {
  if (key.ctrl && (input === 'k' || input === 'u' || input === 'w')) {
    return true
  }
  return Boolean(key.meta && (key.backspace || key.delete))
}

function isYankKey(key: EditorKey, input: string): boolean {
  return Boolean((key.ctrl || key.meta) && input === 'y')
}

function pushToKillRing(text: string, direction: 'prepend' | 'append'): void {
  if (!text) {
    return
  }
  if (lastActionWasKill && killRing.length > 0) {
    killRing[0] = direction === 'prepend' ? text + killRing[0] : killRing[0] + text
  } else {
    killRing.unshift(text)
    if (killRing.length > KILL_RING_MAX_SIZE) {
      killRing.pop()
    }
  }
  lastActionWasKill = true
  lastActionWasYank = false
}

function getLastKill(): string {
  return killRing[0] ?? ''
}

function recordYank(start: number, length: number): void {
  lastYankStart = start
  lastYankLength = length
  lastActionWasYank = true
  killRingIndex = 0
}

function getNextYank(): { text: string; start: number; length: number } | null {
  if (!lastActionWasYank || killRing.length <= 1) {
    return null
  }
  killRingIndex = (killRingIndex + 1) % killRing.length
  return {
    text: killRing[killRingIndex] ?? '',
    start: lastYankStart,
    length: lastYankLength,
  }
}

function updateYankLength(length: number): void {
  lastYankLength = length
}

function resetKillAccumulation(): void {
  lastActionWasKill = false
}

function resetYankState(): void {
  lastActionWasYank = false
}
