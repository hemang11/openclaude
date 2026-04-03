import assert from 'node:assert/strict'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'

import React from 'react'

import { App } from '../app.tsx'
import { FakeOpenClaudeClient, appendConversation, createSnapshot } from '../testing/fakeClient.ts'
import { renderApp, sendInput, sendKeypress, sendRawInput, waitFor } from '../testing/testTerminal.ts'
import type { OpenClaudeEvent, OpenClaudeRequest, OpenClaudeResponse } from '../../../types/stdio/protocol.ts'

test('user happy path submits a prompt after backspace and enter from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () =>
        harness.stdout.output.includes('OpenClaude') &&
        harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of ['a', 'b', 'c']) {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\u007f')
    await sendInput(harness.stdin, 'd')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.promptSubmissions.length === 1,
      { message: 'The prompt was not submitted from the TUI.' },
    )

    assert.deepEqual(client.promptSubmissions, ['abd'])
    await waitFor(
      () => harness.stdout.output.includes('Synthetic assistant response.'),
      { message: 'The assistant response never rendered in the Ink transcript.' },
    )
    assert.equal(harness.stdout.output.includes('\u007f'), false)
  } finally {
    await harness.cleanup()
  }
})

test('exact slash commands execute from the Ink TUI on Enter', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/provider') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Providers') &&
        harness.stdout.output.includes('Select a provider, then choose how to connect it.'),
      { message: 'The /provider command did not open the provider picker from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('status line renders provider, model, context, and activity state in the prompt shell', async () => {
  const snapshot = createSnapshot()
  snapshot.session.estimatedContextTokens = 25000
  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () =>
        harness.stdout.output.includes('openai · gpt-5.3-codex · 88% left') &&
        harness.stdout.output.includes('Ready'),
      { message: 'The status line did not render provider/model/context activity details.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/compact no longer advertises an instruction argument hint in slash suggestions', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/compact ') {
      await sendInput(harness.stdin, char)
    }

    await delay(40)

    assert.equal(harness.stdout.output.includes('[instructions]'), false)
  } finally {
    await harness.cleanup()
  }
})

test('partial slash commands accept and execute the selected suggestion on Enter', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/pro') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Providers') &&
        harness.stdout.output.includes('Select a provider, then choose how to connect it.'),
      { message: 'Enter did not accept and execute the selected slash-command suggestion.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('backend panel commands render cost, diff, and doctor panels from the Ink TUI', async () => {
  const expectPanel = async (
    command: string,
    matcher: (output: string) => boolean,
    message: string,
  ) => {
    const client = new FakeOpenClaudeClient(createSnapshot())
    const harness = await renderApp(
      <App
        clientFactory={() => client}
        workspaceFilesLoader={async () => []}
      />,
    )

    try {
      await waitFor(
        () => harness.stdout.output.includes('OpenClaude'),
        { message: 'The Ink app did not finish initializing.' },
      )

      for (const char of command) {
        await sendInput(harness.stdin, char)
      }
      await sendInput(harness.stdin, '\r')

      await waitFor(() => matcher(harness.stdout.output), { message })
    } finally {
      await harness.cleanup()
    }
  }

  await expectPanel(
    '/cost',
    (output) => output.includes('Session timing and estimated context usage.'),
    'The /cost command did not render the cost panel.',
  )
  await expectPanel(
    '/diff',
    (output) => output.includes('Workspace Diff') && output.includes('Uncommitted workspace changes.'),
    'The /diff command did not render the diff panel.',
  )
  await expectPanel(
    '/doctor',
    (output) => output.includes('Doctor') && output.includes('Runtime diagnostics.'),
    'The /doctor command did not render the doctor panel.',
  )
})

test('frontend slash commands cover copy and keybindings flows without touching the real environment', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const copied: string[] = []
  const openedTemplates: string[] = []
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
      clipboardWriter={async (text) => {
        copied.push(text)
      }}
      keybindingsFileOpener={async (template) => {
        openedTemplates.push(template)
        return '/tmp/keybindings.json'
      }}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/copy') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('No assistant response is available to copy yet.'),
      { message: 'The /copy command did not show the empty-transcript status.' },
    )
    assert.equal(copied.length, 0)

    for (const char of '/keybindings') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Opened /tmp/keybindings.json for keybinding customization.'),
      { message: 'The /keybindings command did not use the injected file opener.' },
    )
    assert.equal(openedTemplates.length, 1)
    assert.equal(openedTemplates[0].trim().length > 0, true)
  } finally {
    await harness.cleanup()
  }
})

test('exit slash command prints the resume hint before closing the app', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const exitWrites: string[] = []
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
      exitWriter={(text) => {
        exitWrites.push(text)
      }}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/exit') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        exitWrites.join('').includes('Resume this session with:') &&
        exitWrites.join('').includes('openclaude --resume session-test'),
      { message: 'The /exit command did not print the session resume hint.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('escape dismisses inline prompt suggestions without clearing the current input', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/pro') {
      await sendInput(harness.stdin, char)
    }

    await waitFor(
      () => harness.stdout.frame.includes('Commands'),
      { message: 'Slash-command suggestions did not appear.' },
    )

    await sendRawInput(harness.stdin, '\u001B[27u')
    await sendInput(harness.stdin, '\r')
    await delay(100)

    assert.equal(client.promptSubmissions.length, 0)
    assert.equal(
      harness.stdout.output.includes('Select a provider, then choose how to connect it.'),
      false,
      'Esc did not dismiss suggestions while preserving the current input.',
    )
  } finally {
    await harness.cleanup()
  }
})

test('command argument hints render inline after a trailing space', async () => {
  const snapshot = createSnapshot()
  snapshot.commands = snapshot.commands.map((command) =>
    command.name === 'resume'
      ? { ...command, argumentHint: '<session-id>' }
      : command,
  )
  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/resume ') {
      await sendInput(harness.stdin, char)
    }

    await waitFor(
      () => harness.stdout.frame.includes('<session-id>'),
      { message: 'Inline command argument hint did not render after the command name.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('quoted @ file suggestions accept on Tab with Claude-style quoting', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => ['docs/my file.md']}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'read @"docs/my') {
      await sendInput(harness.stdin, char)
    }

    await waitFor(
      () => harness.stdout.frame.includes('@"docs/my file.md"'),
      { message: 'Quoted file suggestion did not appear.' },
    )

    await sendKeypress(harness.stdin, { name: 'tab' })

    await waitFor(
      () => harness.stdout.frame.includes('read @"docs/my file.md" '),
      { message: 'Tab did not apply the quoted file suggestion.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('Tab applies @ file common-prefix completion around the cursor without dropping the suffix', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => ['src/components/App.tsx', 'src/components/Button.tsx']}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'read @src/comp later') {
      await sendInput(harness.stdin, char)
    }
    for (let index = 0; index < 'mp later'.length; index += 1) {
      await sendKeypress(harness.stdin, { name: 'leftArrow' })
    }

    await waitFor(
      () => harness.stdout.frame.includes('File Paths'),
      { message: 'File suggestions did not appear at the cursor position.' },
    )

    await sendKeypress(harness.stdin, { name: 'tab' })

    await waitFor(
      () => harness.stdout.frame.includes('read @src/components/ later'),
      { message: 'Tab did not apply the common-prefix completion around the cursor.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ctrl+p and ctrl+n fall back to project prompt history when suggestions are inactive', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
      promptHistoryLoader={async () => [
        { value: 'second project prompt', createdAt: 20 },
        { value: 'older project prompt', createdAt: 10 },
      ]}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendKeypress(harness.stdin, { name: 'p', sequence: 'p', ctrl: true })
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 1)
    assert.equal(client.promptSubmissions[0], 'second project prompt')

    await sendKeypress(harness.stdin, { name: 'p', sequence: 'p', ctrl: true })
    await sendKeypress(harness.stdin, { name: 'n', sequence: 'n', ctrl: true })
    for (const char of 'fresh') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 2)
    assert.equal(client.promptSubmissions[1], 'fresh')
  } finally {
    await harness.cleanup()
  }
})

test('typing slash, backspace, and slash again does not restack the startup header', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    let offset = harness.stdout.output.length
    await sendInput(harness.stdin, '/')

    await waitFor(
      () => harness.stdout.output.slice(offset).includes('Commands'),
      { message: 'Typing / did not open the inline command suggestions.' },
    )

    let currentDelta = harness.stdout.output.slice(offset)
    assert.equal(currentDelta.includes('Tips for getting started'), false)

    offset = harness.stdout.output.length
    await sendInput(harness.stdin, '\u007f')

    await waitFor(
      () =>
        harness.stdout.output.slice(offset).includes('Tips for getting started')
        && !harness.stdout.output.slice(offset).includes('Commands'),
      { message: 'Backspacing / did not restore the idle startup header.' },
    )

    offset = harness.stdout.output.length
    await sendInput(harness.stdin, '/')

    await waitFor(
      () => harness.stdout.output.slice(offset).includes('Commands'),
      { message: 'Typing / again did not reopen the inline command suggestions.' },
    )

    currentDelta = harness.stdout.output.slice(offset)
    assert.equal(currentDelta.includes('Tips for getting started'), false)
  } finally {
    await harness.cleanup()
  }
})

