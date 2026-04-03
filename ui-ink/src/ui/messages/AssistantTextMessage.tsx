import React from 'react'
import { Box, Text } from 'ink'

import { Markdown, StreamingMarkdown } from '../Markdown.tsx'
import { uiTheme } from '../theme.ts'

export function AssistantTextMessage({ text, live = false }: { text: string; live?: boolean }): React.ReactElement {
  return (
    <Box flexDirection="row" alignItems="flex-start">
      <Text color={uiTheme.assistantMarker}>{'● '}</Text>
      <Box flexDirection="column" flexGrow={1}>
        {live ? <StreamingMarkdown text={text} /> : <Markdown text={text} />}
      </Box>
    </Box>
  )
}
