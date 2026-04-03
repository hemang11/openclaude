import assert from 'node:assert/strict'
import test from 'node:test'

import { createLiveRenderableMessages, normalizeRenderableMessages, reorderMessagesForUI } from './normalizeMessages.ts'
import type { BackendSnapshot } from '../../../types/stdio/protocol.ts'

test('normalizeRenderableMessages groups tool invocation snapshots and tool results into one grouped tool block', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 1,
      assistantMessageCount: 1,
      totalMessageCount: 5,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'user',
        id: 'user-1',
        createdAt: '2026-04-02T00:00:00Z',
        text: 'Summarize the desktop folders',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-1',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"pwd"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"pwd"}',
      },
      {
        kind: 'tool',
        id: 'tool-msg-1',
        createdAt: '2026-04-02T00:00:02Z',
        text: 'Allow this local bash command?',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'permission_requested',
        command: 'pwd',
        permissionRequestId: 'perm-1',
        inputJson: '{"command":"pwd"}',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-1',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Command: pwd\nExit code: 0\n\n/Users/test',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'yielded',
        isError: false,
      },
      {
        kind: 'assistant',
        id: 'assistant-2',
        createdAt: '2026-04-02T00:00:04Z',
        text: 'Folder summary',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 3)
  assert.equal(messages[0]?.type, 'user')
  assert.equal(messages[1]?.type, 'grouped_tool')
  assert.equal(messages[2]?.type, 'assistant')

  const groupedTool = messages[1]
  assert.ok(groupedTool && groupedTool.type === 'grouped_tool')
  assert.equal(groupedTool.toolName, 'bash')
  assert.equal(groupedTool.status, 'completed')
  assert.equal(groupedTool.command, 'pwd')
  assert.equal(groupedTool.permissionPending, false)
  assert.match(groupedTool.resultText ?? '', /Command: pwd/)
})

test('normalizeRenderableMessages does not trim the main transcript when no limit is provided', () => {
  const snapshot = {
    messages: Array.from({ length: 30 }, (_, index) => ({
      id: `user-${index}`,
      kind: 'user',
      createdAt: `2026-04-03T00:${String(index).padStart(2, '0')}:00Z`,
      text: `message ${index}`,
    })),
  } as unknown as BackendSnapshot

  const messages = normalizeRenderableMessages({ snapshot })

  assert.equal(messages.length, 30)
  assert.equal(messages[0]?.id, 'user-0')
  assert.equal(messages[29]?.id, 'user-29')
})

test('normalizeRenderableMessages groups same-tool sibling rows from one assistant response into a single grouped row', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 0,
      assistantMessageCount: 1,
      totalMessageCount: 4,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-a',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"pwd"}',
        assistantMessageId: 'assistant-1',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolGroupKey: 'assistant-1:bash',
        toolRenderClass: 'list',
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
        assistantMessageId: 'assistant-1',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolGroupKey: 'assistant-1:bash',
        toolRenderClass: 'list',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls"}',
      },
      {
        kind: 'tool_result',
        id: 'tool-a-result',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Command: pwd\nExit code: 0',
        assistantMessageId: 'assistant-1',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolGroupKey: 'assistant-1:bash',
        toolRenderClass: 'list',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'tool_result',
        id: 'tool-b-result',
        createdAt: '2026-04-02T00:00:04Z',
        text: 'Command: ls\nExit code: 0',
        assistantMessageId: 'assistant-1',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolGroupKey: 'assistant-1:bash',
        toolRenderClass: 'list',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 1)
  const groupedTool = messages[0]
  assert.ok(groupedTool && groupedTool.type === 'grouped_tool')
  assert.deepEqual(groupedTool.toolIds, ['tool-a', 'tool-b'])
  assert.deepEqual(groupedTool.commands, ['{"command":"pwd"}', '{"command":"ls"}'])
  assert.equal(groupedTool.status, 'completed')
})