test('typing a normal prompt keeps the startup header visible until a real turn starts', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'H')

    await waitFor(
      () => harness.stdout.output.includes('Tips for getting started') && harness.stdout.output.includes('Recent activity'),
      { message: 'Typing a normal prompt hid the startup header before the turn started.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('typing ! into an empty prompt enters bash mode and submits a bang-prefixed prompt', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '!pwd') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.promptSubmissions.length === 1,
      { message: 'The bash-mode prompt was not submitted from the TUI.' },
    )

    assert.deepEqual(client.promptSubmissions, ['!pwd'])
  } finally {
    await harness.cleanup()
  }
})

test('ctrl+r opens prompt-local history search and tab accepts the selected prompt into the composer', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'first prompt') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 1)

    for (const char of 'alpha prompt') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 2)

    for (const char of 'alp') {
      await sendInput(harness.stdin, char)
    }
    await sendKeypress(harness.stdin, { name: 'r', sequence: 'r', ctrl: true })

    await waitFor(
      () =>
        harness.stdout.frame.includes('search prompts:')
        && harness.stdout.frame.includes('alpha prompt'),
      { message: 'Ctrl+R did not open prompt-local history search.' },
    )

    await sendKeypress(harness.stdin, { name: 'tab' })
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 3)
    assert.equal(client.promptSubmissions[2], 'alpha prompt')
  } finally {
    await harness.cleanup()
  }
})

test('bash-mode arrow history only traverses bash entries', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'prompt one') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 1)

    for (const char of '!ls') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 2)

    for (const char of 'prompt two') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 3)
    assert.equal(client.promptSubmissions[2], 'prompt two')

    await sendInput(harness.stdin, '!')
    await sendKeypress(harness.stdin, { name: 'upArrow' })
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 4)
    assert.equal(client.promptSubmissions[3], '!ls')
  } finally {
    await harness.cleanup()
  }
})

test('bash-mode down restores the empty bash draft before submit', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'prompt one') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 1)

    for (const char of '!ls') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 2)

    for (const char of 'prompt two') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 3)
    assert.equal(client.promptSubmissions[2], 'prompt two')

    await sendInput(harness.stdin, '!')
    await sendKeypress(harness.stdin, { name: 'upArrow' })
    await sendKeypress(harness.stdin, { name: 'downArrow' })
    for (const char of 'pwd') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 4)
    assert.equal(client.promptSubmissions[3], '!pwd')
  } finally {
    await harness.cleanup()
  }
})

test('up arrow can restore preloaded project history in a fresh UI session', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
      promptHistoryLoader={async () => [
        { value: 'fresh current-session prompt', createdAt: 20 },
        { value: 'older project prompt', createdAt: 10 },
      ]}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendKeypress(harness.stdin, { name: 'upArrow' })
    await sendInput(harness.stdin, '\r')
    await waitFor(() => client.promptSubmissions.length === 1)
    assert.equal(client.promptSubmissions[0], 'fresh current-session prompt')
  } finally {
    await harness.cleanup()
  }
})

test('/login opens the provider picker from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/login') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Providers') &&
        harness.stdout.output.includes('Select a provider, then choose how to connect it.'),
      { message: 'The /login command did not open the provider picker from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/logout disconnects the active provider from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/logout') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.state.activeProvider === null,
      { message: 'The /logout command did not disconnect the active provider.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Disconnected openai'),
      { message: 'The /logout command did not render the disconnect status from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/models opens the model picker from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/models') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Models') &&
        harness.stdout.output.includes('GPT-5.3 Codex'),
      { message: 'The /models command did not open the model picker from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/resume argument suggestions accept on Enter and resume the selected session', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/resume sess') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.session.sessionId === 'session-older',
      { message: 'The inline /resume argument suggestion did not resume the selected session.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Resumed session Summarize desktop repo.'),
      { message: 'The resumed-session status did not render.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/models argument suggestions accept on Enter and select the scoped model token', async () => {
  const snapshot = createSnapshot()
  snapshot.providers = [
    ...snapshot.providers,
    {
      providerId: 'anthropic',
      displayName: 'Anthropic',
      supportedAuthMethods: ['api_key'],
      connected: true,
      active: false,
      connection: {
        providerId: 'anthropic',
        authMethod: 'api_key',
        credentialReference: 'anthropic/default',
        connectedAt: '2026-04-02T00:00:00Z',
      },
    },
  ]
  snapshot.state.connections = [
    ...snapshot.state.connections,
    {
      providerId: 'anthropic',
      authMethod: 'api_key',
      credentialReference: 'anthropic/default',
      connectedAt: '2026-04-02T00:00:00Z',
    },
  ]
  snapshot.models = [
    ...snapshot.models,
    {
      id: 'claude-sonnet-4',
      displayName: 'Claude Sonnet 4',
      providerId: 'anthropic',
      providerDisplayName: 'Anthropic',
      providerActive: false,
      active: false,
    },
  ]

  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/models claude') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.state.activeModelId === 'claude-sonnet-4',
      { message: 'The inline /models argument suggestion did not select the scoped model.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Selected model claude-sonnet-4'),
      { message: 'The selected-model status did not render.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/memory opens the memory picker and launches the selected editor target', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const openedPaths: string[] = []
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
      memoryFilesLoader={async () => [
        {
          path: '/tmp/openclaude-memory/AGENTS.md',
          displayPath: '~/.openclaude/AGENTS.md',
          label: 'User memory',
          detail: 'Saved in ~/.openclaude/AGENTS.md',
          exists: false,
        },
      ]}
      memoryFileEditor={async (filePath) => {
        openedPaths.push(filePath)
      }}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/memory') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Memory') &&
        harness.stdout.output.includes('Choose an AGENTS memory file to edit.') &&
        harness.stdout.output.includes('User memory'),
      { message: 'The /memory command did not open the memory picker.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => openedPaths[0] === '/tmp/openclaude-memory/AGENTS.md',
      { message: 'Selecting a memory file did not launch the external editor target.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Opened memory file at ~/.openclaude/AGENTS.md.'),
      { message: 'The memory-open confirmation did not render.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/config opens the config picker from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/config') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Config') &&
        harness.stdout.output.includes('Fast mode: OFF') &&
        harness.stdout.output.includes('Reasoning visible: ON'),
      { message: 'The /config command did not open the config picker from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/help opens the local help panel from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/help') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Help') &&
        harness.stdout.output.includes('/resume') &&
        harness.stdout.output.includes('/provider'),
      { message: 'The /help command did not open the help panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/context opens the backend context panel from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(appendConversation(createSnapshot(), 'Inspect repo', 'Working on it.'))
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/context') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Context') &&
        harness.stdout.output.includes('Estimated input context: 128 tokens') &&
        harness.stdout.output.includes('Projected prompt messages: 4'),
      { message: 'The /context command did not open the backend context panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/fast toggles fast mode from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/fast') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.settings.fastMode === true,
      { message: 'The /fast command did not toggle fast mode.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Fast mode ON.'),
      { message: 'The /fast command did not render the expected status update.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/effort sets the configured effort level from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/effort high') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.settings.effortLevel === 'high',
      { message: 'The /effort command did not update the effort setting.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Set effort level to high'),
      { message: 'The /effort command did not render the expected status update.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/compact executes from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(
    appendConversation(createSnapshot(), 'Review the repo', 'I inspected the workspace.'),
  )
  const originalRequest = client.request.bind(client) as (
    method: OpenClaudeRequest['method'],
    params?: OpenClaudeRequest['params'],
  ) => Promise<OpenClaudeResponse>
  client.request = (async (method: OpenClaudeRequest['method'], params?: OpenClaudeRequest['params']) => {
    if (
      method === 'command.run'
      && ((params as { commandName?: string } | undefined)?.commandName === 'compact')
    ) {
      await delay(50)
    }
    return originalRequest(method, params)
  }) as typeof client.request
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/compact') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Compacting…'),
      { message: 'The /compact command did not render the in-flight compacting indicator.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Compacted conversation history into a summary.'),
      { message: 'The /compact command did not render the expected status update.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Conversation compacted')
        && harness.stdout.output.includes('This session is being continued from a previous conversation'),
      { message: 'The /compact command did not render the compact boundary and summary.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/compact is rejected while a tool is still in progress', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  client.startRequest = ((method, params, onEvent) => {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      client.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'prompt-running',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'tool-running',
        event: 'prompt.tool.started',
        data: {
          toolId: 'tool-1',
          toolName: 'bash',
          phase: 'started',
          command: 'pwd',
          text: 'pwd',
        },
      })
      return {
        id: 'fake-running',
        promise: new Promise<OpenClaudeResponse['result']>(() => {}),
      }
    }
    return FakeOpenClaudeClient.prototype.startRequest.call(client, method, params, onEvent)
  }) as typeof client.startRequest

  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'run something long') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Working with 1 live tool'),
      { message: 'The running tool state did not render before compact was attempted.' },
    )

    for (const char of '/compact') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Cannot run /compact while a tool is still in progress.'),
      { message: 'The Ink app did not reject /compact while a tool was running.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('prompt submission is rejected while /compact is in progress', async () => {
  const client = new FakeOpenClaudeClient(
    appendConversation(createSnapshot(), 'Review the repo', 'I inspected the workspace.'),
  )
  let releaseCompact = () => {}
  const compactBlocked = new Promise<void>((resolve) => {
    releaseCompact = resolve
  })
  const originalRequest = client.request.bind(client) as (
    method: OpenClaudeRequest['method'],
    params?: OpenClaudeRequest['params'],
  ) => Promise<OpenClaudeResponse>
  client.request = (async (method: OpenClaudeRequest['method'], params?: OpenClaudeRequest['params']) => {
    if (
      method === 'command.run'
      && ((params as { commandName?: string } | undefined)?.commandName === 'compact')
    ) {
      await compactBlocked
    }
    return originalRequest(method, params)
  }) as typeof client.request

  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/compact') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Compacting…'),
      { message: 'The compacting indicator did not render before testing prompt submission.' },
    )

    for (const char of 'hello after compact') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Cannot submit a new prompt while compaction is in progress.'),
      { message: 'The Ink app did not reject prompt submission while compaction was active.' },
    )

    releaseCompact()

    await waitFor(
      () => harness.stdout.output.includes('Conversation compacted'),
      { message: 'The compact command did not finish after the submission guard was exercised.' },
    )
  } finally {
    releaseCompact()
    await harness.cleanup()
  }
})

