import React from 'react'
import { Box, Text } from 'ink'

export function IndentedText({
  text,
  dimColor = false,
  paddingLeft = 2,
}: {
  text: string
  dimColor?: boolean
  paddingLeft?: number
}): React.ReactElement {
  const lines = text.split(/\r?\n/)
  return (
    <Box flexDirection="column" paddingLeft={paddingLeft}>
      {lines.map((line, index) => (
        <Text key={`${index}-${line.slice(0, 16)}`} dimColor={dimColor}>
          {line || ' '}
        </Text>
      ))}
    </Box>
  )
}

export function truncateText(text: string, maxLength = 220): string {
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (normalized.length <= maxLength) {
    return normalized
  }
  return `${normalized.slice(0, Math.max(0, maxLength - 1))}…`
}

export function truncateMultilineText(
  text: string,
  options: {
    maxLines?: number
    maxLineLength?: number
  } = {},
): string {
  const maxLines = options.maxLines ?? 3
  const maxLineLength = options.maxLineLength ?? 120
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => line.trim().length > 0)

  if (lines.length === 0) {
    return ''
  }

  const clipped = lines.slice(0, maxLines).map((line) => truncateText(line, maxLineLength))
  if (lines.length > maxLines) {
    clipped.push('…')
  }
  return clipped.join('\n')
}

export function summarizeToolResult(
  resultText: string,
  command?: string | null,
): string {
  const lines = resultText
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => line.trim().length > 0)

  if (lines.length === 0) {
    return ''
  }

  const summarized: string[] = []
  for (const line of lines) {
    if (command && line === `Command: ${command}`) {
      continue
    }
    if (line === 'Exit code: 0') {
      continue
    }
    summarized.push(line)
  }

  const source = summarized.length > 0 ? summarized.join('\n') : lines.join('\n')
  return truncateMultilineText(source, { maxLines: 3, maxLineLength: 120 })
}
