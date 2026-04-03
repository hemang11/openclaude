import React from 'react'
import { Box, Text } from 'ink'

type Props = {
  kind: 'system' | 'progress' | 'attachment' | 'tombstone' | 'compact_boundary' | 'compact_summary'
  text: string
  attachmentKind?: string | null
  hookEvent?: string | null
  source?: string | null
  reason?: string | null
}

export function StatusMessage({ kind, text, attachmentKind, hookEvent, source, reason }: Props): React.ReactElement {
  const descriptor = statusDescriptor(kind)
  const content =
    kind === 'attachment' && (attachmentLabel(attachmentKind, hookEvent, source))
      ? `${attachmentLabel(attachmentKind, hookEvent, source)}: ${text}`
      : kind === 'tombstone' && reason
        ? `${reason}: ${text}`
        : text

  return (
    <Box>
      <Text color={descriptor.color} dimColor>
        {descriptor.prefix} {content}
      </Text>
    </Box>
  )
}

function attachmentLabel(
  attachmentKind?: string | null,
  hookEvent?: string | null,
  source?: string | null,
): string | null {
  switch ((attachmentKind ?? '').trim()) {
    case 'hook_additional_context':
      return hookEvent?.trim() || 'Hook'
    case 'plan_mode':
      return 'Plan mode'
    case 'restored_file':
      return 'Restored file'
    case 'compact_file_reference':
      return 'File reference'
    default:
      return source?.trim() || null
  }
}

function statusDescriptor(kind: Props['kind']): {
  prefix: string
  color: 'magenta' | 'yellow' | 'cyan' | 'red' | 'green'
} {
  switch (kind) {
    case 'system':
      return { prefix: '!', color: 'magenta' }
    case 'progress':
      return { prefix: '•', color: 'yellow' }
    case 'attachment':
      return { prefix: '@', color: 'cyan' }
    case 'compact_boundary':
      return { prefix: '≡', color: 'green' }
    case 'compact_summary':
      return { prefix: '⋯', color: 'green' }
    case 'tombstone':
      return { prefix: 'x', color: 'red' }
  }
}
