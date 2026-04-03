import React, { useMemo } from 'react'
import { Box, Static, Text } from 'ink'

import type { BackendSnapshot } from '../../../types/stdio/protocol.ts'
import { normalizeRenderableMessages } from '../messages/normalizeMessages.ts'
import type { RenderableMessage } from '../messages/types.ts'
import { MessageRow } from './MessageRow.tsx'

type Props = {
  snapshot: BackendSnapshot | null
  showEmptyState?: boolean
  renderStatic?: boolean
  staticKey?: string
}

type MessageRowItem = {
  id: string
  message: RenderableMessage
  previousMessage?: RenderableMessage
}

export function Messages({
  snapshot,
  showEmptyState = true,
  renderStatic = false,
  staticKey = 'default',
}: Props): React.ReactElement {
  const showReasoning = snapshot?.settings.reasoningVisible || snapshot?.settings.verboseOutput
  const messages = useMemo(
    () => normalizeRenderableMessages({ snapshot }),
    [snapshot],
  )
  const { staticRows, liveRows } = useMemo(
    () => splitMessageRowsForRender(messages, renderStatic),
    [messages, renderStatic],
  )

  return (
    <Box flexDirection="column" marginBottom={1}>
      {messages.length === 0 && showEmptyState ? (
        <>
          <Text dimColor>No conversation yet.</Text>
          <Text dimColor>Send a prompt when you are ready.</Text>
        </>
      ) : messages.length > 0 ? (
        renderStatic ? (
          <>
            {staticRows.length > 0 ? (
              <Static key={staticKey} items={staticRows}>
                {(row) => (
                  <MessageRow
                    key={row.id}
                    message={row.message}
                    previousMessage={row.previousMessage}
                    showReasoning={Boolean(showReasoning)}
                  />
                )}
              </Static>
            ) : null}
            {liveRows.map((row) => (
              <MessageRow
                key={row.id}
                message={row.message}
                previousMessage={row.previousMessage}
                showReasoning={Boolean(showReasoning)}
              />
            ))}
          </>
        ) : (
          toMessageRows(messages).map((row) => (
            <MessageRow
              key={row.id}
              message={row.message}
              previousMessage={row.previousMessage}
              showReasoning={Boolean(showReasoning)}
            />
          ))
        )
      ) : null}
    </Box>
  )
}

export function splitMessageRowsForRender(messages: RenderableMessage[], renderStatic: boolean): {
  staticRows: MessageRowItem[]
  liveRows: MessageRowItem[]
} {
  if (!renderStatic || messages.length === 0) {
    return {
      staticRows: [],
      liveRows: toMessageRows(messages),
    }
  }

  const firstDynamicIndex = messages.findIndex((message) => !shouldRenderStatically(message))
  if (firstDynamicIndex === -1) {
    return {
      staticRows: toMessageRows(messages),
      liveRows: [],
    }
  }

  const staticMessages = messages.slice(0, firstDynamicIndex)
  const liveMessages = messages.slice(firstDynamicIndex)
  return {
    staticRows: toMessageRows(staticMessages),
    liveRows: toMessageRows(liveMessages, staticMessages[staticMessages.length - 1]),
  }
}

function toMessageRows(
  messages: RenderableMessage[],
  previousMessage?: RenderableMessage,
): MessageRowItem[] {
  const rows: MessageRowItem[] = []
  let previous = previousMessage

  for (const message of messages) {
    rows.push({
      id: message.id,
      message,
      previousMessage: previous,
    })
    previous = message
  }

  return rows
}

function shouldRenderStatically(message: RenderableMessage): boolean {
  switch (message.type) {
    case 'assistant':
      return !message.live && message.blocks.every((block) => !block.live)
    case 'grouped_tool':
      return !message.live && !message.permissionPending && (message.status === 'completed' || message.status === 'failed')
    case 'collapsed_read_search':
      return !message.live && !message.active && (message.status === 'completed' || message.status === 'failed')
    default:
      return true
  }
}
