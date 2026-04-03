import test from 'node:test'
import assert from 'node:assert/strict'

import { summarizeToolResult, truncateMultilineText } from './shared.tsx'

test('summarizeToolResult removes duplicated command boilerplate and clips long output', () => {
  const summary = summarizeToolResult(
    [
      "Command: cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l",
      'Exit code: 0',
      '',
      '25',
      'Agent-Kit',
      'bytebytego-ai-cohort',
      'claude-code-java',
      'extra-folder',
    ].join('\n'),
    "cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l",
  )

  assert.equal(summary.includes('Command:'), false)
  assert.equal(summary.includes('Exit code: 0'), false)
  assert.equal(summary.includes('25'), true)
  assert.equal(summary.includes('Agent-Kit'), true)
  assert.equal(summary.includes('bytebytego-ai-cohort'), true)
  assert.equal(summary.includes('extra-folder'), false)
  assert.equal(summary.endsWith('…'), true)
})

test('truncateMultilineText clips after the configured number of lines', () => {
  const clipped = truncateMultilineText('one\ntwo\nthree\nfour', { maxLines: 2, maxLineLength: 20 })

  assert.equal(clipped, 'one\ntwo\n…')
})
