import React from 'react'
import { Box, Text } from 'ink'

import type { PromptSuggestionItem, PromptSuggestionState } from '../prompt/suggestions.ts'
import { uiTheme } from './theme.ts'

export function PromptSuggestionOverlay({
  state,
  selectedIndex,
}: {
  state: PromptSuggestionState
  selectedIndex: number
}): React.ReactElement | null {
  if (!state.kind || state.items.length === 0) {
    return null
  }

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1} marginBottom={1}>
      <Text bold color={uiTheme.overlayTitle}>
        {state.kind === 'command' ? 'Commands' : 'File Paths'}
      </Text>
      <Text dimColor>
        {state.kind === 'command'
          ? 'Tab/Enter to accept, Esc dismisses, ↑/↓ or Ctrl+P/Ctrl+N to move'
          : 'Tab/Enter to complete, Esc dismisses, ↑/↓ or Ctrl+P/Ctrl+N to move'}
      </Text>
      <Box height={1} />
      {state.items.map((item, index) => (
        <SuggestionRow
          key={item.key}
          item={item}
          selected={index === selectedIndex}
        />
      ))}
    </Box>
  )
}

function SuggestionRow({
  item,
  selected,
}: {
  item: PromptSuggestionItem
  selected: boolean
}): React.ReactElement {
  return (
    <Box>
      <Text color={selected ? uiTheme.overlaySelection : undefined}>
        {selected ? '› ' : '  '}
      </Text>
      <Text inverse={selected}>
        {item.label}
      </Text>
      {item.detail ? (
        <>
          <Text> </Text>
          <Text dimColor>{item.detail}</Text>
        </>
      ) : null}
    </Box>
  )
}
