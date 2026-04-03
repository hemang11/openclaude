import React from 'react'
import { Box, Text } from 'ink'
import { uiTheme } from './theme.ts'

export interface PickerOption {
  key: string
  label: string
  detail?: string
}

interface PickerProps {
  title: string
  subtitle: string
  options: PickerOption[]
  selectedIndex: number
}

export function Picker({ title, subtitle, options, selectedIndex }: PickerProps): React.ReactElement {
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={uiTheme.overlayBorder} paddingX={1}>
      <Text bold color={uiTheme.overlayTitle}>
        {title}
      </Text>
      <Text dimColor>{subtitle}</Text>
      <Box height={1} />
      {options.map((option, index) => (
        <Box key={option.key} flexDirection="column">
          <Text color={index === selectedIndex ? uiTheme.overlaySelection : undefined}>
            {index === selectedIndex ? '› ' : '  '}
            {option.label}
          </Text>
          {option.detail ? (
            <Box marginLeft={2}>
              <Text dimColor>{option.detail}</Text>
            </Box>
          ) : null}
        </Box>
      ))}
      <Box height={1} />
      <Text dimColor>↑/↓ move  Enter select  Esc close</Text>
    </Box>
  )
}
