import React, { useMemo } from 'react'
import { Box, Text, useStdout } from 'ink'

import type { BackendSnapshot } from '../../../types/stdio/protocol.ts'
import { InlineMarkdown } from './Markdown.tsx'
import { uiTheme } from './theme.ts'

type Props = {
  snapshot: BackendSnapshot | null
}

type LayoutMode = 'horizontal' | 'compact'

type FeedLine = {
  text: string
  timestamp?: string
}

const MAX_LEFT_WIDTH = 50
const BORDER_PADDING = 4
const DIVIDER_WIDTH = 1
const CONTENT_PADDING = 2
const MIN_RIGHT_WIDTH = 30
const RECENT_ACTIVITY_LIMIT = 3

export function StartupHeader({ snapshot }: Props): React.ReactElement {
  const { stdout } = useStdout()
  const columns = stdout?.columns ?? process.stdout.columns ?? 120
  const layoutMode = getLayoutMode(columns)

  const welcomeMessage = 'OpenClaude'
  const effortSuffix = snapshot?.settings.effortLevel ? ` · effort ${snapshot.settings.effortLevel}` : ''
  const modelLine = `${snapshot?.state.activeModelId ?? 'default-model'} · ${snapshot?.state.activeProvider ?? 'no-provider'}${snapshot?.settings.fastMode ? ' · fast' : ''}${effortSuffix}`
  const cwdLine = `Session ${snapshot?.session?.sessionId ?? 'pending'} · ${snapshot?.session?.totalMessageCount ?? 0} messages`
  const optimalLeftWidth = calculateOptimalLeftWidth(welcomeMessage, cwdLine, modelLine)
  const { leftWidth, rightWidth, totalWidth } = calculateLayoutDimensions(columns, layoutMode, optimalLeftWidth)

  const tips = useMemo(() => buildTips(snapshot, Math.max(24, rightWidth)), [rightWidth, snapshot])
  const recentActivity = useMemo(
    () => buildRecentActivity(snapshot, Math.max(24, rightWidth)),
    [rightWidth, snapshot],
  )

  const leftPanel = (
    <Box
      flexDirection="column"
      width={leftWidth}
      minHeight={9}
      justifyContent="space-between"
      alignItems="center"
    >
      <Box marginTop={1}>
        <Text bold>{welcomeMessage}</Text>
      </Box>
      <OpenClawd />
      <Box flexDirection="column" alignItems="center">
        <Text dimColor>{truncateLine(modelLine, leftWidth - 2)}</Text>
        <Text dimColor>{truncateLine(cwdLine, leftWidth - 2)}</Text>
      </Box>
    </Box>
  )

  const rightPanel = (
    <FeedColumn
      feeds={[
        { title: 'Tips for getting started', lines: tips },
        { title: 'Recent activity', lines: recentActivity },
      ]}
      width={rightWidth}
    />
  )

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.brand} marginBottom={1} width={Math.max(totalWidth, 48)}>
      <Box flexDirection={layoutMode === 'horizontal' ? 'row' : 'column'} paddingX={1} gap={1}>
        {leftPanel}
        {layoutMode === 'horizontal' ? <VerticalDivider /> : null}
        {rightPanel}
      </Box>
    </Box>
  )
}

function OpenClawd(): React.ReactElement {
  return (
    <Box flexDirection="column">
      <Text>
        <Text color={uiTheme.brand}>{' ▐'}</Text>
        <Text color="black" backgroundColor={uiTheme.brand}>{'▛███▜'}</Text>
        <Text color={uiTheme.brand}>{'▌'}</Text>
      </Text>
      <Text>
        <Text color={uiTheme.brand}>{'▝▜'}</Text>
        <Text color="black" backgroundColor={uiTheme.brand}>{'█████'}</Text>
        <Text color={uiTheme.brand}>{'▛▘'}</Text>
      </Text>
      <Text color={uiTheme.brand}>{'  ▘▘ ▝▝  '}</Text>
    </Box>
  )
}

function FeedColumn({
  feeds,
  width,
}: {
  feeds: Array<{ title: string; lines: FeedLine[] }>
  width: number
}): React.ReactElement {
  const divider = '─'.repeat(Math.max(8, width))

  return (
    <Box flexDirection="column" width={width}>
      {feeds.map((feed, index) => (
        <React.Fragment key={feed.title}>
          <FeedSection title={feed.title} lines={feed.lines} width={width} />
          {index < feeds.length - 1 ? (
            <Text color={uiTheme.brand} dimColor>
              {divider}
            </Text>
          ) : null}
        </React.Fragment>
      ))}
    </Box>
  )
}

