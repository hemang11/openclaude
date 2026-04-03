import React from 'react'
import { Box, Text } from 'ink'

import { Markdown } from '../Markdown.tsx'
import { truncateMultilineText } from './shared.tsx'

export function AssistantThinkingMessage({
  text,
  showReasoning,
  live,
}: {
  text: string
  showReasoning: boolean
  live: boolean
}): React.ReactElement | null {
  const previewText = truncateMultilineText(text, { maxLines: live ? 2 : 3, maxLineLength: 100 })
  if (!previewText.trim()) {
    return null
  }

  if (!showReasoning) {
    return (
      <Text dimColor italic>
        {live ? '∴ Thinking…' : '∴ Thinking'}
      </Text>
    )
  }

  return (
    <Box flexDirection="column" gap={1}>
      <Text dimColor italic>
        {live ? '∴ Thinking…' : '∴ Thinking'}
      </Text>
      <Box paddingLeft={2}>
        <Markdown text={previewText} dimColor />
      </Box>
    </Box>
  )
}
