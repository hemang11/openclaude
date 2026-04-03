import React from 'react'
import { Text } from 'ink'

import type { PromptInputMode } from '../prompt/inputModes.ts'
import { uiTheme } from './theme.ts'

type Props = {
  mode: PromptInputMode
  isLoading: boolean
}

export function PromptInputModeIndicator({
  mode,
  isLoading,
}: Props): React.ReactElement {
  if (mode === 'bash') {
    return (
      <Text color={uiTheme.bashBorder} dimColor={isLoading}>
        !{' '}
      </Text>
    )
  }

  return (
    <Text color={uiTheme.promptMarker} dimColor={isLoading}>
      {'> '}
    </Text>
  )
}
