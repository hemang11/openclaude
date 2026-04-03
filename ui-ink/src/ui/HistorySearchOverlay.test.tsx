import assert from 'node:assert/strict'
import test from 'node:test'

import { filterHistorySearchEntries } from './HistorySearchOverlay.tsx'

test('filterHistorySearchEntries returns exact substring matches before fuzzy subsequence matches', () => {
  const now = Date.now()
  const results = filterHistorySearchEntries(
    [
      { value: 'alpha prompt', createdAt: now - 1_000 },
      { value: 'archive plan', createdAt: now - 2_000 },
      { value: 'beta prompt', createdAt: now - 3_000 },
    ],
    'ap',
  )

  assert.deepEqual(
    results.map((entry) => entry.value),
    ['alpha prompt', 'archive plan', 'beta prompt'],
  )
})
