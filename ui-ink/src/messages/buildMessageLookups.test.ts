import assert from 'node:assert/strict'
import test from 'node:test'

import { buildMessageLookups, hasUnresolvedHooksFromLookup } from './buildMessageLookups.ts'
import type { SessionMessageView } from '../../../types/stdio/protocol.ts'

test('buildMessageLookups indexes tool use siblings, results, and resolution state', () => {
  const messages: SessionMessageView[] = [
    {
      kind: 'assistant',
      id: 'assistant-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: '',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
    {
      kind: 'tool_use',
      id: 'assistant-1:tool-a',
      createdAt: '2026-04-02T00:00:01Z',
      text: '{"command":"pwd"}',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
    },
    {
      kind: 'tool_use',
      id: 'assistant-1:tool-b',
      createdAt: '2026-04-02T00:00:02Z',
      text: '{"command":"ls"}',
      toolId: 'tool-b',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"ls"}',
    },
    {
      kind: 'tool',
      id: 'tool-a-progress',
      createdAt: '2026-04-02T00:00:03Z',
      text: 'Running pwd',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'progress',
      command: 'pwd',
    },
    {
      kind: 'tool_result',
      id: 'tool-a-result',
      createdAt: '2026-04-02T00:00:04Z',
      text: 'Command: pwd',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'completed',
      isError: false,
    },
  ]

  const lookups = buildMessageLookups(messages)

  assert.equal(lookups.toolUseById.get('tool-a')?.toolName, 'bash')
  assert.equal(lookups.toolResultById.get('tool-a')?.text, 'Command: pwd')
  assert.equal(lookups.resolvedToolUseIds.has('tool-a'), true)
  assert.equal(lookups.resolvedToolUseIds.has('tool-b'), false)
  assert.equal(lookups.assistantMessageIdByToolUseId.get('tool-a'), 'assistant-1')
  assert.deepEqual(lookups.toolUseIdsByAssistantMessageId.get('assistant-1'), ['tool-a', 'tool-b'])
  assert.equal(lookups.toolGroupKeyByToolUseId.get('tool-a'), 'assistant-1:bash')
  assert.deepEqual(lookups.toolUseIdsByGroupKey.get('assistant-1:bash'), ['tool-a', 'tool-b'])
  assert.deepEqual([...lookups.siblingToolUseIdsByToolUseId.get('tool-a') ?? []], ['tool-a', 'tool-b'])
  assert.equal(lookups.toolEventsById.get('tool-a')?.length, 1)
  assert.equal(lookups.erroredToolUseIds.has('tool-a'), false)
})

test('buildMessageLookups prefers backend-provided assistant and sibling metadata over id-prefix inference', () => {
  const messages: SessionMessageView[] = [
    {
      kind: 'tool_use',
      id: 'row-1',
      createdAt: '2026-04-02T00:00:01Z',
      text: '{"command":"pwd"}',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a', 'tool-b'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
    },
    {
      kind: 'tool_use',
      id: 'row-2',
      createdAt: '2026-04-02T00:00:02Z',
      text: '{"command":"ls"}',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a', 'tool-b'],
      toolId: 'tool-b',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"ls"}',
    },
    {
      kind: 'tool_result',
      id: 'result-b',
      createdAt: '2026-04-02T00:00:03Z',
      text: 'Command: ls',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a', 'tool-b'],
      toolId: 'tool-b',
      toolName: 'bash',
      phase: 'completed',
      isError: false,
    },
  ]

  const lookups = buildMessageLookups(messages)

  assert.equal(lookups.assistantMessageIdByToolUseId.get('tool-a'), 'assistant-explicit')
  assert.equal(lookups.assistantMessageIdByToolUseId.get('tool-b'), 'assistant-explicit')
  assert.deepEqual(lookups.toolUseIdsByAssistantMessageId.get('assistant-explicit'), ['tool-a', 'tool-b'])
  assert.deepEqual([...lookups.siblingToolUseIdsByToolUseId.get('tool-a') ?? []], ['tool-a', 'tool-b'])
  assert.deepEqual([...lookups.siblingToolUseIdsByToolUseId.get('tool-b') ?? []], ['tool-a', 'tool-b'])
  assert.equal(lookups.toolResultById.get('tool-b')?.text, 'Command: ls')
})

