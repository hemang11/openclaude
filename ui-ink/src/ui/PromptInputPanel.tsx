import React from 'react'
import { Box, Text } from 'ink'

import type { InputKey } from '../input/useTerminalInput.ts'
import type { PromptSuggestionState } from '../prompt/suggestions.ts'
import type { PromptInputMode } from '../prompt/inputModes.ts'
import type { FooterLine } from './PromptFooter.tsx'
import { PromptFooter } from './PromptFooter.tsx'
import { PromptInputModeIndicator } from './PromptInputModeIndicator.tsx'
import { PromptSuggestionOverlay } from './PromptSuggestionOverlay.tsx'
import { TextInput } from './TextInput.tsx'
import { uiTheme } from './theme.ts'

type Props = {
  overlayMessage: string | null
  historySearch: {
    query: string
    cursorOffset: number
    failed: boolean
  } | null
  promptSuggestions: PromptSuggestionState
  promptSuggestionIndex: number
  inputMode: PromptInputMode
  busy: boolean
  input: string
  cursorOffset: number
  onChange: (value: string, cursorOffset: number) => void
  onSubmit: (value: string) => void
  onHistoryUp: () => void
  onHistoryDown: () => void
  onClearInput: () => void
  onExit: () => void
  beforeInput: (input: string, key: InputKey) => boolean
  onPaste: (text: string) => void
  promptInlineGhostText: string | null
  footerLines: FooterLine[]
  stashedPrompt: boolean
}

export function PromptInputPanel({
  overlayMessage,
  historySearch,
  promptSuggestions,
  promptSuggestionIndex,
  inputMode,
  busy,
  input,
  cursorOffset,
  onChange,
  onSubmit,
  onHistoryUp,
  onHistoryDown,
  onClearInput,
  onExit,
  beforeInput,
  onPaste,
  promptInlineGhostText,
  footerLines,
  stashedPrompt,
}: Props): React.ReactElement {
  const selectedIndex = Math.min(promptSuggestionIndex, Math.max(0, promptSuggestions.items.length - 1))

  return (
    <Box flexDirection="column">
      <Box borderStyle="round" borderColor={uiTheme.promptBorder} paddingX={1} flexDirection="column">
        {!overlayMessage && !historySearch ? (
          <PromptSuggestionOverlay
            state={promptSuggestions}
            selectedIndex={selectedIndex}
          />
        ) : null}
        <Box>
          {overlayMessage ? (
            <Text dimColor>{overlayMessage}</Text>
          ) : (
            <>
              <PromptInputModeIndicator mode={inputMode} isLoading={busy} />
              <TextInput
                value={input}
                cursorOffset={cursorOffset}
                columns={Math.max(16, (process.stdout.columns ?? 80) - 10)}
                onChange={onChange}
                onSubmit={onSubmit}
                onHistoryUp={onHistoryUp}
                onHistoryDown={onHistoryDown}
                onClearInput={onClearInput}
                onExit={onExit}
                beforeInput={beforeInput}
                onPaste={onPaste}
                argumentHint={promptSuggestions.commandArgumentHint ?? undefined}
                inlineGhostText={promptInlineGhostText}
              />
            </>
          )}
        </Box>
      </Box>
      <Box marginTop={1}>
        <PromptFooter
          lines={footerLines}
          stashedPrompt={stashedPrompt}
          historySearch={historySearch}
        />
      </Box>
    </Box>
  )
}
