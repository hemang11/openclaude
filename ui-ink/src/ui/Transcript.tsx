import React from 'react'

import type { BackendSnapshot } from '../../../types/stdio/protocol.ts'
import { Messages } from './Messages.tsx'

type Props = {
  snapshot: BackendSnapshot | null
  showEmptyState?: boolean
  staticKey?: string
}

export function Transcript({ staticKey, ...props }: Props): React.ReactElement {
  return <Messages {...props} renderStatic staticKey={staticKey} />
}