test('buildMessageLookups tracks tool-scoped hook attachment counts when metadata is present', () => {
  const messages: SessionMessageView[] = [
    {
      kind: 'tool_use',
      id: 'row-1',
      createdAt: '2026-04-02T00:00:01Z',
      text: '{"command":"pwd"}',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
    },
    {
      kind: 'attachment',
      id: 'hook-pre-1',
      createdAt: '2026-04-02T00:00:02Z',
      text: 'pre hook',
      attachmentKind: 'hook_additional_context',
      hookEvent: 'PreToolUse',
      toolId: 'tool-a',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
      source: 'hook_additional_context',
    },
    {
      kind: 'attachment',
      id: 'hook-post-1',
      createdAt: '2026-04-02T00:00:03Z',
      text: 'post hook',
      attachmentKind: 'hook_additional_context',
      hookEvent: 'PostToolUse',
      toolId: 'tool-a',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
      source: 'hook_additional_context',
    },
  ]

  const lookups = buildMessageLookups(messages)

  assert.equal(lookups.hookMessagesByToolUseId.get('tool-a')?.length, 2)
  assert.equal(lookups.resolvedHookCounts.get('tool-a')?.get('PreToolUse'), 1)
  assert.equal(lookups.resolvedHookCounts.get('tool-a')?.get('PostToolUse'), 1)
})

test('buildMessageLookups tracks hook progress and responses from typed progress rows', () => {
  const messages: SessionMessageView[] = [
    {
      kind: 'tool_use',
      id: 'row-1',
      createdAt: '2026-04-02T00:00:01Z',
      text: '{"command":"pwd"}',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
    },
    {
      kind: 'progress',
      id: 'hook-progress-1',
      createdAt: '2026-04-02T00:00:02Z',
      text: 'PreToolUse:bash [echo pre] started',
      hookEvent: 'PreToolUse',
      toolId: 'tool-a',
      toolName: 'PreToolUse:bash',
      phase: 'hook_started',
      command: 'echo pre',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
    },
    {
      kind: 'progress',
      id: 'hook-response-1',
      createdAt: '2026-04-02T00:00:03Z',
      text: 'PreToolUse:bash [echo pre] completed successfully',
      hookEvent: 'PreToolUse',
      toolId: 'tool-a',
      toolName: 'PreToolUse:bash',
      phase: 'hook_response',
      command: 'echo pre',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
    },
  ]

  const lookups = buildMessageLookups(messages)

  assert.equal(lookups.hookMessagesByToolUseId.get('tool-a')?.length, 2)
  assert.equal(lookups.inProgressHookCounts.get('tool-a')?.get('PreToolUse'), 1)
  assert.equal(lookups.resolvedHookCounts.get('tool-a')?.get('PreToolUse'), 1)
})

test('hasUnresolvedHooksFromLookup reports unresolved hook progress correctly', () => {
  const lookups = buildMessageLookups([
    {
      kind: 'tool_use',
      id: 'row-1',
      createdAt: '2026-04-02T00:00:01Z',
      text: '{"command":"pwd"}',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
    },
    {
      kind: 'progress',
      id: 'hook-progress-1',
      createdAt: '2026-04-02T00:00:02Z',
      text: 'PostToolUse:bash [echo post] started',
      hookEvent: 'PostToolUse',
      toolId: 'tool-a',
      toolName: 'PostToolUse:bash',
      phase: 'hook_started',
      command: 'echo post',
      assistantMessageId: 'assistant-explicit',
      siblingToolIds: ['tool-a'],
    },
  ])

  assert.equal(hasUnresolvedHooksFromLookup(lookups, 'tool-a', 'PostToolUse'), true)
  assert.equal(hasUnresolvedHooksFromLookup(lookups, 'tool-a', 'PreToolUse'), false)
})