function FeedSection({
  title,
  lines,
  width,
}: {
  title: string
  lines: FeedLine[]
  width: number
}): React.ReactElement {
  const maxTimestampWidth = Math.max(0, ...lines.map((line) => (line.timestamp ? line.timestamp.length : 0)))
  const textWidth = Math.max(10, width - (maxTimestampWidth > 0 ? maxTimestampWidth + 2 : 0))

  return (
    <Box flexDirection="column">
      <Text bold color={uiTheme.brand}>
        {title}
      </Text>
      {lines.map((line, index) => {
        const body = truncateLine(line.text, textWidth)
        return (
          <Box key={`${title}-${index}`} flexDirection="row" alignItems="flex-start">
            {maxTimestampWidth > 0 ? (
              <>
                <Text dimColor>{(line.timestamp ?? '').padEnd(maxTimestampWidth)}</Text>
                <Text>{'  '}</Text>
              </>
            ) : null}
            <Box flexDirection="column" flexGrow={1}>
              <InlineMarkdown text={body} />
            </Box>
          </Box>
        )
      })}
    </Box>
  )
}

function VerticalDivider(): React.ReactElement {
  return (
    <Box
      height="100%"
      borderStyle="single"
      borderColor={uiTheme.brand}
      borderDimColor
      borderTop={false}
      borderBottom={false}
      borderLeft={false}
    />
  )
}

function getLayoutMode(columns: number): LayoutMode {
  return columns >= 70 ? 'horizontal' : 'compact'
}

function calculateLayoutDimensions(
  columns: number,
  layoutMode: LayoutMode,
  optimalLeftWidth: number,
): { leftWidth: number; rightWidth: number; totalWidth: number } {
  if (layoutMode === 'horizontal') {
    const leftWidth = optimalLeftWidth
    const usedSpace = BORDER_PADDING + CONTENT_PADDING + DIVIDER_WIDTH + leftWidth
    const availableForRight = columns - usedSpace

    let rightWidth = Math.max(MIN_RIGHT_WIDTH, availableForRight)
    const totalWidth = Math.min(
      leftWidth + rightWidth + DIVIDER_WIDTH + CONTENT_PADDING,
      columns - BORDER_PADDING,
    )

    if (totalWidth < leftWidth + rightWidth + DIVIDER_WIDTH + CONTENT_PADDING) {
      rightWidth = totalWidth - leftWidth - DIVIDER_WIDTH - CONTENT_PADDING
    }

    return { leftWidth, rightWidth, totalWidth }
  }

  const totalWidth = Math.min(columns - BORDER_PADDING, MAX_LEFT_WIDTH + 20)
  return {
    leftWidth: totalWidth,
    rightWidth: totalWidth,
    totalWidth,
  }
}

function calculateOptimalLeftWidth(
  welcomeMessage: string,
  cwdLine: string,
  modelLine: string,
): number {
  const contentWidth = Math.max(
    welcomeMessage.length,
    cwdLine.length,
    modelLine.length,
    20,
  )
  return Math.min(contentWidth + 4, MAX_LEFT_WIDTH)
}

function buildTips(snapshot: BackendSnapshot | null, width: number): FeedLine[] {
  const providerConnected = Boolean(snapshot?.state.activeProvider)
  const modelSelected = Boolean(snapshot?.state.activeModelId)

  return [
    {
      text: truncateLine(
        providerConnected ? '`/models` review connected models' : '`/provider` connect a provider',
        width,
      ),
    },
    {
      text: truncateLine(
        providerConnected && modelSelected ? 'Type a prompt and press `Enter`' : 'Use `/` to open the command palette',
        width,
      ),
    },
    { text: truncateLine('`@` inserts workspace file paths inline', width) },
    { text: truncateLine('`meta+p` switches models quickly', width) },
  ]
}

function buildRecentActivity(snapshot: BackendSnapshot | null, width: number): FeedLine[] {
  if (!snapshot) {
    return [{ text: 'Waiting for backend state...' }]
  }

  const recentMessages = snapshot.messages
    .filter((message) => message.kind === 'user' || message.kind === 'assistant')
    .slice(-RECENT_ACTIVITY_LIMIT)
    .reverse()

  if (recentMessages.length === 0) {
    return [{ text: 'No recent activity' }]
  }

  return recentMessages.map((message) => ({
    text: truncateLine(`${message.kind === 'user' ? 'You' : 'OpenClaude'} ${compactText(message.text)}`, width - 8),
    timestamp: formatRelativeTime(message.createdAt),
  }))
}

function compactText(value: string): string {
  return value.replace(/\s+/g, ' ').trim()
}

function truncateLine(value: string, maxWidth: number): string {
  if (value.length <= maxWidth) {
    return value
  }
  return `${value.slice(0, Math.max(0, maxWidth - 1))}…`
}

function formatRelativeTime(isoString: string): string {
  const target = Date.parse(isoString)
  if (Number.isNaN(target)) {
    return ''
  }

  const deltaSeconds = Math.max(0, Math.floor((Date.now() - target) / 1000))
  if (deltaSeconds < 10) {
    return 'now'
  }
  if (deltaSeconds < 60) {
    return `${deltaSeconds}s`
  }
  const deltaMinutes = Math.floor(deltaSeconds / 60)
  if (deltaMinutes < 60) {
    return `${deltaMinutes}m`
  }
  const deltaHours = Math.floor(deltaMinutes / 60)
  if (deltaHours < 24) {
    return `${deltaHours}h`
  }
  return `${Math.floor(deltaHours / 24)}d`
}
