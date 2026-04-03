import type { InputKey } from '../input/useTerminalInput.ts'
import type { PromptInputMode } from './inputModes.ts'

export type PromptKeybindingContext = {
  busy: boolean
  historySearchActive: boolean
  inputMode: PromptInputMode
  inputValue: string
  cursorOffset: number
  suggestionsVisible: boolean
  suggestionsDismissed: boolean
  inlineGhostTextVisible: boolean
  suggestionSelected: boolean
}

export type PromptAction =
  | 'cancelHistorySearch'
  | 'nextHistorySearchMatch'
  | 'acceptHistorySearch'
  | 'executeHistorySearch'
  | 'dismissSuggestions'
  | 'selectPreviousSuggestion'
  | 'selectNextSuggestion'
  | 'acceptSuggestion'
  | 'executeSuggestion'
  | 'openModels'
  | 'toggleFastMode'
  | 'toggleVerboseOutput'
  | 'toggleTasks'
  | 'toggleStash'
  | 'openExternalEditor'
  | 'startHistorySearch'
  | 'suspendApp'
  | 'enterBashMode'
  | 'exitInputMode'
  | 'cancelActivePrompt'

export function resolvePromptAction(
  inputValue: string,
  key: InputKey,
  context: PromptKeybindingContext,
): PromptAction | null {
  const rawEnter = inputValue === '\r' || inputValue === '\n'

  if (key.ctrl && inputValue === 'z') {
    return 'suspendApp'
  }

  if (context.historySearchActive) {
    if (key.ctrl && inputValue === 'r') {
      return 'nextHistorySearchMatch'
    }
    if (key.escape || key.tab) {
      return 'acceptHistorySearch'
    }
    if (key.return || rawEnter) {
      return 'executeHistorySearch'
    }
    if ((key.backspace || key.delete) && context.inputValue.length === 0) {
      return 'cancelHistorySearch'
    }
    return null
  }

  if (context.busy && key.ctrl && inputValue === 'c') {
    return 'cancelActivePrompt'
  }

  if (!context.busy && !context.suggestionsDismissed && key.escape && context.suggestionsVisible) {
    return 'dismissSuggestions'
  }

  if (!context.busy && !context.suggestionsDismissed && hasSuggestionNavigationKey(key, inputValue) && context.suggestionsVisible) {
    return isReverseSuggestionNavigation(key, inputValue)
      ? 'selectPreviousSuggestion'
      : 'selectNextSuggestion'
  }

  if (!context.busy && !context.suggestionsDismissed && key.rightArrow && context.inlineGhostTextVisible && context.suggestionSelected) {
    return 'acceptSuggestion'
  }

  if (!context.busy && !context.suggestionsDismissed && key.tab && context.suggestionSelected) {
    return 'acceptSuggestion'
  }

  if (
    !context.busy
    && !context.suggestionsDismissed
    && (key.return || rawEnter)
    && !key.shift
    && !key.meta
    && context.suggestionSelected
  ) {
    return 'executeSuggestion'
  }

  if (key.meta && inputValue === 'p') {
    return 'openModels'
  }
  if (key.meta && inputValue === 'o') {
    return 'toggleFastMode'
  }
  if (key.ctrl && inputValue === 'o') {
    return 'toggleVerboseOutput'
  }
  if (key.ctrl && inputValue === 't') {
    return 'toggleTasks'
  }
  if (key.ctrl && inputValue === 's') {
    return 'toggleStash'
  }
  if (key.ctrl && inputValue === 'g') {
    return 'openExternalEditor'
  }
  if (key.ctrl && inputValue === 'r') {
    return 'startHistorySearch'
  }

  if (!key.ctrl && !key.meta && context.cursorOffset === 0 && inputValue === '!') {
    return 'enterBashMode'
  }

  if (
    context.inputMode !== 'prompt'
    && context.cursorOffset === 0
    && (
      key.escape
      || key.backspace
      || key.delete
      || (key.ctrl && inputValue === 'u')
    )
  ) {
    return 'exitInputMode'
  }

  return null
}

export function promptFooterShortcutLines(args: {
  historySearchActive: boolean
  inputMode: PromptInputMode
}): string[] {
  if (args.historySearchActive) {
    return [
      'ctrl+r older match  tab/esc keep  enter use  ctrl+c cancel',
      '↑/↓ move cursor  backspace on empty cancel  ctrl+z suspend',
    ]
  }

  const modeHint = args.inputMode === 'bash'
    ? '! shell mode  esc/backspace at column 0 exit mode  ↑/↓ bash history'
    : '/ commands  @ files  tab complete  ↑/↓ or ctrl+n/ctrl+p history  ctrl+r search'

  return [
    modeHint,
    'Enter submit  shift/meta+enter newline  ctrl+s stash  ctrl+g editor  meta+p models  meta+o fast',
  ]
}

export function hasSuggestionNavigationKey(key: InputKey, inputValue: string): boolean {
  return Boolean(key.upArrow || key.downArrow || (key.ctrl && (inputValue === 'n' || inputValue === 'p')))
}

export function isReverseSuggestionNavigation(key: InputKey, inputValue: string): boolean {
  return Boolean(key.upArrow || (key.ctrl && inputValue === 'p'))
}

export function nextSuggestionIndex(current: number, total: number, reverse: boolean): number {
  if (total <= 0) {
    return 0
  }
  if (reverse) {
    return current <= 0 ? total - 1 : current - 1
  }
  return current >= total - 1 ? 0 : current + 1
}
