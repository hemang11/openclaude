import React, { useMemo } from 'react'
import { Box, Text } from 'ink'

import { computeWrappedLayout } from '../input/editor.ts'

type Props = {
  value: string
  cursorOffset: number
  placeholder?: string
  maxVisibleLines?: number
}

export function PromptComposer({
  value,
  cursorOffset,
  placeholder = 'Ask OpenClaude to work on the current repository',
  maxVisibleLines = 6,
}: Props): React.ReactElement {
  const columns = Math.max(16, (process.stdout.columns ?? 80) - 10)
  const layout = useMemo(() => computeWrappedLayout(value, cursorOffset, columns), [columns, cursorOffset, value])
  const startRow = Math.max(0, layout.cursorRow - maxVisibleLines + 1)
  const visibleLines = layout.lines.slice(startRow, startRow + maxVisibleLines)

  if (!value) {
    return (
      <Box flexDirection="column">
        <Text dimColor>
          <Text inverse> </Text>
          {placeholder}
        </Text>
      </Box>
    )
  }

  return (
    <Box flexDirection="column">
      {visibleLines.map((line, index) => {
        const row = startRow + index
        return (
          <Text key={`line-${row}`}>
            {renderLine(line, row === layout.cursorRow ? layout.cursorColumn : null)}
          </Text>
        )
      })}
      {layout.lines.length > maxVisibleLines ? (
        <Text dimColor>
          {layout.cursorRow + 1}/{layout.lines.length} lines
        </Text>
      ) : null}
    </Box>
  )
}

function renderLine(line: string, cursorColumn: number | null): React.ReactNode {
  if (cursorColumn == null) {
    return line || ' '
  }

  const safeColumn = Math.max(0, Math.min(cursorColumn, line.length))
  const before = line.slice(0, safeColumn)
  const cursorCharacter = safeColumn < line.length ? line[safeColumn] : ' '
  const after = safeColumn < line.length ? line.slice(safeColumn + 1) : ''

  return (
    <>
      {before}
      <Text inverse>{cursorCharacter}</Text>
      {after}
    </>
  )
}
