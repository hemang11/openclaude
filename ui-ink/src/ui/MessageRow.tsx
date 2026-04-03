import React from 'react'
import { Box } from 'ink'

import type { RenderableMessage } from '../messages/types.ts'
import { Message } from './Message.tsx'

export function MessageRow({
  message,
  previousMessage,
  showReasoning,
}: {
  message: RenderableMessage
  previousMessage?: RenderableMessage
  showReasoning: boolean
}): React.ReactElement {
  return (
    <Box flexDirection="column" marginTop={messageRowMargin(message, previousMessage)}>
      <Message message={message} showReasoning={showReasoning} />
    </Box>
  )
}

function messageRowMargin(message: RenderableMessage, previousMessage?: RenderableMessage): number {
  if (!previousMessage) {
    return 0
  }
  if (isToolMessage(message) && isToolMessage(previousMessage)) {
    return 0
  }
  if (message.type === 'user' && previousMessage.type === 'user') {
    return 0
  }
  if (message.type === 'system' && previousMessage.type === 'system') {
    return 0
  }
  return 1
}

function isToolMessage(message: RenderableMessage): boolean {
  return message.type === 'grouped_tool' || message.type === 'collapsed_read_search'
}
