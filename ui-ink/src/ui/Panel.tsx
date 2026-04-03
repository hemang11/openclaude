import React from 'react'
import { Box, Text } from 'ink'

import type { PanelView } from '../../../types/stdio/protocol.ts'
import { uiTheme, type ThemeColor } from './theme.ts'

interface PanelProps {
  panel: PanelView
}

export function Panel({ panel }: PanelProps): React.ReactElement {
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        {panel.title}
      </Text>
      {panel.subtitle ? <Text dimColor>{panel.subtitle}</Text> : null}
      {panel.contextUsage ? (
        <>
          <Box height={1} />
          <Text color={colorForStatus(panel.contextUsage.status)}>
            {renderContextBar(panel.contextUsage.usedCells, panel.contextUsage.totalCells)}{' '}
            <Text dimColor>
              {panel.contextUsage.estimatedTokens}/{panel.contextUsage.contextWindowTokens} estimated tokens
            </Text>
          </Text>
        </>
      ) : null}
      {panel.sections.map((section) => (
        <Box key={section.title} flexDirection="column" marginTop={1}>
          <Text bold>{section.title}</Text>
          {section.lines.map((line, index) => (
            <Text key={`${section.title}-${index}`} dimColor={line.startsWith('…')}>
              {line}
            </Text>
          ))}
        </Box>
      ))}
      <Box height={1} />
      <Text dimColor>Esc close</Text>
    </Box>
  )
}

function renderContextBar(usedCells: number, totalCells: number): string {
  const active = '■'.repeat(Math.max(0, usedCells))
  const empty = '□'.repeat(Math.max(0, totalCells - usedCells))
  return active + empty
}

function colorForStatus(status: string): ThemeColor {
  switch (status) {
    case 'healthy':
      return uiTheme.brand
    case 'warming':
    case 'hot':
      return 'yellow'
    default:
      return 'red'
  }
}
