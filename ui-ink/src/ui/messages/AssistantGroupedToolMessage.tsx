import React from 'react'
import { Box, Text } from 'ink'

import type { RenderableGroupedToolMessage } from '../../messages/types.ts'
import { uiTheme } from '../theme.ts'
import { IndentedText, summarizeToolResult, truncateText } from './shared.tsx'

export function AssistantGroupedToolMessage({
  block,
}: {
  block: RenderableGroupedToolMessage
}): React.ReactElement {
  const status = statusFor(block.status, block.permissionPending)
  const commandLines = block.commands.length > 0 ? block.commands : compactToolLines(block.command, block.detail)
  const resultPreview = block.resultTexts.length > 0
    ? summarizeToolResult(block.resultTexts.join('\n'), block.commands[0] ?? block.command)
    : block.resultText
      ? summarizeToolResult(block.resultText, block.command)
      : ''
  const countSuffix = block.toolIds.length > 1 ? ` (${block.toolIds.length})` : ''
  return (
    <Box flexDirection="column">
      <Text>
        <Text color={status.color} dimColor={status.dimColor} bold={status.bold}>
          {status.icon}{' '}
        </Text>
        <Text bold>
          {block.toolName}
          {countSuffix}
        </Text>
        <Text color={status.color} dimColor={status.dimColor} bold={status.bold}>
          {status.suffix}
        </Text>
      </Text>
      {commandLines.slice(0, 3).map((line, index) => (
        <Text key={`${block.id}-cmd-${index}`}>
          {index === 0 ? '  └ ' : '    '}
          {truncateText(line, 88)}
        </Text>
      ))}
      {commandLines.length > 3 ? (
        <IndentedText
          text={`… ${commandLines.length - 3} more`}
          paddingLeft={4}
        />
      ) : null}
      {resultPreview ? (
        <IndentedText
          text={resultPreview}
          paddingLeft={4}
        />
      ) : null}
    </Box>
  )
}

function statusFor(
  status: RenderableGroupedToolMessage['status'],
  permissionPending: boolean,
): {
  icon: string
  color?: 'yellow' | 'green' | 'red' | 'cyan' | 'gray'
  suffix: string
  dimColor: boolean
  bold: boolean
} {
  if (permissionPending) {
    return { icon: '◌', color: 'cyan', suffix: ' awaiting approval', dimColor: true, bold: false }
  }

  switch (status) {
    case 'completed':
      return { icon: '●', color: undefined, suffix: ' completed', dimColor: false, bold: true }
    case 'failed':
      return { icon: '●', color: 'red', suffix: ' failed', dimColor: false, bold: false }
    case 'running':
      return { icon: '●', color: uiTheme.brandMuted, suffix: ' running', dimColor: true, bold: false }
    default:
      return { icon: '○', color: uiTheme.brandMuted, suffix: '', dimColor: true, bold: false }
  }
}

function compactToolLines(...values: Array<string | null | undefined>): string[] {
  const ordered: string[] = []
  const seen = new Set<string>()
  for (const value of values) {
    if (value == null || !value.trim()) {
      continue
    }
    const normalized = value.trim()
    if (seen.has(normalized)) {
      continue
    }
    seen.add(normalized)
    ordered.push(normalized)
  }
  return ordered
}
