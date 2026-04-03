import assert from 'node:assert/strict'
import test from 'node:test'

import { buildPanelViewport, nextPanelScrollOffset } from './panelViewport.ts'

test('buildPanelViewport wraps long logical lines into visible panel rows', () => {
  const body = [
    'Summary',
    'WebFetch — Fetches long article content from a remote page and returns a readable markdown-like summary for the active model.',
  ].join('\n')

  const viewport = buildPanelViewport(body, 40, 24, 0)

  assert.equal(viewport.scrollOffset, 0)
  assert.ok(viewport.totalLines > 2)
  assert.deepEqual(viewport.visibleLines.slice(0, 3), [
    'Summary',
    'WebFetch — Fetches long article cont',
    'ent from a remote page and returns a',
  ])
})

test('nextPanelScrollOffset scrolls by wrapped rows instead of logical lines', () => {
  const body = [
    'Summary',
    'WebFetch — Fetches long article content from a remote page and returns a readable markdown-like summary for the active model.',
    'WebSearch — Searches the web with provider-native search when available.',
  ].join('\n')

  const first = nextPanelScrollOffset(body, 24, 10, 0, 'down')
  const second = nextPanelScrollOffset(body, 24, 10, first, 'down')
  const viewport = buildPanelViewport(body, 24, 10, second)

  assert.equal(first, 1)
  assert.equal(second, 2)
  assert.notEqual(viewport.visibleLines[0], 'Summary')
})