test('/tasks toggles the task panel from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/tasks') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Background task panel shown.') &&
        harness.stdout.output.includes('Tasks') &&
        harness.stdout.output.includes('No session todos yet.'),
      { message: 'The /tasks command did not toggle the task panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/clear starts a fresh session from the Ink TUI', async () => {
  const snapshot = appendConversation(createSnapshot(), 'Old prompt', 'Old response')
  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/clear') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Cleared conversation history and started a new session.'),
      { message: 'The /clear command did not report a fresh session from the TUI.' },
    )

    await waitFor(
      () =>
        client.snapshot.session.sessionId === 'session-cleared' &&
        client.snapshot.messages.length === 0 &&
        harness.stdout.output.includes('Ask OpenClaude to work on the current repository') &&
        !extractTranscriptFrame(harness.stdout.output).includes('Old response'),
      { message: 'The /clear command did not start a fresh session from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/plan enters plan mode and submits the inline description from the Ink TUI', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/plan sketch the migration') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.session.planMode === true && client.promptSubmissions.length === 1,
      { message: 'The /plan command did not enter plan mode and submit the description.' },
    )

    assert.deepEqual(client.promptSubmissions, ['sketch the migration'])
    await waitFor(
      () =>
        harness.stdout.output.includes('Enabled plan mode.') &&
        harness.stdout.output.includes('Synthetic assistant response.'),
      { message: 'The /plan command did not render the planning turn from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/status opens the backend status panel from the Ink TUI', async () => {
  const client = new StatusPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/status') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Status') &&
        harness.stdout.output.includes('Active provider: OpenAI') &&
        harness.stdout.output.includes('OpenAI (openai) — active via browser_sso'),
      { message: 'The /status command did not render the status panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/session opens the backend session panel from the Ink TUI', async () => {
  const client = new SessionPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/session') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Session') &&
        harness.stdout.output.includes('Current session') &&
        harness.stdout.output.includes('Workspace root: /tmp/workspace'),
      { message: 'The /session command did not render the session panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/permissions opens the permissions overlay from the Ink TUI', async () => {
  const client = new PermissionsPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/permissions') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.permissionEditorSnapshotRequests === 1 &&
        harness.stdout.output.includes('Permissions') &&
        harness.stdout.output.includes('Recent activity') &&
        harness.stdout.output.includes('Allow') &&
        harness.stdout.output.includes('Ask') &&
        harness.stdout.output.includes('Deny') &&
        harness.stdout.output.includes('Workspace'),
      { message: 'The blank /permissions command did not open the permissions editor from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/permissions can delete a rule from the allow tab', async () => {
  const client = new PermissionsPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/permissions') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await waitFor(
      () => harness.stdout.output.includes('Recent activity'),
      { message: 'The permissions overlay did not open.' },
    )

    await sendInput(harness.stdin, '\t')
    await waitFor(
      () => harness.stdout.output.includes('Current session') && harness.stdout.output.includes('Bash(ls -1 ~/Desktop)'),
      { message: 'The allow tab never opened.' },
    )

    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\r')
    await waitFor(
      () => harness.stdout.output.includes('Delete allowed tool?'),
      { message: 'Selecting an allow rule did not open the delete confirmation.' },
    )

    await sendInput(harness.stdin, '\r')
    await waitFor(
      () => client.permissionMutations.includes('remove:session:allow:Bash(ls -1 ~/Desktop)'),
      { message: 'Confirming delete did not invoke the permissions remove mutation.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/permissions retry-denials runs from the recent tab', async () => {
  const client = new PermissionsPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/permissions') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Recent activity'),
      { message: 'The permissions overlay did not open.' },
    )

    await sendInput(harness.stdin, 'r')

    await waitFor(
      () => client.permissionMutations.includes('retry-denials'),
      { message: 'Retrying denied permissions did not invoke the retry-denials mutation.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/usage opens the backend usage panel from the Ink TUI', async () => {
  const client = new UsagePanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/usage') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Usage') &&
        harness.stdout.output.includes('Plan usage limits for the active provider/account.') &&
        harness.stdout.output.includes('Provider: OpenAI'),
      { message: 'The /usage command did not render the usage panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/stats opens the backend statistics panel from the Ink TUI', async () => {
  const client = new StatsCommandPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/stats') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Stats') &&
        harness.stdout.output.includes('OpenClaude usage statistics and activity.') &&
        harness.stdout.output.includes('Total sessions: 12'),
      { message: 'The /stats command did not render the statistics panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/resume opens the session picker and can resume a scoped session', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/resume') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Resume') &&
        harness.stdout.output.includes('Summarize desktop repo'),
      { message: 'The /resume command did not open the session picker.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Resumed session Summarize desktop repo.'),
      { message: 'Selecting a resumable session did not switch the active session.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/rename with an explicit title persists the new session name', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/rename Repo summary session') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.snapshot.session.title === 'Repo summary session',
      { message: 'Submitting the rename overlay did not update the session title.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('/rewind opens the checkpoint picker and restores the selected user prompt', async () => {
  const snapshot = appendConversation(
    appendConversation(createSnapshot(), 'first prompt', 'first answer'),
    'second prompt',
    'second answer',
  )
  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/rewind') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Rewind') &&
        harness.stdout.output.includes('Restore the conversation to just before the selected user turn.'),
      { message: 'The /rewind command did not open the checkpoint picker.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.snapshot.messages.length === 2 &&
        extractTranscriptFrame(harness.stdout.output).includes('first answer') &&
        !extractTranscriptFrame(harness.stdout.output).includes('second answer') &&
        harness.stdout.output.includes('Rewound conversation to the selected checkpoint.') &&
        harness.stdout.output.includes('> second prompt'),
      { message: 'The /rewind command did not restore the selected prompt from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('tools slash command opens the backend tools panel from the Ink TUI', async () => {
  const client = new ToolsPanelClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of '/tools') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Tools') &&
        harness.stdout.output.includes('bash') &&
        harness.stdout.output.includes('Tool count: 12'),
      { message: 'The /tools command did not render the tools panel from the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ask-user-question tool collects an answer and sends it back through the TUI', async () => {
  const client = new AskUserQuestionClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'help me choose') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Approach') &&
        harness.stdout.output.includes('Which implementation path should OpenClaude take?'),
      { message: 'The AskUserQuestion overlay never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Review your answers'),
      { message: 'The AskUserQuestion review step never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.submittedAnswers['Which implementation path should OpenClaude take?'] === 'Safer refactor',
      { message: 'The AskUserQuestion answer payload was not submitted back through the TUI.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Question-backed response.'),
      { message: 'The prompt never resumed after answering AskUserQuestion.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ask-user-question preview flow sends the selected preview annotation back through the TUI', async () => {
  const client = new PreviewAskUserQuestionClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'compare approaches') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Architecture') &&
        harness.stdout.output.includes('Preview') &&
        harness.stdout.output.includes('Incremental rollout with checkpoints.'),
      { message: 'The preview AskUserQuestion overlay never rendered the preview panel.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Review your answers'),
      { timeoutMs: 10000, message: 'The preview AskUserQuestion review step never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.submittedAnnotations['Which architecture should OpenClaude take?']?.preview
          === '## Safer refactor\nIncremental rollout with checkpoints.',
      { message: 'The preview annotation was not sent back through the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ask-user-question preview flow captures notes and returns them with the selected preview', async () => {
  const client = new PreviewAskUserQuestionClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'compare approaches with notes') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Architecture') &&
        harness.stdout.output.includes('press n to add notes'),
      { message: 'The preview AskUserQuestion overlay never showed the notes affordance.' },
    )

    await sendInput(harness.stdin, 'n')
    for (const char of 'Prefer the safer rollout') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await delay(40)
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Review your answers'),
      { message: 'The preview AskUserQuestion notes flow never reached review.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.submittedAnnotations['Which architecture should OpenClaude take?']?.preview
          === '## Safer refactor\nIncremental rollout with checkpoints.'
        && client.submittedAnnotations['Which architecture should OpenClaude take?']?.notes
          === 'Prefer the safer rollout',
      { message: 'The preview AskUserQuestion notes were not submitted with the selected preview.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ask-user-question multi-select flow returns comma-separated answers through the TUI', async () => {
  const client = new MultiSelectAskUserQuestionClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'pick features') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Features') &&
        harness.stdout.output.includes('Which features should OpenClaude enable first?'),
      { message: 'The multi-select AskUserQuestion overlay never appeared.' },
    )

    await sendInput(harness.stdin, '\r')
    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\r')
    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\r')
    for (const char of 'Custom workflow') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Review your answers'),
      { message: 'The multi-select AskUserQuestion review step never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.submittedAnswers['Which features should OpenClaude enable first?']
          === 'WebSearch, TodoWrite, Custom workflow',
      { message: 'The multi-select AskUserQuestion answers were not submitted as a comma-separated string.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ask-user-question footer can send chat-about-this feedback back through the TUI', async () => {
  const client = new AskUserQuestionClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'clarify the approach') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Chat about this'),
      { message: 'The AskUserQuestion footer never rendered.' },
    )

    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\u001B[B')

    await waitFor(
      () => harness.stdout.output.includes('› Chat about this'),
      { timeoutMs: 5000, message: 'The AskUserQuestion footer focus never moved onto Chat about this.' },
    )
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.deniedFeedbacks.some((feedback) =>
          feedback.includes('The user wants to clarify these questions.')
          && feedback.includes('Start by asking them what they would like to clarify.'),
        ),
      { message: 'The AskUserQuestion footer did not send the chat-about-this feedback payload.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Question-backed response.'),
      { message: 'The prompt never resumed after selecting the chat-about-this footer action.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('ask-user-question footer can finish the plan interview in plan mode', async () => {
  const snapshot = createSnapshot()
  snapshot.session.planMode = true
  const client = new AskUserQuestionClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'finish the plan interview') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Skip interview and plan immediately'),
      { message: 'The plan-mode AskUserQuestion footer did not render the finish-plan action.' },
    )

    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\u001B[B')
    await sendInput(harness.stdin, '\u001B[B')

    await waitFor(
      () => harness.stdout.output.includes('› Skip interview and plan immediately'),
      { timeoutMs: 5000, message: 'The AskUserQuestion plan footer focus never moved onto the finish-plan action.' },
    )
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        client.deniedFeedbacks.some((feedback) =>
          feedback.includes('The user has indicated they have provided enough answers for the plan interview.')
          && feedback.includes('Stop asking clarifying questions and proceed to finish the plan'),
        ),
      { message: 'The AskUserQuestion plan footer did not send the finish-plan feedback payload.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Question-backed response.'),
      { message: 'The prompt never resumed after selecting the finish-plan footer action.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('tool happy path can approve a bash tool call while a prompt is still running', async () => {
  const client = new PermissionOverlayClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'list desktop folders') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow bash?') && harness.stdout.output.includes('pwd'),
      { message: 'The permission overlay never appeared for the tool request.' },
    )

    const output = harness.stdout.output
    const overlayOffset = output.lastIndexOf('Allow bash?')
    const overlayFrame = overlayOffset >= 0 ? output.slice(overlayOffset) : output
    assert.equal(overlayFrame.includes('› Allow: Run this tool call.'), true)
    assert.equal(overlayFrame.includes('Deny: Block this tool call.'), true)

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.includes('allow'),
      { message: 'The permission approval was not sent back through the TUI.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Tool-backed response.'),
      { message: 'The prompt never resumed after approving the tool call.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('permission overlay accepts raw enter to approve a tool call', async () => {
  const client = new PermissionOverlayClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'read this article') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow bash?') && harness.stdout.output.includes('pwd'),
      { message: 'The permission overlay never appeared for the tool request.' },
    )

    await sendRawInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.includes('allow'),
      { message: 'The raw enter approval was not sent back through the TUI.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('permission overlay hides prompt chrome while it is active', async () => {
  const client = new PermissionOverlayClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'summarize my desktop files') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow bash?') && harness.stdout.output.includes('pwd'),
      { message: 'The permission overlay never appeared for the tool request.' },
    )

    const output = harness.stdout.output
    const overlayOffset = output.lastIndexOf('Allow bash?')
    const overlayFrame = overlayOffset >= 0 ? output.slice(overlayOffset) : output
    assert.equal(overlayFrame.includes('Allow bash?'), true)
    assert.equal(overlayFrame.includes('› Allow: Run this tool call.'), true)
    assert.equal(overlayFrame.includes('/ commands  @ files'), false)
    assert.equal(overlayFrame.includes('Enter submit'), false)
    assert.equal(overlayFrame.includes('Thinking'), false)
  } finally {
    await harness.cleanup()
  }
})

test('approving a tool once in a run reuses that decision for later requests from the same tool', async () => {
  const client = new MultiPermissionOverlayClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'inspect another folder') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow bash?') && harness.stdout.output.includes('pwd'),
      { message: 'The first permission request never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.join(',') === 'allow,allow',
      { message: 'The later tool request did not reuse the earlier approval.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Queued tool response.'),
      { message: 'The prompt never completed after reusing the tool approval.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('repeated identical permission requests in one run are auto-responded without reopening the overlay', async () => {
  const client = new RepeatedPermissionOverlayClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'check live score') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow WebSearch?'),
      { message: 'The initial permission request never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.join(',') === 'allow,allow',
      { message: 'The repeated permission request was not auto-responded.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Repeated permission response.'),
      { message: 'The prompt never completed after the repeated permission request.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('approved permission decisions persist across prompt runs without reopening the overlay', async () => {
  const client = new CrossRunPermissionCacheClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'check live score') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow WebSearch?'),
      { message: 'The first permission overlay never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.join(',') === 'allow',
      { message: 'The first permission approval was not recorded.' },
    )

    for (const char of 'check score again') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.join(',') === 'allow,allow',
      { message: 'The cached permission decision was not reused on the next prompt run.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Second run reused the cached permission decision.'),
      { message: 'The second prompt never completed after the cached permission decision.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('queued identical permission requests are drained together after one approval', async () => {
  const client = new QueuedDuplicatePermissionOverlayClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'fetch live score') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Allow WebSearch?'),
      { message: 'The initial permission request never appeared.' },
    )

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.permissionDecisions.join(',') === 'allow,allow',
      { message: 'The queued duplicate permission requests were not both answered.' },
    )

    await waitFor(
      () => harness.stdout.output.includes('Queued duplicate permission response.'),
      { message: 'The prompt never completed after approving the queued duplicate request.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('bracketed paste inserts the pasted text without leaking terminal escape gibberish', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendRawInput(harness.stdin, '\u001b[200~first line\r\nsecond line\u001b[201~')
    await waitFor(
      () => harness.stdout.output.includes('first line') && harness.stdout.output.includes('second line'),
      { message: 'The bracketed paste did not land in the prompt.' },
    )
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.promptSubmissions.length === 1,
      { message: 'The bracketed paste prompt was not submitted from the TUI.' },
    )

    assert.deepEqual(client.promptSubmissions, ['first line\nsecond line'])
    assert.equal(harness.stdout.output.includes('[200~'), false)
    assert.equal(harness.stdout.output.includes('[201~'), false)
  } finally {
    await harness.cleanup()
  }
})

test('plain multiline paste is treated as pasted input instead of submitting on embedded returns', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendRawInput(harness.stdin, 'first line\r\nsecond line')
    await waitFor(
      () => harness.stdout.output.includes('first line') && harness.stdout.output.includes('second line'),
      { message: 'The plain multiline paste did not land in the prompt.' },
    )

    assert.equal(client.promptSubmissions.length, 0)

    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.promptSubmissions.length === 1,
      { message: 'The plain multiline pasted prompt was not submitted from the TUI.' },
    )

    assert.deepEqual(client.promptSubmissions, ['first line\nsecond line'])
  } finally {
    await harness.cleanup()
  }
})

test('footer help renders below the prompt block', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    const output = harness.stdout.output
    const promptIndex = output.lastIndexOf('> ')
    const footerIndex = output.lastIndexOf('/ commands')

    assert.notEqual(promptIndex, -1)
    assert.notEqual(footerIndex, -1)
    assert.ok(footerIndex > promptIndex, 'Expected footer help to render below the prompt block.')
  } finally {
    await harness.cleanup()
  }
})

test('startup header renders the banner plus tips and recent activity blocks', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    const output = harness.stdout.output
    assert.equal(output.includes('OpenClaude'), true)
    assert.equal(output.includes('Tips for getting started'), true)
    assert.equal(output.includes('Recent activity'), true)
    assert.equal(output.includes('/models review connected models'), true)
    assert.equal(output.includes('No recent activity'), true)
    assert.equal(output.includes('`/models`'), false)
  } finally {
    await harness.cleanup()
  }
})

test('recent activity header renders inline markdown without raw backticks', async () => {
  const snapshot = createSnapshot()
  snapshot.messages = [
    {
      kind: 'assistant',
      id: 'assistant-header-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'Use `rg --files` before running tests.',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
  ]
  snapshot.session.assistantMessageCount = 1
  snapshot.session.totalMessageCount = 1

  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('Recent activity'),
      { message: 'The startup header did not render.' },
    )

    const output = harness.stdout.output
    assert.equal(output.includes('rg --files'), true)
    assert.equal(output.includes('`rg --files`'), false)
  } finally {
    await harness.cleanup()
  }
})

test('startup home keeps restored transcript details out of the main transcript area', async () => {
  const snapshot = createSnapshot()
  snapshot.messages = [
    {
      kind: 'user',
      id: 'user-old-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'Old greeting from the restored session',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
    {
      kind: 'assistant',
      id: 'assistant-old-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'Old provider failure details that should not flood the startup screen SENTINEL_TRANSCRIPT_DETAIL',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
  ]
  snapshot.session.userMessageCount = 1
  snapshot.session.assistantMessageCount = 1
  snapshot.session.totalMessageCount = 2

  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('Recent activity'),
      { message: 'The startup home did not render.' },
    )

    const output = harness.stdout.output
    assert.equal(output.includes('Recent activity'), true)
    assert.equal(output.includes('SENTINEL_TRANSCRIPT_DETAIL'), false)
  } finally {
    await harness.cleanup()
  }
})

test('model lifecycle status spam is suppressed from the visible status area', async () => {
  const client = new StatusNoiseClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => client.promptSubmissions.length === 1,
      { message: 'The prompt was not submitted from the TUI.' },
    )

    const output = harness.stdout.output
    assert.equal(output.includes('Model response in progress'), false)
    assert.equal(output.includes('Model response completed'), false)
    assert.equal(output.includes('Prompt started'), false)
  } finally {
    await harness.cleanup()
  }
})

test('Jackson end-of-input stderr noise is suppressed from the visible status area', async () => {
  const client = new FakeOpenClaudeClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    client.emit('stderr', 'No content to map due to end-of-input')
    client.emit('stderr', ' at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1]')

    await delay(50)

    const output = harness.stdout.output
    assert.equal(output.includes('No content to map due to end-of-input'), false)
    assert.equal(output.includes('StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION'), false)
  } finally {
    await harness.cleanup()
  }
})

test('Jackson end-of-input error noise is suppressed from thrown backend errors too', async () => {
  const client = new EndOfInputErrorClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('OpenClaude backend request failed.'),
      { message: 'The sanitized fallback error was not shown.' },
    )

    const output = harness.stdout.output
    assert.equal(output.includes('No content to map due to end-of-input'), false)
    assert.equal(output.includes('StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION'), false)
  } finally {
    await harness.cleanup()
  }
})

test('streaming thinking renders above the prompt area while the model is working', async () => {
  const client = new StreamingThinkingClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Inspecting the workspace before answering.'),
      { message: 'Streaming thinking never rendered in the TUI.' },
    )

    assert.equal(harness.stdout.output.includes('Thinking'), true)

    const output = harness.stdout.output
    const transcriptFrame = extractTranscriptFrame(output)
    const userQuestionIndex = transcriptFrame.lastIndexOf('\n hi\n')
    const thinkingIndex = transcriptFrame.lastIndexOf('Inspecting the workspace before answering.')
    const promptIndex = output.lastIndexOf('Ask OpenClaude to work on the current repository')

    assert.notEqual(userQuestionIndex, -1)
    assert.notEqual(thinkingIndex, -1)
    assert.notEqual(promptIndex, -1)
    assert.ok(userQuestionIndex < thinkingIndex, 'Expected the submitted user prompt to stay visible above the thinking block.')
    assert.ok(thinkingIndex < promptIndex, 'Expected the streaming thinking block to render above the prompt area.')
  } finally {
    await harness.cleanup()
  }
})

