import test from 'node:test'
import assert from 'node:assert/strict'

import { createInkStdout } from './screen.ts'

test('createInkStdout keeps rows dynamic instead of freezing the startup height', () => {
  let currentRows = 18
  const stdout = {
    isTTY: true,
    rows: currentRows,
    write() {
      return true
    },
  } as unknown as NodeJS.WriteStream

  const proxy = createInkStdout(stdout)
  assert.equal(proxy.rows, 24)

  currentRows = 60
  Object.assign(stdout, { rows: currentRows })
  assert.equal(proxy.rows, 60)
})

test('createInkStdout strips the clear-scrollback escape sequence', () => {
  let written = ''
  const stdout = {
    isTTY: true,
    rows: 24,
    write(chunk: string) {
      written += chunk
      return true
    },
  } as unknown as NodeJS.WriteStream

  const proxy = createInkStdout(stdout)
  proxy.write('\u001B[2J\u001B[3J\u001B[Hhello')

  assert.equal(written.includes('\u001B[3J'), false)
  assert.equal(written.includes('\u001B[2J'), true)
  assert.equal(written.endsWith('hello'), true)
})
