import React from 'react'
import { Box, Text } from 'ink'
import type { ThemeColor } from './theme.ts'

export type FooterLine = {
  key: string
  text: string
  color?: ThemeColor
  dim?: boolean
}

export function PromptFooter({
  lines,
  stashedPrompt,
  historySearch,
}: {
  lines: FooterLine[]
  stashedPrompt: boolean
  historySearch?: {
    query: string
    cursorOffset: number
    failed: boolean
  } | null
}): React.ReactElement {
  return (
    <Box flexDirection="column">
      {historySearch ? (
        <Text dimColor>
          {renderHistorySearchLine(historySearch)}
        </Text>
      ) : null}
      {lines.map((line) => (
        <Text key={line.key} color={line.color} dimColor={line.dim}>
          {line.text}
        </Text>
      ))}
      {stashedPrompt ? <Text dimColor>Stashed prompt is ready. Press ctrl+s to restore it.</Text> : null}
    </Box>
  )
}

function renderHistorySearchLine(
  historySearch: {
    query: string
    cursorOffset: number
    failed: boolean
  },
): React.ReactNode {
  const label = historySearch.failed ? 'no matching prompt:' : 'search prompts:'
  const safeCursor = Math.max(0, Math.min(historySearch.cursorOffset, historySearch.query.length))
  const before = historySearch.query.slice(0, safeCursor)
  const cursorCharacter = safeCursor < historySearch.query.length ? historySearch.query[safeCursor] : ' '
  const after = safeCursor < historySearch.query.length ? historySearch.query.slice(safeCursor + 1) : ''

  return (
    <>
      {label}
      {' '}
      {before}
      <Text inverse>{cursorCharacter}</Text>
      {after}
    </>
  )
}