test('normalizeRenderableMessages collapses consecutive read and search tool rows across grouped families', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 0,
      assistantMessageCount: 1,
      totalMessageCount: 4,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'tool_use',
        id: 'assistant-1:read-1',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"file_path":"README.md"}',
        assistantMessageId: 'assistant-1',
        siblingToolIds: ['read-1'],
        toolGroupKey: 'assistant-1:read',
        toolRenderClass: 'read',
        toolId: 'read-1',
        toolName: 'Read',
        phase: 'started',
        inputJson: '{"file_path":"README.md"}',
      },
      {
        kind: 'tool_result',
        id: 'read-1-result',
        createdAt: '2026-04-02T00:00:02Z',
        text: 'README contents',
        assistantMessageId: 'assistant-1',
        siblingToolIds: ['read-1'],
        toolGroupKey: 'assistant-1:read',
        toolRenderClass: 'read',
        toolId: 'read-1',
        toolName: 'Read',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'tool_use',
        id: 'assistant-2:grep-1',
        createdAt: '2026-04-02T00:00:03Z',
        text: '{"pattern":"QueryEngine"}',
        assistantMessageId: 'assistant-2',
        siblingToolIds: ['grep-1'],
        toolGroupKey: 'assistant-2:grep',
        toolRenderClass: 'search',
        toolId: 'grep-1',
        toolName: 'Grep',
        phase: 'started',
        inputJson: '{"pattern":"QueryEngine"}',
      },
      {
        kind: 'tool_result',
        id: 'grep-1-result',
        createdAt: '2026-04-02T00:00:04Z',
        text: 'Matched QueryEngine',
        assistantMessageId: 'assistant-2',
        siblingToolIds: ['grep-1'],
        toolGroupKey: 'assistant-2:grep',
        toolRenderClass: 'search',
        toolId: 'grep-1',
        toolName: 'Grep',
        phase: 'completed',
        isError: false,
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 1)
  const collapsed = messages[0]
  assert.ok(collapsed && collapsed.type === 'collapsed_read_search')
  assert.equal(collapsed.label, 'Read/search actions')
  assert.deepEqual(collapsed.toolIds, ['read-1', 'grep-1'])
})

test('normalizeRenderableMessages keeps a completed tool row running while post-tool hooks are still unresolved', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 1,
      assistantMessageCount: 1,
      totalMessageCount: 4,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-1',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"pwd"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"pwd"}',
      },
      {
        kind: 'progress',
        id: 'hook-progress-1',
        createdAt: '2026-04-02T00:00:02Z',
        text: 'PostToolUse:Bash [echo post] started',
        hookEvent: 'PostToolUse',
        toolId: 'tool-1',
        toolName: 'PostToolUse:Bash',
        phase: 'hook_started',
        command: 'echo post',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-1',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Command: pwd\nExit code: 0',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 1)
  const groupedTool = messages[0]
  assert.ok(groupedTool && groupedTool.type === 'grouped_tool')
  assert.equal(groupedTool.status, 'running')
  assert.match(groupedTool.resultText ?? '', /Command: pwd/)
})

test('normalizeRenderableMessages prefers tool_result displayText over prompt text for grouped tool previews', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 0,
      assistantMessageCount: 1,
      totalMessageCount: 2,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-1',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"file_path":"/tmp/demo.txt"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-1',
        toolName: 'Write',
        phase: 'started',
        inputJson: '{"file_path":"/tmp/demo.txt"}',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-1',
        createdAt: '2026-04-02T00:00:02Z',
        text: 'Updated /tmp/demo.txt (12 chars).',
        displayText: '--- /tmp/demo.txt\n+++ /tmp/demo.txt',
        toolId: 'tool-1',
        toolName: 'Write',
        phase: 'completed',
        isError: false,
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 1)
  const groupedTool = messages[0]
  assert.ok(groupedTool && groupedTool.type === 'grouped_tool')
  assert.equal(groupedTool.resultText, '--- /tmp/demo.txt\n+++ /tmp/demo.txt')
})

test('normalizeRenderableMessages hides tool-scoped hook additional-context attachments from standalone rows', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 0,
      assistantMessageCount: 1,
      totalMessageCount: 3,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-1',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"pwd"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"pwd"}',
      },
      {
        kind: 'attachment',
        id: 'hook-attachment-1',
        createdAt: '2026-04-02T00:00:02Z',
        text: 'Additional context from PostToolUse',
        attachmentKind: 'hook_additional_context',
        hookEvent: 'PostToolUse',
        toolId: 'tool-1',
        source: 'hook_additional_context',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-1',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Command: pwd\nExit code: 0',
        toolId: 'tool-1',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 1)
  assert.equal(messages[0]?.type, 'grouped_tool')
})

