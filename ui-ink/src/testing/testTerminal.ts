import { setTimeout as delay } from 'node:timers/promises'
import { PassThrough, Writable } from 'node:stream'

import React from 'react'
import { render, type Instance } from 'ink'

type TtyOutput = Writable & NodeJS.WriteStream
type TtyInput = PassThrough & NodeJS.ReadStream

export class TestTtyInput extends PassThrough {
  public readonly isTTY = true
  public isRaw = false

  setRawMode(value: boolean): this {
    this.isRaw = value
    return this
  }

  ref(): this {
    return this
  }

  unref(): this {
    return this
  }
}

export class TestTtyOutput extends Writable {
  public readonly isTTY = true
  public readonly columns = 120
  public readonly rows = 48
  private readonly chunks: string[] = []
  private frameStartIndex = 0

  _write(
    chunk: string | Uint8Array,
    _encoding: BufferEncoding,
    callback: (error?: Error | null) => void,
  ): void {
    this.chunks.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'))
    callback()
  }

  moveCursor(): boolean {
    return true
  }

  clearLine(): boolean {
    return true
  }

  clearScreenDown(): boolean {
    this.frameStartIndex = this.chunks.length
    return true
  }

  get output(): string {
    return this.chunks.join('')
  }

  get frame(): string {
    return this.chunks.slice(this.frameStartIndex).join('')
  }
}

export async function renderApp(node: React.ReactElement): Promise<{
  app: Instance
  stdin: TtyInput
  stdout: TestTtyOutput
  stderr: TestTtyOutput
  cleanup: () => Promise<void>
}> {
  const stdin = new TestTtyInput() as TtyInput
  const stdout = new TestTtyOutput()
  const stderr = new TestTtyOutput()
  const app = render(node, {
    stdin,
    stdout: stdout as unknown as TtyOutput,
    stderr: stderr as unknown as TtyOutput,
    exitOnCtrlC: false,
    debug: true,
    patchConsole: false,
  })

  await delay(20)

  return {
    app,
    stdin,
    stdout,
    stderr,
    cleanup: async () => {
      app.unmount()
      app.cleanup()
      await delay(20)
    },
  }
}

export async function sendInput(stdin: NodeJS.ReadWriteStream, value: string): Promise<void> {
  for (const char of [...value]) {
    stdin.write(char)
    await delay(20)
  }
}

export async function sendRawInput(stdin: NodeJS.ReadWriteStream, value: string): Promise<void> {
  stdin.write(value)
  await delay(20)
}

export async function sendKeypress(
  stdin: NodeJS.ReadWriteStream,
  key: {
    name: string
    sequence?: string
    ctrl?: boolean
    meta?: boolean
    shift?: boolean
  },
): Promise<void> {
  stdin.write(keypressSequence(key))
  await delay(20)
}

function keypressSequence(
  key: {
    name: string
    sequence?: string
    ctrl?: boolean
    meta?: boolean
    shift?: boolean
  },
): string {
  if (key.sequence && key.sequence !== key.name && !key.ctrl && !key.meta) {
    return key.sequence
  }

  if (key.ctrl && key.name.length === 1) {
    const lower = key.name.toLowerCase()
    const code = lower.charCodeAt(0)
    if (code >= 97 && code <= 122) {
      return String.fromCharCode(code - 96)
    }
  }

  const base = namedKeySequence(key.name, key.shift ?? false)
  if (key.meta) {
    return `\u001B${base}`
  }
  return base
}

function namedKeySequence(name: string, shift: boolean): string {
  switch (name) {
    case 'return':
    case 'enter':
      return '\r'
    case 'tab':
      return '\t'
    case 'escape':
      return '\u001B'
    case 'backspace':
      return '\u007F'
    case 'delete':
      return '\u001B[3~'
    case 'up':
    case 'upArrow':
      return '\u001B[A'
    case 'down':
    case 'downArrow':
      return '\u001B[B'
    case 'right':
    case 'rightArrow':
      return '\u001B[C'
    case 'left':
    case 'leftArrow':
      return '\u001B[D'
    case 'home':
      return '\u001B[H'
    case 'end':
      return '\u001B[F'
    default:
      if (name.length === 1) {
        return shift ? name.toUpperCase() : name
      }
      return name
  }
}

export async function waitFor(
  predicate: () => boolean,
  options: { timeoutMs?: number; intervalMs?: number; message?: string } = {},
): Promise<void> {
  const timeoutMs = options.timeoutMs ?? 3000
  const intervalMs = options.intervalMs ?? 20
  const startedAt = Date.now()

  while (!predicate()) {
    if (Date.now() - startedAt > timeoutMs) {
      throw new Error(options.message ?? `Condition was not met within ${timeoutMs}ms.`)
    }
    await delay(intervalMs)
  }
}