test('live tool rows suppress the empty-state text and show the active command', async () => {
  const client = new LiveToolRenderingClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'count desktop folders') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes("cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l"),
      { message: 'The active bash command never rendered in the transcript.' },
    )

    const output = harness.stdout.output
    const frame = extractTranscriptFrame(output)
    const commandIndex = output.lastIndexOf("cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l")
    assert.ok(output.lastIndexOf('No conversation yet.') < commandIndex)
    assert.ok(output.lastIndexOf('Send a prompt when you are ready.') < commandIndex)
    assert.equal(frame.includes('bash running'), true)

    await waitFor(
      () => harness.stdout.output.includes('Desktop folder count response.'),
      { message: 'The prompt never completed after the live tool run.' },
    )
  } finally {
    await harness.cleanup()
  }
})

test('working indicator stays visible during tool activity until the first text token arrives', async () => {
  const client = new LiveToolRenderingClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    for (const char of 'count desktop folders') {
      await sendInput(harness.stdin, char)
    }
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Working') &&
        harness.stdout.output.includes("cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l"),
      { message: 'The working indicator did not stay visible while the tool was running.' },
    )

    const frame = extractTranscriptFrame(harness.stdout.output)
    assert.equal(frame.includes('Working'), true)
    assert.equal(frame.includes('Desktop folder count response.'), false)
  } finally {
    await harness.cleanup()
  }
})

