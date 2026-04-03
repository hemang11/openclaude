import assert from 'node:assert/strict'
import test from 'node:test'

import { formatContextRemainingPercent } from './StatusLine.tsx'

test('formatContextRemainingPercent keeps whole percentages for ordinary ranges', () => {
  assert.equal(formatContextRemainingPercent(25_000, 200_000), '88%')
})

test('formatContextRemainingPercent keeps integer percentages near a full context window', () => {
  assert.equal(formatContextRemainingPercent(1_500, 200_000), '99%')
  assert.equal(formatContextRemainingPercent(400, 200_000), '99%')
})

test('formatContextRemainingPercent keeps a non-zero integer signal near exhaustion', () => {
  assert.equal(formatContextRemainingPercent(199_500, 200_000), '1%')
})
