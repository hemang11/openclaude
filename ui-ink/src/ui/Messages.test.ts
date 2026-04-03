import assert from 'node:assert/strict'
import test from 'node:test'

import type { RenderableMessage } from '../messages/types.ts'
import { splitMessageRowsForRender } from './Messages.tsx'

test('splitMessageRowsForRender keeps committed rows static and in-flight tool rows live', () => {
  const messages: RenderableMessage[] = [
    {
      type: 'user',
      id: 'user-1',
      createdAt: '2026-04-03T00:00:00Z',
      text: 'summarize every file on my desktop',
    },
    {
      type: 'grouped_tool',
      id: 'tool-1',
      createdAt: '2026-04-03T00:00:01Z',
      assistantMessageId: 'assistant-tool-turn',
      toolGroupKey: null,
      toolId: 'tool-1',
      toolIds: ['tool-1'],
      siblingToolIds: ['tool-1'],
      toolName: 'bash',
      toolRenderClass: null,
      status: 'running',
      commands: ['find ~/Desktop -maxdepth 1 -type f | wc -l'],
      details: ['Scanning Desktop files…'],
      resultTexts: [],
      command: 'find ~/Desktop -maxdepth 1 -type f | wc -l',
      detail: 'Scanning Desktop files…',
      resultText: null,
      permissionPending: false,
      live: false,
    },
  ]

  const { staticRows, liveRows } = splitMessageRowsForRender(messages, true)

  assert.deepEqual(staticRows.map((row) => row.message.id), ['user-1'])
  assert.deepEqual(liveRows.map((row) => row.message.id), ['tool-1'])
  assert.equal(liveRows[0]?.previousMessage?.id, 'user-1')
})

test('splitMessageRowsForRender keeps fully resolved transcripts entirely static', () => {
  const messages: RenderableMessage[] = [
    {
      type: 'user',
      id: 'user-1',
      createdAt: '2026-04-03T00:00:00Z',
      text: 'summarize every file on my desktop',
    },
    {
      type: 'grouped_tool',
      id: 'tool-1',
      createdAt: '2026-04-03T00:00:01Z',
      assistantMessageId: 'assistant-tool-turn',
      toolGroupKey: null,
      toolId: 'tool-1',
      toolIds: ['tool-1'],
      siblingToolIds: ['tool-1'],
      toolName: 'bash',
      toolRenderClass: null,
      status: 'completed',
      commands: ['find ~/Desktop -maxdepth 1 -type f | wc -l'],
      details: ['find ~/Desktop -maxdepth 1 -type f | wc -l'],
      resultTexts: ['25'],
      command: 'find ~/Desktop -maxdepth 1 -type f | wc -l',
      detail: 'find ~/Desktop -maxdepth 1 -type f | wc -l',
      resultText: '25',
      permissionPending: false,
      live: false,
    },
  ]

  const { staticRows, liveRows } = splitMessageRowsForRender(messages, true)

  assert.deepEqual(staticRows.map((row) => row.message.id), ['user-1', 'tool-1'])
  assert.equal(liveRows.length, 0)
})
