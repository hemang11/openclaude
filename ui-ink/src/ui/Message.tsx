import React from 'react'
import { Box, Text } from 'ink'

import type { RenderableAssistantMessage, RenderableMessage } from '../messages/types.ts'
import { AssistantCollapsedReadSearchMessage } from './messages/AssistantCollapsedReadSearchMessage.tsx'
import { AssistantTextMessage } from './messages/AssistantTextMessage.tsx'
import { AssistantThinkingMessage } from './messages/AssistantThinkingMessage.tsx'
import { AssistantGroupedToolMessage } from './messages/AssistantGroupedToolMessage.tsx'
import { StatusMessage } from './messages/StatusMessage.tsx'
import { UserTextMessage } from './messages/UserTextMessage.tsx'

export function Message({
  message,
  showReasoning,
}: {
  message: RenderableMessage
  showReasoning: boolean
}): React.ReactElement | null {
  switch (message.type) {
    case 'assistant':
      return <AssistantMessage message={message} showReasoning={showReasoning} />
    case 'grouped_tool':
      return <AssistantGroupedToolMessage block={message} />
    case 'collapsed_read_search':
      return <AssistantCollapsedReadSearchMessage block={message} />
    case 'user':
      return <UserTextMessage text={message.text} />
    case 'system':
    case 'progress':
    case 'attachment':
    case 'compact_boundary':
    case 'compact_summary':
    case 'tombstone':
      return (
        <StatusMessage
          kind={message.type}
          text={message.text}
          attachmentKind={message.attachmentKind}
          hookEvent={message.hookEvent}
          source={message.source}
          reason={message.reason}
        />
      )
    default:
      return null
  }
}

function AssistantMessage({
  message,
  showReasoning,
}: {
  message: RenderableAssistantMessage
  showReasoning: boolean
}): React.ReactElement {
  return (
    <Box flexDirection="column">
      {message.blocks.map((block) => {
        switch (block.type) {
          case 'thinking':
            return (
              <AssistantThinkingMessage
                key={block.id}
                text={block.text}
                showReasoning={showReasoning}
                live={block.live}
              />
            )
          case 'text':
            return <AssistantTextMessage key={block.id} text={block.text} live={block.live} />
        }
      })}
      {message.live && message.blocks.length === 0 ? <Text dimColor>∴ Thinking…</Text> : null}
    </Box>
  )
}
