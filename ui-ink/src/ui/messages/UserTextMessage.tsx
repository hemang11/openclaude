import React from 'react'
import { Box, Text } from 'ink'

import { uiTheme } from '../theme.ts'

export function UserTextMessage({ text }: { text: string }): React.ReactElement {
  const terminalWidth = Math.max(24, (process.stdout.columns ?? 120) - 2)
  const lines = text
    .split(/\r?\n/)
    .flatMap((line) => wrapText(line || ' ', terminalWidth - 2))

  return (
    <Box flexDirection="column">
      {lines.map((line, index) => (
        <Text key={`${index}-${line.slice(0, 16)}`} backgroundColor={uiTheme.userMessageBackground}>
          {` ${line.padEnd(terminalWidth - 2, ' ')} `}
        </Text>
      ))}
    </Box>
  )
}

function wrapText(text: string, width: number): string[] {
  if (width <= 0) {
    return [text]
  }

  const wrapped: string[] = []
  let remaining = text

  while (remaining.length > width) {
    wrapped.push(remaining.slice(0, width))
    remaining = remaining.slice(width)
  }

  wrapped.push(remaining)
  return wrapped
}