test('normalizeRenderableMessages preserves typed attachment metadata for status rows', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 0,
      assistantMessageCount: 0,
      totalMessageCount: 1,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'attachment',
        id: 'attachment-1',
        createdAt: '2026-04-02T00:00:00Z',
        text: 'Additional context from SessionStart',
        attachmentKind: 'hook_additional_context',
        source: 'hook_additional_context',
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 1)
  const attachment = messages[0]
  assert.ok(attachment && attachment.type === 'attachment')
  assert.equal(attachment.attachmentKind, 'hook_additional_context')
  assert.equal(attachment.source, 'hook_additional_context')
})

test('normalizeRenderableMessages groups sibling bash tool uses into one grouped tool block', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 1,
      assistantMessageCount: 1,
      totalMessageCount: 6,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'user',
        id: 'user-1',
        createdAt: '2026-04-02T00:00:00Z',
        text: 'Inspect two directories',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-a',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"ls src"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls src"}',
        command: 'ls src',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-b',
        createdAt: '2026-04-02T00:00:02Z',
        text: '{"command":"ls test"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls test"}',
        command: 'ls test',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-a',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Command: ls src',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'tool_result',
        id: 'tool-result-b',
        createdAt: '2026-04-02T00:00:04Z',
        text: 'Command: ls test',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'assistant',
        id: 'assistant-2',
        createdAt: '2026-04-02T00:00:05Z',
        text: 'Done comparing directories.',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 3)
  const grouped = messages[1]
  assert.ok(grouped && grouped.type === 'grouped_tool')
  assert.equal(grouped.toolIds.length, 2)
  assert.equal(grouped.toolName, 'bash')
  assert.equal(grouped.status, 'completed')
  assert.deepEqual(grouped.commands, ['ls src', 'ls test'])
})

test('normalizeRenderableMessages groups tool rows using backend metadata instead of tool row id prefixes', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 1,
      assistantMessageCount: 1,
      totalMessageCount: 5,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'user',
        id: 'user-1',
        createdAt: '2026-04-02T00:00:00Z',
        text: 'Inspect two directories',
      },
      {
        kind: 'tool_use',
        id: 'row-a',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"ls src"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        assistantMessageId: 'assistant-explicit',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls src"}',
        command: 'ls src',
      },
      {
        kind: 'tool_use',
        id: 'row-b',
        createdAt: '2026-04-02T00:00:02Z',
        text: '{"command":"ls test"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        assistantMessageId: 'assistant-explicit',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls test"}',
        command: 'ls test',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-a',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Command: ls src',
        assistantMessageId: 'assistant-explicit',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'tool_result',
        id: 'tool-result-b',
        createdAt: '2026-04-02T00:00:04Z',
        text: 'Command: ls test',
        assistantMessageId: 'assistant-explicit',
        siblingToolIds: ['tool-a', 'tool-b'],
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 2)
  assert.equal(messages[0]?.type, 'user')
  const grouped = messages[1]
  assert.ok(grouped && grouped.type === 'grouped_tool')
  assert.deepEqual(grouped.toolIds, ['tool-a', 'tool-b'])
  assert.deepEqual(grouped.commands, ['ls src', 'ls test'])
})

