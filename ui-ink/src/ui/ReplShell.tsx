import React from 'react'
import { Box } from 'ink'

type Props = {
  header?: React.ReactNode
  transcript?: React.ReactNode
  pendingTurn?: React.ReactNode
  thinking?: React.ReactNode
  liveMessages?: React.ReactNode
  tasks?: React.ReactNode
  overlay?: React.ReactNode
  prompt: React.ReactNode
  statusLine?: React.ReactNode
  statusFeed?: React.ReactNode
}

export function ReplShell({
  header,
  transcript,
  pendingTurn,
  thinking,
  liveMessages,
  tasks,
  overlay,
  prompt,
  statusLine,
  statusFeed,
}: Props): React.ReactElement {
  return (
    <Box flexDirection="column">
      {header}
      {transcript}
      {pendingTurn}
      {thinking}
      {liveMessages}
      {tasks}
      {overlay}
      {prompt}
      {statusLine}
      {statusFeed}
    </Box>
  )
}
