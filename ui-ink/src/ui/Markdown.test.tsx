import assert from 'node:assert/strict'
import test from 'node:test'

import React from 'react'

import { renderApp, waitFor } from '../testing/testTerminal.ts'
import { Markdown, StreamingMarkdown } from './Markdown.tsx'

test('Markdown renders GFM tables and links as Ink-friendly text', async () => {
  const harness = await renderApp(
    <Markdown
      text={[
        '| Tool | Result |',
        '| --- | --- |',
        '| bash | success |',
        '',
        'See [docs](https://example.com/docs).',
      ].join('\n')}
    />,
  )

  try {
    await waitFor(() => harness.stdout.output.includes('Tool | Result'))
    assert.equal(harness.stdout.output.includes('bash | success'), true)
    assert.equal(harness.stdout.output.includes('docs (https://example.com/docs)'), true)
  } finally {
    await harness.cleanup()
  }
})

test('StreamingMarkdown strips prompt XML tags while rendering the visible content', async () => {
  const harness = await renderApp(
    <StreamingMarkdown
      text={[
        '<analysis>Reason through the result.</analysis>',
        '',
        '<summary>Open [guide](https://example.com/guide).</summary>',
      ].join('\n')}
    />,
  )

  try {
    await waitFor(() => harness.stdout.output.includes('Reason through the result.'))
    assert.equal(harness.stdout.output.includes('<analysis>'), false)
    assert.equal(harness.stdout.output.includes('<summary>'), false)
    assert.equal(harness.stdout.output.includes('guide (https://example.com/guide)'), true)
  } finally {
    await harness.cleanup()
  }
})