test('reorderMessagesForUI re-emits tool events and results at the tool_use position', () => {
  const ordered = reorderMessagesForUI([
    {
      kind: 'user',
      id: 'user-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'Inspect files',
    },
    {
      kind: 'tool',
      id: 'tool-a-progress',
      createdAt: '2026-04-02T00:00:01Z',
      text: 'Running pwd',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'progress',
      command: 'pwd',
    },
    {
      kind: 'system',
      id: 'system-1',
      createdAt: '2026-04-02T00:00:02Z',
      text: 'Prompt started',
    },
    {
      kind: 'tool_result',
      id: 'tool-a-result',
      createdAt: '2026-04-02T00:00:03Z',
      text: 'Command: pwd',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'completed',
      isError: false,
    },
    {
      kind: 'tool_use',
      id: 'assistant-1:tool-a',
      createdAt: '2026-04-02T00:00:04Z',
      text: '{"command":"pwd"}',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
      assistantMessageId: 'assistant-1',
      siblingToolIds: ['tool-a'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
      command: 'pwd',
    },
    {
      kind: 'assistant',
      id: 'assistant-2',
      createdAt: '2026-04-02T00:00:05Z',
      text: 'Done.',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
  ])

  assert.deepEqual(
    ordered.map((message) => `${message.kind}:${message.id}`),
    [
      'user:user-1',
      'system:system-1',
      'tool_use:assistant-1:tool-a',
      'tool:tool-a-progress',
      'tool_result:tool-a-result',
      'assistant:assistant-2',
    ],
  )
})

test('reorderMessagesForUI places tool-scoped pre and post hook attachments around the matching tool trajectory', () => {
  const ordered = reorderMessagesForUI([
    {
      kind: 'attachment',
      id: 'hook-post-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'post hook',
      attachmentKind: 'hook_additional_context',
      hookEvent: 'PostToolUse',
      toolId: 'tool-a',
      source: 'hook_additional_context',
    },
    {
      kind: 'tool_result',
      id: 'tool-a-result',
      createdAt: '2026-04-02T00:00:01Z',
      text: 'Command: pwd',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'completed',
      isError: false,
    },
    {
      kind: 'attachment',
      id: 'hook-pre-1',
      createdAt: '2026-04-02T00:00:02Z',
      text: 'pre hook',
      attachmentKind: 'hook_additional_context',
      hookEvent: 'PreToolUse',
      toolId: 'tool-a',
      source: 'hook_additional_context',
    },
    {
      kind: 'tool_use',
      id: 'assistant-1:tool-a',
      createdAt: '2026-04-02T00:00:03Z',
      text: '{"command":"pwd"}',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
      assistantMessageId: 'assistant-1',
      siblingToolIds: ['tool-a'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
      command: 'pwd',
    },
  ])

  assert.deepEqual(
    ordered.map((message) => `${message.kind}:${message.id}`),
    [
      'tool_use:assistant-1:tool-a',
      'attachment:hook-pre-1',
      'tool_result:tool-a-result',
      'attachment:hook-post-1',
    ],
  )
})

test('reorderMessagesForUI places typed hook progress rows around the matching tool trajectory', () => {
  const ordered = reorderMessagesForUI([
    {
      kind: 'progress',
      id: 'hook-post-progress-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'PostToolUse:bash [echo post] completed successfully',
      hookEvent: 'PostToolUse',
      toolId: 'tool-a',
      toolName: 'PostToolUse:bash',
      phase: 'hook_response',
      command: 'echo post',
    },
    {
      kind: 'tool_result',
      id: 'tool-a-result',
      createdAt: '2026-04-02T00:00:01Z',
      text: 'Command: pwd',
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'completed',
      isError: false,
    },
    {
      kind: 'progress',
      id: 'hook-pre-progress-1',
      createdAt: '2026-04-02T00:00:02Z',
      text: 'PreToolUse:bash [echo pre] started',
      hookEvent: 'PreToolUse',
      toolId: 'tool-a',
      toolName: 'PreToolUse:bash',
      phase: 'hook_started',
      command: 'echo pre',
    },
    {
      kind: 'tool_use',
      id: 'assistant-1:tool-a',
      createdAt: '2026-04-02T00:00:03Z',
      text: '{"command":"pwd"}',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
      assistantMessageId: 'assistant-1',
      siblingToolIds: ['tool-a'],
      toolId: 'tool-a',
      toolName: 'bash',
      phase: 'started',
      inputJson: '{"command":"pwd"}',
      command: 'pwd',
    },
  ])

  assert.deepEqual(
    ordered.map((message) => `${message.kind}:${message.id}`),
    [
      'tool_use:assistant-1:tool-a',
      'progress:hook-pre-progress-1',
      'tool_result:tool-a-result',
      'progress:hook-post-progress-1',
    ],
  )
})

test('createLiveRenderableMessages preserves the active tool command for live tool rows', () => {
  const messages = createLiveRenderableMessages({
    liveAssistantText: '',
    liveReasoningText: '',
    liveToolCalls: [{
      toolId: 'tool-1',
      toolName: 'bash',
      phase: 'progress',
      text: 'Running cd /Users/hshrimali-mbp/Desktop && ls',
      command: 'cd /Users/hshrimali-mbp/Desktop && ls',
    }],
    fallbackProviderId: 'openai',
    fallbackModelId: 'gpt-5.3-codex',
  })

  assert.equal(messages.length, 1)
  const liveTool = messages[0]
  assert.ok(liveTool && liveTool.type === 'grouped_tool')
  assert.equal(liveTool.command, 'cd /Users/hshrimali-mbp/Desktop && ls')
  assert.equal(liveTool.detail, 'cd /Users/hshrimali-mbp/Desktop && ls')
})

test('normalizeRenderableMessages emits sibling tool groups in assistant-turn order before grouping', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 1,
      assistantMessageCount: 1,
      totalMessageCount: 8,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'user',
        id: 'user-1',
        createdAt: '2026-04-02T00:00:00Z',
        text: 'Inspect two directories',
      },
      {
        kind: 'assistant',
        id: 'assistant-1',
        createdAt: '2026-04-02T00:00:01Z',
        text: '',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-a',
        createdAt: '2026-04-02T00:00:02Z',
        text: '{"command":"ls src"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls src"}',
        command: 'ls src',
      },
      {
        kind: 'tool',
        id: 'tool-a-progress',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Running ls src',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'progress',
        command: 'ls src',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-b',
        createdAt: '2026-04-02T00:00:04Z',
        text: '{"command":"ls test"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls test"}',
        command: 'ls test',
      },
      {
        kind: 'tool_result',
        id: 'tool-result-a',
        createdAt: '2026-04-02T00:00:05Z',
        text: 'Command: ls src',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'tool_result',
        id: 'tool-result-b',
        createdAt: '2026-04-02T00:00:06Z',
        text: 'Command: ls test',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'completed',
        isError: false,
      },
      {
        kind: 'assistant',
        id: 'assistant-2',
        createdAt: '2026-04-02T00:00:07Z',
        text: 'Done comparing directories.',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 3)
  const grouped = messages[1]
  assert.ok(grouped && grouped.type === 'grouped_tool')
  assert.deepEqual(grouped.toolIds, ['tool-a', 'tool-b'])
  assert.deepEqual(grouped.commands, ['ls src', 'ls test'])
})

test('normalizeRenderableMessages keeps a grouped sibling bash family running while one tool is still active', () => {
  const snapshot: BackendSnapshot = {
    state: {
      activeProvider: 'openai',
      activeModelId: 'gpt-5.3-codex',
      activeSessionId: 'session-test',
      connections: [],
    },
    settings: {
      fastMode: false,
      verboseOutput: true,
      reasoningVisible: true,
      alwaysCopyFullResponse: false,
    },
    session: {
      sessionId: 'session-test',
      startedAt: '2026-04-02T00:00:00Z',
      updatedAt: '2026-04-02T00:00:00Z',
      durationSeconds: 1,
      userMessageCount: 1,
      assistantMessageCount: 0,
      totalMessageCount: 5,
      estimatedContextTokens: 10,
      contextWindowTokens: 200000,
      totalCostUsd: 0,
      planMode: false,
      todos: [],
    },
    providers: [],
    models: [],
    commands: [],
    messages: [
      {
        kind: 'user',
        id: 'user-1',
        createdAt: '2026-04-02T00:00:00Z',
        text: 'Inspect two directories',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-a',
        createdAt: '2026-04-02T00:00:01Z',
        text: '{"command":"ls src"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-a',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls src"}',
        command: 'ls src',
      },
      {
        kind: 'tool_use',
        id: 'assistant-1:tool-b',
        createdAt: '2026-04-02T00:00:02Z',
        text: '{"command":"ls test"}',
        providerId: 'openai',
        modelId: 'gpt-5.3-codex',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'started',
        inputJson: '{"command":"ls test"}',
        command: 'ls test',
      },
      {
        kind: 'tool',
        id: 'tool-b-progress',
        createdAt: '2026-04-02T00:00:03Z',
        text: 'Still running ls test',
        toolId: 'tool-b',
        toolName: 'bash',
        phase: 'progress',
        command: 'ls test',
      },
      {
        kind: 'system',
        id: 'system-1',
        createdAt: '2026-04-02T00:00:04Z',
        text: 'Prompt started',
      },
    ],
  }

  const messages = normalizeRenderableMessages({ snapshot, limit: 10 })
  assert.equal(messages.length, 3)
  const grouped = messages[1]
  assert.ok(grouped && grouped.type === 'grouped_tool')
  assert.equal(grouped.status, 'running')
})
