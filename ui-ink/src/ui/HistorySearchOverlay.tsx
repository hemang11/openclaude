import React from 'react'
import { Box, Text } from 'ink'

import { PromptComposer } from './PromptComposer.tsx'
import { uiTheme } from './theme.ts'

export type PromptHistoryEntry = {
  value: string
  createdAt: number
}

export type HistorySearchOverlayState = {
  kind: 'history-search'
  query: string
  queryCursorOffset: number
  selectedIndex: number
  entries: PromptHistoryEntry[]
}

const PREVIEW_ROWS = 6

export function filterHistorySearchEntries(
  entries: PromptHistoryEntry[],
  query: string,
): PromptHistoryEntry[] {
  const normalized = query.trim().toLowerCase()
  if (!normalized) {
    return entries
  }

  const exact: PromptHistoryEntry[] = []
  const fuzzy: PromptHistoryEntry[] = []

  for (const entry of entries) {
    const lower = entry.value.toLowerCase()
    if (lower.includes(normalized)) {
      exact.push(entry)
    } else if (isSubsequence(lower, normalized)) {
      fuzzy.push(entry)
    }
  }

  return exact.concat(fuzzy)
}

export function renderHistorySearchOverlay(
  overlay: HistorySearchOverlayState,
): React.ReactElement {
  const filtered = filterHistorySearchEntries(overlay.entries, overlay.query)
  const selected = filtered[Math.min(overlay.selectedIndex, Math.max(0, filtered.length - 1))]
  const selectedPreview = selected?.value ?? ''
  const previewLines = selectedPreview.split(/\r?\n/).filter((line) => line.trim() !== '')
  const shownPreviewLines = previewLines.slice(0, PREVIEW_ROWS)
  const morePreviewLines = Math.max(0, previewLines.length - shownPreviewLines.length)
  const rowWidth = Math.max(24, (process.stdout.columns ?? 80) - 20)

  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        Search prompts
      </Text>
      <Text dimColor>Filter local prompt history and restore a previous prompt into the composer.</Text>
      <Box height={1} />
      <Box>
        <Text color={uiTheme.promptMarker}>{'> '}</Text>
        <PromptComposer
          value={overlay.query}
          cursorOffset={overlay.queryCursorOffset}
          placeholder="Filter history…"
          maxVisibleLines={1}
        />
      </Box>
      <Box height={1} />
      {filtered.length === 0 ? (
        <Text dimColor>{overlay.entries.length === 0 ? 'No history yet.' : 'No matching prompts.'}</Text>
      ) : (
        <Box flexDirection="column">
          {filtered.slice(0, 8).map((entry, index) => {
            const firstLine = entry.value.split(/\r?\n/, 1)[0] ?? ''
            const truncatedLine = truncateToWidth(firstLine, rowWidth)
            const age = formatRelativeTimeAgo(entry.createdAt)

            return (
              <Text key={`${entry.createdAt}-${index}`} color={index === overlay.selectedIndex ? uiTheme.overlaySelection : undefined}>
                {index === overlay.selectedIndex ? '› ' : '  '}
                <Text dimColor>{padEnd(age, 8)}</Text>
                <Text>{` ${truncatedLine}`}</Text>
              </Text>
            )
          })}
        </Box>
      )}
      {selected ? (
        <>
          <Box height={1} />
          <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
            {shownPreviewLines.map((line, index) => (
              <Text key={`${selected.createdAt}-${index}`} dimColor>
                {line}
              </Text>
            ))}
            {morePreviewLines > 0 ? <Text dimColor>{`… +${morePreviewLines} more lines`}</Text> : null}
          </Box>
        </>
      ) : null}
      <Box height={1} />
      <Text dimColor>Type to filter  ↑/↓ move  Enter use  Esc close</Text>
    </Box>
  )
}

function truncateToWidth(value: string, width: number): string {
  if (value.length <= width) {
    return value
  }
  return `${value.slice(0, Math.max(0, width - 1))}…`
}

function formatRelativeTimeAgo(timestamp: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000))
  if (seconds < 60) {
    return `${seconds}s`
  }

  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes}m`
  }

  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours}h`
  }

  const days = Math.floor(hours / 24)
  return `${days}d`
}

function isSubsequence(text: string, query: string): boolean {
  let index = 0
  for (let textIndex = 0; textIndex < text.length && index < query.length; textIndex += 1) {
    if (text[textIndex] === query[index]) {
      index += 1
    }
  }
  return index === query.length
}

function padEnd(value: string, width: number): string {
  return value.length >= width ? value : value + ' '.repeat(width - value.length)
}
