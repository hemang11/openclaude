import React from 'react'
import { Box, Text } from 'ink'

import type { RenderableCollapsedReadSearchMessage } from '../../messages/types.ts'
import { uiTheme } from '../theme.ts'
import { IndentedText, truncateText } from './shared.tsx'

export function AssistantCollapsedReadSearchMessage({
  block,
}: {
  block: RenderableCollapsedReadSearchMessage
}): React.ReactElement {
  const status = statusFor(block.status)
  const countSuffix = block.toolIds.length > 1 ? ` (${block.toolIds.length})` : ''
  return (
    <Box flexDirection="column">
      <Text>
        <Text color={status.color} dimColor={status.dimColor} bold={status.bold}>
          {status.icon}{' '}
        </Text>
        <Text bold>
          {block.label}
          {countSuffix}
        </Text>
        <Text color={status.color} dimColor={status.dimColor} bold={status.bold}>
          {status.suffix}
        </Text>
      </Text>
      <Text>
        {'  └ '}
        {block.summary}
      </Text>
      {block.commands.slice(0, 3).map((command) => (
        <Text key={command}>
          {'    '}
          {truncateText(command, 88)}
        </Text>
      ))}
      {block.commands.length > 3 ? (
        <IndentedText text={`… ${block.commands.length - 3} more`} paddingLeft={4} />
      ) : null}
    </Box>
  )
}

function statusFor(status: RenderableCollapsedReadSearchMessage['status']): {
  icon: string
  color?: 'yellow' | 'green' | 'red' | 'cyan' | 'gray'
  suffix: string
  dimColor: boolean
  bold: boolean
} {
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
