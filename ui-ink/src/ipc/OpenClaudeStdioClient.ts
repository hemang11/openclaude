import { randomUUID } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import readline from 'node:readline'

import type {
  OpenClaudeEvent,
  OpenClaudeRequest,
  OpenClaudeResponse,
} from '../../../types/stdio/protocol.ts'

type PendingRequest = {
  resolve: (value: OpenClaudeResponse) => void
  reject: (reason?: unknown) => void
  onEvent?: (event: OpenClaudeEvent) => void
}

export interface OpenClaudeRequestHandle<TResult extends OpenClaudeResponse['result'] = OpenClaudeResponse['result']> {
  id: string
  promise: Promise<TResult>
}

export interface OpenClaudeClient {
  on(event: 'stderr', listener: (line: string) => void): this
  off(event: 'stderr', listener: (line: string) => void): this
  request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult>
  startRequest<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): OpenClaudeRequestHandle<TResult>
  dispose(): void
}

export class OpenClaudeStdioClient extends EventEmitter implements OpenClaudeClient {
  private readonly child: ChildProcessWithoutNullStreams
  private readonly pending = new Map<string, PendingRequest>()

  constructor(command: string, args: string[], env: NodeJS.ProcessEnv = process.env) {
    super()
    this.child = spawn(command, args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      env,
    })

    const stdout = readline.createInterface({ input: this.child.stdout })
    stdout.on('line', (line) => this.handleLine(line))

    const stderr = readline.createInterface({ input: this.child.stderr })
    stderr.on('line', (line) => {
      if (shouldEmitStderrLine(line)) {
        this.emit('stderr', line)
      }
    })

    this.child.on('exit', (code, signal) => {
      const error = new Error(`OpenClaude backend exited with code=${code ?? 'null'} signal=${signal ?? 'null'}`)
      for (const pending of this.pending.values()) {
        pending.reject(error)
      }
      this.pending.clear()
      this.emit('exit', { code, signal })
    })
  }

  async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    return this.startRequest<TResult>(method, params, onEvent).promise
  }

  startRequest<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): OpenClaudeRequestHandle<TResult> {
    const id = randomUUID()
    const envelope: OpenClaudeRequest = {
      kind: 'request',
      id,
      method,
      params,
    } as OpenClaudeRequest

    const promise = new Promise<OpenClaudeResponse>((resolve, reject) => {
      this.pending.set(id, { resolve, reject, onEvent })
    })

    this.child.stdin.write(`${JSON.stringify(envelope)}\n`)
    return {
      id,
      promise: promise.then((response) => {
        if (!response.ok) {
          throw new Error(response.error?.message ?? 'OpenClaude backend request failed.')
        }
        return response.result as TResult
      }),
    }
  }

  dispose(): void {
    this.child.kill()
  }

  private handleLine(line: string): void {
    if (!line.trim()) {
      return
    }

    const message = JSON.parse(line) as OpenClaudeResponse | OpenClaudeEvent
    if (message.kind === 'event') {
      const pending = this.pending.get(message.id)
      pending?.onEvent?.(message)
      this.emit('event', message)
      return
    }

    const pending = this.pending.get(message.id)
    if (!pending) {
      return
    }

    this.pending.delete(message.id)
    pending.resolve(message)
  }
}

function shouldEmitStderrLine(line: string): boolean {
  const normalized = line.trim()
  if (!normalized) {
    return false
  }
  if (normalized.includes('StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION')) {
    return false
  }
  if (normalized.includes('end-of-input')) {
    return false
  }
  if (normalized.includes('No content to map')) {
    return false
  }
  return true
}