test('busy UI shows a front-end working indicator before reasoning or text deltas arrive', async () => {
  const client = new SilentBusyClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Working'),
      { message: 'The busy working indicator never appeared before streaming output.' },
    )

    const frame = extractTranscriptFrame(harness.stdout.output)
    assert.equal(frame.includes('Working'), true)
    assert.equal(frame.includes('Delayed final response.'), false)
  } finally {
    await harness.cleanup()
  }
})

test('restored history stays out of the main transcript after a new prompt is sent', async () => {
  const snapshot = createSnapshot()
  snapshot.messages = [
    {
      kind: 'user',
      id: 'restored-user-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'RESTORED_USER_SENTINEL',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
    {
      kind: 'assistant',
      id: 'restored-assistant-1',
      createdAt: '2026-04-02T00:00:00Z',
      text: 'RESTORED_ASSISTANT_SENTINEL',
      providerId: 'openai',
      modelId: 'gpt-5.3-codex',
    },
  ]
  snapshot.session.userMessageCount = 1
  snapshot.session.assistantMessageCount = 1
  snapshot.session.totalMessageCount = 2

  const client = new FakeOpenClaudeClient(snapshot)
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () => harness.stdout.output.includes('Synthetic assistant response.'),
      { message: 'The new assistant response never rendered in the transcript.' },
    )

    const transcriptFrame = extractTranscriptFrame(harness.stdout.output)

    assert.equal(transcriptFrame.includes('RESTORED_USER_SENTINEL'), false)
    assert.equal(transcriptFrame.includes('RESTORED_ASSISTANT_SENTINEL'), false)
    assert.equal(transcriptFrame.includes('Synthetic assistant response.'), true)
  } finally {
    await harness.cleanup()
  }
})

test('assistant markdown renders as terminal content instead of raw markdown markers', async () => {
  const client = new MarkdownResponseClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Plan') &&
        harness.stdout.output.includes('• inspect the repo') &&
        harness.stdout.output.includes('rg --files'),
      { message: 'Rendered markdown did not appear in the TUI.' },
    )

    const output = harness.stdout.output
    const lastFrameOffset = output.lastIndexOf('OpenClaude')
    const finalFrame = lastFrameOffset >= 0 ? output.slice(lastFrameOffset) : output

    assert.equal(finalFrame.includes('## Plan'), false)
    assert.equal(finalFrame.includes('- inspect the repo'), false)
    assert.equal(finalFrame.includes('`rg --files`'), false)
  } finally {
    await harness.cleanup()
  }
})

test('assistant markdown links render as readable label and URL instead of raw markdown link syntax', async () => {
  const client = new MarkdownLinksResponseClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('ESPNcricinfo') &&
        harness.stdout.output.includes('https://www.espncricinfo.com/live') &&
        harness.stdout.output.includes('Sportstar') &&
        harness.stdout.output.includes('https://sportstar.thehindu.com/live'),
      { message: 'Rendered markdown links did not appear in the TUI.' },
    )

    const output = harness.stdout.output
    const lastFrameOffset = output.lastIndexOf('OpenClaude')
    const finalFrame = lastFrameOffset >= 0 ? output.slice(lastFrameOffset) : output

    assert.equal(finalFrame.includes('[ESPNcricinfo](https://www.espncricinfo.com/live)'), false)
    assert.equal(finalFrame.includes('[Sportstar](https://sportstar.thehindu.com/live)'), false)
  } finally {
    await harness.cleanup()
  }
})

test('assistant markdown tables render as formatted terminal rows instead of raw pipe syntax', async () => {
  const client = new MarkdownTableResponseClient(createSnapshot())
  const harness = await renderApp(
    <App
      clientFactory={() => client}
      workspaceFilesLoader={async () => []}
    />,
  )

  try {
    await waitFor(
      () => harness.stdout.output.includes('OpenClaude'),
      { message: 'The Ink app did not finish initializing.' },
    )

    await sendInput(harness.stdin, 'h')
    await sendInput(harness.stdin, 'i')
    await sendInput(harness.stdin, '\r')

    await waitFor(
      () =>
        harness.stdout.output.includes('Name') &&
        harness.stdout.output.includes('Type') &&
        harness.stdout.output.includes('java-cpp-to-python-guide.md'),
      { message: 'Rendered markdown table did not appear in the TUI.' },
    )

    const output = harness.stdout.output
    const lastFrameOffset = output.lastIndexOf('OpenClaude')
    const finalFrame = lastFrameOffset >= 0 ? output.slice(lastFrameOffset) : output

    assert.equal(finalFrame.includes('| Name | Type |'), false)
    assert.equal(finalFrame.includes('|---|---|'), false)
  } finally {
    await harness.cleanup()
  }
})

class StatusNoiseClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      onEvent?.({
        kind: 'event',
        id: 'status-noise-1',
        event: 'prompt.status',
        data: { message: 'Model response in progress' },
      })
      onEvent?.({
        kind: 'event',
        id: 'status-noise-2',
        event: 'prompt.status',
        data: { message: 'Model response completed' },
      })
    }

    return super.request(method, params, onEvent)
  }
}

class ToolsPanelClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'command.run' || method === 'command.execute') {
      const commandName = String((params as { commandName?: string; name?: string } | undefined)?.commandName ?? '')
      if (commandName === 'tools') {
        return {
          message: 'Rendered available tools.',
          snapshot: this.snapshot,
          panel: {
            kind: 'tools',
            title: 'Tools',
            subtitle: 'Allowed tools exposed to the active model.',
            sections: [
              { title: 'Summary', lines: ['Provider: OpenAI', 'Model: gpt-5.3-codex', 'Tool count: 12'] },
              { title: 'bash', lines: ['bash — Run a local shell command in the current workspace.'] },
              { title: 'Read', lines: ['Read — Read a file from the local filesystem.'] },
              { title: 'Glob', lines: ['Glob — Fast file pattern matching tool that works with any codebase size.'] },
              { title: 'Grep', lines: ['Grep — A powerful search tool built on ripgrep semantics.'] },
              { title: 'Write', lines: ['Write — Write a file to the local filesystem.'] },
              { title: 'Edit', lines: ['Edit — Perform exact string replacements in files.'] },
              { title: 'WebFetch', lines: ['WebFetch — Fetch content from a URL and return readable page content for the model to analyze.'] },
              { title: 'TodoWrite', lines: ['TodoWrite — Update the todo list for the current session. Use it proactively to track progress and pending tasks.'] },
              { title: 'WebSearch', lines: ['WebSearch — Search the web for current information and return relevant links the model can cite.'] },
              { title: 'AskUserQuestion', lines: ['AskUserQuestion — Ask the user multiple choice questions to clarify requirements or decisions.'] },
              { title: 'EnterPlanMode', lines: ['EnterPlanMode — Requests permission to enter plan mode for complex tasks requiring exploration and design.'] },
              { title: 'ExitPlanMode', lines: ['ExitPlanMode — Prompts the user to exit plan mode and start coding.'] },
            ],
            contextUsage: null,
          },
        } as TResult
      }
    }

    return super.request(method, params, onEvent)
  }
}

class StatusPanelClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'command.run' || method === 'command.execute') {
      const commandName = String((params as { commandName?: string; name?: string } | undefined)?.commandName ?? '')
      if (commandName === 'status') {
        return {
          message: 'Rendered OpenClaude status.',
          snapshot: this.snapshot,
          panel: {
            kind: 'status',
            title: 'Status',
            subtitle: 'OpenClaude runtime status for the current workspace.',
            sections: [
              { title: 'Overview', lines: ['Version: 0.1.0-SNAPSHOT', 'Active provider: OpenAI', 'Active model: gpt-5.3-codex'] },
              { title: 'Providers', lines: ['OpenAI (openai) — active via browser_sso'] },
              { title: 'Tools', lines: ['Tool runtime ready: yes', 'Tool count: 12'] },
              { title: 'Environment', lines: ['Git workspace: true'] },
            ],
            contextUsage: null,
          },
        } as TResult
      }
    }

    return super.request(method, params, onEvent)
  }
}

class SessionPanelClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'command.run' || method === 'command.execute') {
      const commandName = String((params as { commandName?: string; name?: string } | undefined)?.commandName ?? '')
      if (commandName === 'session') {
        return {
          message: 'Rendered current session details.',
          snapshot: this.snapshot,
          panel: {
            kind: 'session',
            title: 'Session',
            subtitle: 'Current session information for this workspace.',
            sections: [
              {
                title: 'Current session',
                lines: [
                  'Title: Current session',
                  'Session id: session-test',
                  'Created: 2026-04-02T00:00:00Z',
                  'Updated: 2026-04-02T00:00:00Z',
                  'Messages: 0',
                  'Workspace root: /tmp/workspace',
                  'Working directory: /tmp/workspace',
                ],
              },
              { title: 'Preview', lines: ['No session preview yet.'] },
              {
                title: 'Resume scope',
                lines: [
                  'Current scope path: /tmp/workspace',
                  'Use /resume to switch to another session from this workspace.',
                ],
              },
            ],
            contextUsage: null,
          },
        } as TResult
      }
    }

    return super.request(method, params, onEvent)
  }
}

class PermissionsPanelClient extends FakeOpenClaudeClient {
  public permissionEditorSnapshotRequests = 0
  public readonly permissionMutations: string[] = []

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'permissions.editor.snapshot') {
      this.permissionEditorSnapshotRequests += 1
    }
    if (method === 'permissions.editor.mutate') {
      const action = String((params as { action?: string } | undefined)?.action ?? '')
      if (action === 'retry-denials') {
        this.permissionMutations.push(action)
      } else {
        const source = String((params as { source?: string } | undefined)?.source ?? '')
        const behavior = String((params as { behavior?: string } | undefined)?.behavior ?? '')
        const rule = String((params as { rule?: string } | undefined)?.rule ?? '')
        this.permissionMutations.push(`${action}:${source}:${behavior}:${rule}`)
      }
    }

    return super.request(method, params, onEvent)
  }
}

class UsagePanelClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'command.run' || method === 'command.execute') {
      const commandName = String((params as { commandName?: string; name?: string } | undefined)?.commandName ?? '')
      if (commandName === 'usage') {
        return {
          message: 'Rendered usage limits panel.',
          snapshot: this.snapshot,
          panel: {
            kind: 'usage',
            title: 'Usage',
            subtitle: 'Plan usage limits for the active provider/account.',
            sections: [
              {
                title: 'Overview',
                lines: ['Provider: OpenAI', 'Auth: browser_sso', 'Model: gpt-5.3-codex'],
              },
              {
                title: 'Limits',
                lines: [
                  'Provider quota/plan usage APIs are not wired yet for OpenClaude\'s multi-provider engine.',
                  'Current session context: 128/200000 estimated tokens.',
                ],
              },
            ],
            contextUsage: {
              estimatedTokens: 128,
              contextWindowTokens: 200000,
              usedCells: 1,
              totalCells: 24,
              status: 'healthy',
            },
          },
        } as TResult
      }
    }

    return super.request(method, params, onEvent)
  }
}

class StatsCommandPanelClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'command.run' || method === 'command.execute') {
      const commandName = String((params as { commandName?: string; name?: string } | undefined)?.commandName ?? '')
      if (commandName === 'stats') {
        return {
          message: 'Rendered usage statistics panel.',
          snapshot: this.snapshot,
          panel: {
            kind: 'stats',
            title: 'Stats',
            subtitle: 'OpenClaude usage statistics and activity.',
            sections: [
              {
                title: 'Overview',
                lines: [
                  'Total sessions: 12',
                  'Total messages: 88',
                  'Active days: 5',
                  'First session: 2026-03-28',
                  'Last session: 2026-04-02',
                ],
              },
              {
                title: 'Activity',
                lines: [
                  'Longest session: Repo refactor',
                  'Longest session duration: 02h 10m 00s',
                  'Peak activity day: 2026-04-01',
                  'Peak activity messages: 24',
                ],
              },
            ],
            contextUsage: null,
          },
        } as TResult
      }
    }

    return super.request(method, params, onEvent)
  }
}

class StreamingThinkingClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'thinking-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'thinking-delta',
        event: 'prompt.reasoning.delta',
        data: { text: 'Inspecting the workspace before answering.', summary: false },
      })
      await delay(80)
      onEvent?.({
        kind: 'event',
        id: 'thinking-text',
        event: 'prompt.delta',
        data: { text: 'Done.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Done.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Done.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class MarkdownResponseClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      const markdown = '## Plan\n- inspect the repo\n- run tests\nUse `rg --files`.'
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'markdown-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'markdown-text',
        event: 'prompt.delta',
        data: { text: markdown },
      })
      this.snapshot = appendConversation(this.snapshot, text, markdown)
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: markdown,
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class MarkdownTableResponseClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      const markdown = [
        'Summary for `~/Desktop/py`:',
        '',
        '| Name | Type |',
        '|---|---|',
        '| `java-cpp-to-python-guide.md` | Markdown document |',
      ].join('\n')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'markdown-table-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'markdown-table-text',
        event: 'prompt.delta',
        data: { text: markdown },
      })
      this.snapshot = appendConversation(this.snapshot, text, markdown)
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: markdown,
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class MarkdownLinksResponseClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      const markdown = [
        'Sources:',
        '',
        '- [ESPNcricinfo](https://www.espncricinfo.com/live)',
        '- [Sportstar](https://sportstar.thehindu.com/live)',
      ].join('\n')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'markdown-links-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'markdown-links-text',
        event: 'prompt.delta',
        data: { text: markdown },
      })
      this.snapshot = appendConversation(this.snapshot, text, markdown)
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: markdown,
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class LiveToolRenderingClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)

      onEvent?.({
        kind: 'event',
        id: 'live-tool-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'live-tool-running',
        event: 'prompt.tool.started',
        data: {
          toolId: 'tool-live-1',
          toolName: 'bash',
          phase: 'started',
          text: 'Queued bash command.',
          command: "cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l",
        },
      })

      await new Promise((resolve) => setTimeout(resolve, 40))

      onEvent?.({
        kind: 'event',
        id: 'live-tool-complete',
        event: 'prompt.tool.completed',
        data: {
          toolId: 'tool-live-1',
          toolName: 'bash',
          phase: 'completed',
          text: "Command: cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l\nExit code: 0\n\n25",
          command: "cd ~/Desktop && find . -maxdepth 1 -type d | sed '1d' | wc -l",
        },
      })
      onEvent?.({
        kind: 'event',
        id: 'live-tool-delta',
        event: 'prompt.delta',
        data: { text: 'Desktop folder count response.' },
      })

      this.snapshot = appendConversation(this.snapshot, text, 'Desktop folder count response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Desktop folder count response.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class SilentBusyClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'busy-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      await delay(220)
      onEvent?.({
        kind: 'event',
        id: 'busy-text',
        event: 'prompt.delta',
        data: { text: 'Delayed final response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Delayed final response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Delayed final response.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class PermissionOverlayClient extends FakeOpenClaudeClient {
  public readonly permissionDecisions: string[] = []
  private pendingPermissionResolve: ((decision: string) => void) | null = null

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'permission-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'permission-request',
        event: 'permission.requested',
        data: {
          requestId: 'perm-1',
          toolId: 'tool-1',
          toolName: 'bash',
          inputJson: '{"command":"pwd"}',
          command: 'pwd',
          reason: 'Allow this local bash command?',
        },
      })

      const decision = await new Promise<string>((resolve) => {
        this.pendingPermissionResolve = resolve
      })
      if (decision !== 'allow') {
        throw new Error('Permission denied')
      }

      onEvent?.({
        kind: 'event',
        id: 'permission-tool-started',
        event: 'prompt.tool.started',
        data: { toolId: 'tool-1', toolName: 'bash', phase: 'started', text: 'Queued bash command.' },
      })
      onEvent?.({
        kind: 'event',
        id: 'permission-tool-completed',
        event: 'prompt.tool.completed',
        data: { toolId: 'tool-1', toolName: 'bash', phase: 'completed', text: 'Command: pwd' },
      })
      onEvent?.({
        kind: 'event',
        id: 'permission-text',
        event: 'prompt.delta',
        data: { text: 'Tool-backed response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Tool-backed response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Tool-backed response.',
        snapshot: this.snapshot,
      } as TResult
    }

    if (method === 'permission.respond') {
      const decision = String((params as { decision?: string } | undefined)?.decision ?? '')
      this.permissionDecisions.push(decision)
      this.pendingPermissionResolve?.(decision)
      this.pendingPermissionResolve = null
      return {
        message: 'Permission decision recorded.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class AskUserQuestionClient extends FakeOpenClaudeClient {
  public submittedAnswers: Record<string, string> = {}
  public submittedAnnotations: Record<string, { preview?: string; notes?: string }> = {}
  public deniedFeedbacks: string[] = []
  protected pendingResolve: ((decision: { decision: string; payloadJson?: string; updatedInputJson?: string }) => void) | null = null

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'ask-user-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'ask-user-request',
        event: 'permission.requested',
        data: {
          requestId: 'question-1',
          toolId: 'tool-q1',
          toolName: 'AskUserQuestion',
          inputJson: '{"questions":[{"question":"Which implementation path should OpenClaude take?","header":"Approach","options":[{"label":"Safer refactor","description":"Minimize risk and land the change in smaller steps."},{"label":"Direct rewrite","description":"Rewrite the flow in one pass."}]}]}',
          command: 'Which implementation path should OpenClaude take?',
          reason: 'Answer questions?',
          interactionType: 'ask_user_question',
          interactionJson: '{"questions":[{"question":"Which implementation path should OpenClaude take?","header":"Approach","options":[{"label":"Safer refactor","description":"Minimize risk and land the change in smaller steps."},{"label":"Direct rewrite","description":"Rewrite the flow in one pass."}]}]}',
        },
      })

      const response = await new Promise<{ decision: string; payloadJson?: string; updatedInputJson?: string }>((resolve) => {
        this.pendingResolve = resolve
      })
      if (response.decision === 'allow') {
        const parsed = JSON.parse(response.updatedInputJson ?? '{"answers":{}}') as {
          answers?: Record<string, string>
          annotations?: Record<string, { preview?: string; notes?: string }>
        }
        this.submittedAnswers = parsed.answers ?? {}
        this.submittedAnnotations = parsed.annotations ?? {}
      } else {
        const parsed = JSON.parse(response.payloadJson ?? '{"feedback":""}') as { feedback?: string }
        const feedback = parsed.feedback?.trim() ?? ''
        if (!feedback) {
          throw new Error('User declined to answer questions')
        }
        this.deniedFeedbacks.push(feedback)
      }

      onEvent?.({
        kind: 'event',
        id: 'ask-user-text',
        event: 'prompt.delta',
        data: { text: 'Question-backed response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Question-backed response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Question-backed response.',
        snapshot: this.snapshot,
      } as TResult
    }

    if (method === 'permission.respond') {
      const requestParams = (params as { decision?: string; payloadJson?: string; updatedInputJson?: string } | undefined) ?? {}
      this.pendingResolve?.({
        decision: String(requestParams.decision ?? ''),
        payloadJson: requestParams.payloadJson,
        updatedInputJson: requestParams.updatedInputJson,
      })
      this.pendingResolve = null
      return {
        message: 'Permission decision recorded.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class PreviewAskUserQuestionClient extends AskUserQuestionClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'ask-user-preview-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'ask-user-preview-request',
        event: 'permission.requested',
        data: {
          requestId: 'question-preview-1',
          toolId: 'tool-preview-1',
          toolName: 'AskUserQuestion',
          inputJson: '{"questions":[{"question":"Which architecture should OpenClaude take?","header":"Architecture","options":[{"label":"Safer refactor","description":"Land the refactor in smaller steps.","preview":"## Safer refactor\\nIncremental rollout with checkpoints."},{"label":"Direct rewrite","description":"Rewrite the full path now.","preview":"## Direct rewrite\\nReplace the whole flow in one pass."}]}]}',
          command: 'Which architecture should OpenClaude take?',
          reason: 'Answer questions?',
          interactionType: 'ask_user_question',
          interactionJson: '{"questions":[{"question":"Which architecture should OpenClaude take?","header":"Architecture","options":[{"label":"Safer refactor","description":"Land the refactor in smaller steps.","preview":"## Safer refactor\\nIncremental rollout with checkpoints."},{"label":"Direct rewrite","description":"Rewrite the full path now.","preview":"## Direct rewrite\\nReplace the whole flow in one pass."}]}]}',
        },
      })

      const response = await new Promise<{ decision: string; payloadJson?: string; updatedInputJson?: string }>((resolve) => {
        this.pendingResolve = resolve
      })
      if (response.decision !== 'allow') {
        throw new Error('User declined to answer questions')
      }
      const parsed = JSON.parse(response.updatedInputJson ?? '{"answers":{}}') as {
        answers?: Record<string, string>
        annotations?: Record<string, { preview?: string; notes?: string }>
      }
      this.submittedAnswers = parsed.answers ?? {}
      this.submittedAnnotations = parsed.annotations ?? {}

      onEvent?.({
        kind: 'event',
        id: 'ask-user-preview-text',
        event: 'prompt.delta',
        data: { text: 'Preview-backed response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Preview-backed response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Preview-backed response.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class MultiSelectAskUserQuestionClient extends AskUserQuestionClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'ask-user-multi-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'ask-user-multi-request',
        event: 'permission.requested',
        data: {
          requestId: 'question-multi-1',
          toolId: 'tool-multi-1',
          toolName: 'AskUserQuestion',
          inputJson: '{"questions":[{"question":"Which features should OpenClaude enable first?","header":"Features","multiSelect":true,"options":[{"label":"WebSearch","description":"Enable search-backed research."},{"label":"TodoWrite","description":"Track work with todos."},{"label":"WebFetch","description":"Fetch documentation pages."}]}]}',
          command: 'Which features should OpenClaude enable first?',
          reason: 'Answer questions?',
          interactionType: 'ask_user_question',
          interactionJson: '{"questions":[{"question":"Which features should OpenClaude enable first?","header":"Features","multiSelect":true,"options":[{"label":"WebSearch","description":"Enable search-backed research."},{"label":"TodoWrite","description":"Track work with todos."},{"label":"WebFetch","description":"Fetch documentation pages."}]}]}',
        },
      })

      const response = await new Promise<{ decision: string; payloadJson?: string; updatedInputJson?: string }>((resolve) => {
        this.pendingResolve = resolve
      })
      if (response.decision !== 'allow') {
        throw new Error('User declined to answer questions')
      }
      const parsed = JSON.parse(response.updatedInputJson ?? '{"answers":{}}') as {
        answers?: Record<string, string>
        annotations?: Record<string, { preview?: string; notes?: string }>
      }
      this.submittedAnswers = parsed.answers ?? {}
      this.submittedAnnotations = parsed.annotations ?? {}

      onEvent?.({
        kind: 'event',
        id: 'ask-user-multi-text',
        event: 'prompt.delta',
        data: { text: 'Multi-select response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Multi-select response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Multi-select response.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }
}

class MultiPermissionOverlayClient extends FakeOpenClaudeClient {
  public readonly permissionDecisions: string[] = []
  private readonly permissionResolvers = new Map<string, (decision: string) => void>()

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'multi-permission-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: 'multi-permission-request-1',
        event: 'permission.requested',
        data: {
          requestId: 'perm-1',
          toolId: 'tool-1',
          toolName: 'bash',
          inputJson: '{"command":"pwd"}',
          command: 'pwd',
          reason: 'Allow this local bash command?',
        },
      })
      onEvent?.({
        kind: 'event',
        id: 'multi-permission-request-2',
        event: 'permission.requested',
        data: {
          requestId: 'perm-2',
          toolId: 'tool-2',
          toolName: 'bash',
          inputJson: '{"command":"ls -1 ~/Desktop"}',
          command: 'ls -1 ~/Desktop',
          reason: 'Allow this local bash command?',
        },
      })

      await this.waitForPermission('perm-1')
      await this.waitForPermission('perm-2')

      onEvent?.({
        kind: 'event',
        id: 'multi-permission-text',
        event: 'prompt.delta',
        data: { text: 'Queued tool response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Queued tool response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Queued tool response.',
        snapshot: this.snapshot,
      } as TResult
    }

    if (method === 'permission.respond') {
      const requestId = String((params as { requestId?: string } | undefined)?.requestId ?? '')
      const decision = String((params as { decision?: string } | undefined)?.decision ?? '')
      this.permissionDecisions.push(decision)
      this.permissionResolvers.get(requestId)?.(decision)
      this.permissionResolvers.delete(requestId)
      return {
        message: 'Permission decision recorded.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }

  private waitForPermission(requestId: string): Promise<string> {
    return new Promise<string>((resolve) => {
      this.permissionResolvers.set(requestId, resolve)
    })
  }
}

class RepeatedPermissionOverlayClient extends FakeOpenClaudeClient {
  public readonly permissionDecisions: string[] = []
  private readonly permissionResolvers = new Map<string, (decision: string) => void>()

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'repeat-permission-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })

      const firstRequest = {
        requestId: 'perm-repeat-1',
        toolId: 'tool-repeat-1',
        toolName: 'WebSearch',
        inputJson: '{"query":"SRH vs KKR live score IPL 2026"}',
        command: 'SRH vs KKR live score IPL 2026',
        reason: 'Allow web search for "SRH vs KKR live score IPL 2026"?',
      }

      const firstPermission = this.waitForPermission(firstRequest.requestId)
      onEvent?.({
        kind: 'event',
        id: 'repeat-permission-request-1',
        event: 'permission.requested',
        data: firstRequest,
      })

      await firstPermission

      const secondPermission = this.waitForPermission('perm-repeat-2')
      onEvent?.({
        kind: 'event',
        id: 'repeat-permission-request-2',
        event: 'permission.requested',
        data: {
          ...firstRequest,
          requestId: 'perm-repeat-2',
          toolId: 'tool-repeat-2',
        },
      })

      await secondPermission

      onEvent?.({
        kind: 'event',
        id: 'repeat-permission-text',
        event: 'prompt.delta',
        data: { text: 'Repeated permission response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Repeated permission response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Repeated permission response.',
        snapshot: this.snapshot,
      } as TResult
    }

    if (method === 'permission.respond') {
      const requestId = String((params as { requestId?: string } | undefined)?.requestId ?? '')
      const decision = String((params as { decision?: string } | undefined)?.decision ?? '')
      this.permissionDecisions.push(decision)
      this.permissionResolvers.get(requestId)?.(decision)
      this.permissionResolvers.delete(requestId)
      return {
        message: 'Permission decision recorded.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }

  private waitForPermission(requestId: string): Promise<string> {
    return new Promise<string>((resolve) => {
      this.permissionResolvers.set(requestId, resolve)
    })
  }
}

class QueuedDuplicatePermissionOverlayClient extends FakeOpenClaudeClient {
  public readonly permissionDecisions: string[] = []
  private readonly permissionResolvers = new Map<string, (decision: string) => void>()

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'queued-repeat-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })

      const firstRequest = {
        requestId: 'perm-queued-1',
        toolId: 'tool-queued-1',
        toolName: 'WebSearch',
        inputJson: '{"query":"SRH vs KKR live score IPL 2026"}',
        command: 'SRH vs KKR live score IPL 2026',
        reason: 'Allow web search for "SRH vs KKR live score IPL 2026"?',
      }

      const firstPermission = this.waitForPermission(firstRequest.requestId)
      const secondPermission = this.waitForPermission('perm-queued-2')
      onEvent?.({
        kind: 'event',
        id: 'queued-repeat-request-1',
        event: 'permission.requested',
        data: firstRequest,
      })
      onEvent?.({
        kind: 'event',
        id: 'queued-repeat-request-2',
        event: 'permission.requested',
        data: {
          ...firstRequest,
          requestId: 'perm-queued-2',
          toolId: 'tool-queued-2',
        },
      })

      await firstPermission
      await secondPermission

      onEvent?.({
        kind: 'event',
        id: 'queued-repeat-text',
        event: 'prompt.delta',
        data: { text: 'Queued duplicate permission response.' },
      })
      this.snapshot = appendConversation(this.snapshot, text, 'Queued duplicate permission response.')
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: 'Queued duplicate permission response.',
        snapshot: this.snapshot,
      } as TResult
    }

    if (method === 'permission.respond') {
      const requestId = String((params as { requestId?: string } | undefined)?.requestId ?? '')
      const decision = String((params as { decision?: string } | undefined)?.decision ?? '')
      this.permissionDecisions.push(decision)
      this.permissionResolvers.get(requestId)?.(decision)
      this.permissionResolvers.delete(requestId)
      return {
        message: 'Permission decision recorded.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }

  private waitForPermission(requestId: string): Promise<string> {
    return new Promise<string>((resolve) => {
      this.permissionResolvers.set(requestId, resolve)
    })
  }
}

class CrossRunPermissionCacheClient extends FakeOpenClaudeClient {
  public readonly permissionDecisions: string[] = []
  private readonly permissionResolvers = new Map<string, (decision: string) => void>()
  private promptCount = 0

  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      this.promptCount += 1
      const requestId = this.promptCount === 1 ? 'cross-run-1' : 'cross-run-2'
      const toolId = this.promptCount === 1 ? 'tool-cross-run-1' : 'tool-cross-run-2'
      const permission = this.waitForPermission(requestId)
      const responseText = this.promptCount === 1
        ? 'First run completed after approval.'
        : 'Second run reused the cached permission decision.'

      onEvent?.({
        kind: 'event',
        id: `cross-run-start-${this.promptCount}`,
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      onEvent?.({
        kind: 'event',
        id: `cross-run-request-${this.promptCount}`,
        event: 'permission.requested',
        data: {
          requestId,
          toolId,
          toolName: 'WebSearch',
          inputJson: '{ "query": "SRH vs KKR live score IPL 2026" }',
          command: 'SRH vs KKR live score IPL 2026',
          reason: 'Allow web search for "SRH vs KKR live score IPL 2026"?',
        },
      })

      await permission

      onEvent?.({
        kind: 'event',
        id: `cross-run-text-${this.promptCount}`,
        event: 'prompt.delta',
        data: { text: responseText },
      })
      this.snapshot = appendConversation(this.snapshot, text, responseText)
      return {
        sessionId: this.snapshot.session.sessionId ?? 'session-test',
        modelId: this.snapshot.state.activeModelId ?? 'gpt-5.3-codex',
        text: responseText,
        snapshot: this.snapshot,
      } as TResult
    }

    if (method === 'permission.respond') {
      const requestId = String((params as { requestId?: string } | undefined)?.requestId ?? '')
      const decision = String((params as { decision?: string } | undefined)?.decision ?? '')
      this.permissionDecisions.push(decision)
      this.permissionResolvers.get(requestId)?.(decision)
      this.permissionResolvers.delete(requestId)
      return {
        message: 'Permission decision recorded.',
        snapshot: this.snapshot,
      } as TResult
    }

    return super.request(method, params, onEvent)
  }

  private waitForPermission(requestId: string): Promise<string> {
    return new Promise<string>((resolve) => {
      this.permissionResolvers.set(requestId, resolve)
    })
  }
}

class EndOfInputErrorClient extends FakeOpenClaudeClient {
  override async request<TResult extends OpenClaudeResponse['result']>(
    method: OpenClaudeRequest['method'],
    params?: unknown,
    onEvent?: (event: OpenClaudeEvent) => void,
  ): Promise<TResult> {
    if (method === 'prompt.submit') {
      const text = String((params as { text?: string } | undefined)?.text ?? '')
      this.promptSubmissions.push(text)
      onEvent?.({
        kind: 'event',
        id: 'end-of-input-start',
        event: 'prompt.started',
        data: { message: 'Prompt started' },
      })
      throw new Error(
        'No content to map due to end-of-input\n'
        + ' at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1]',
      )
    }

    return super.request(method, params, onEvent)
  }
}

function extractTranscriptFrame(output: string): string {
  const promptBorder = '╭──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────╮'
  const headerTitle = 'Tips for getting started'
  const headerIndex = output.lastIndexOf(headerTitle)
  const promptIndex = output.lastIndexOf(promptBorder)

  if (headerIndex === -1 || promptIndex === -1 || promptIndex <= headerIndex) {
    return output
  }

  const headerEnd = output.indexOf('╰', headerIndex)
  if (headerEnd === -1 || headerEnd >= promptIndex) {
    return output.slice(headerIndex, promptIndex)
  }

  return output.slice(headerEnd, promptIndex)
}
