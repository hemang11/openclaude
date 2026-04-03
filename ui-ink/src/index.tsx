import React from 'react'
import { render } from 'ink'

import { App } from './app.tsx'
import { createInkStdout } from './terminal/screen.ts'

const stdout = createInkStdout(process.stdout)

render(<App showStartupStatusLines={false} />, {
  stdin: process.stdin,
  stdout,
  stderr: process.stderr,
  exitOnCtrlC: false,
  patchConsole: false,
})
