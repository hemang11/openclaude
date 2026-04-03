# Architecture

This document describes how the active `openclaude` runtime is put together today.

It is intentionally code-first. Every section below maps back to the Java modules, the Ink UI, the shared stdio contract, or the persistence model that is live in this repository.

## High-Level Shape

`openclaude` is split into two primary processes:

1. A Java backend that owns provider execution, sessions, tools, commands, compaction, and diagnostics.
2. A React + Ink frontend that owns the terminal UI and talks to the backend over a typed stdio protocol.

At the highest level the runtime looks like this:

```text
User keystrokes
  -> Ink UI (`ui-ink`)
  -> typed stdio requests/events (`types/stdio`)
  -> Java stdio server (`app-cli`)
  -> prompt router / query engine (`core`)
  -> provider plugin (`provider-*`)
  -> tool runtime / hooks / permissions (`core`)
  -> persisted session + state (`~/.openclaude`)
  -> snapshot/events back over stdio
  -> transcript normalization + rendering (`ui-ink`)
```

## Active Build Modules

The active Gradle build is declared in [settings.gradle](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/settings.gradle).

| Module | Role |
|---|---|
| `app-cli` | Main Java entrypoint, interactive shell, service layer, stdio server |
| `auth` | Browser SSO helpers and callback-server support |
| `core` | Query engine, sessions, state, tools, compaction, diagnostics |
| `provider-spi` | Provider contracts and shared request/result/event types |
| `provider-openai` | OpenAI API-key and browser-auth provider implementation |
| `provider-anthropic` | Anthropic provider implementation |
| `provider-gemini` | Gemini provider implementation |
| `provider-mistral` | Mistral provider implementation |
| `provider-kimi` | Kimi provider implementation |
| `provider-bedrock` | AWS Bedrock provider implementation |
| `ui-ink` | Ink-based terminal UI, launched separately from Java |
| `types/stdio` | Shared frontend/backend protocol types used by the UI |

## Live vs Parallel Trees

This repo contains both the active `com.openclaude.*` modules and older or alternate `io.openclaude.*` trees such as:

- `openclaude-app`
- `openclaude-core`
- `openclaude-cli`
- `openclaude-provider-*`
- `openclaude-session`
- `openclaude-tools`
- `openclaude-terminal`

Those directories are not included by the active [settings.gradle](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/settings.gradle) build. They are useful as reference material, experiments, or namespace successors, but they are not the runtime described by this doc.

## Process Topology

### Backend Process

The Java entrypoint is [OpenClaudeApplication.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java).

It manually wires:

- `ProviderRegistry`
- `OpenClaudeStateStore`
- `ConversationSessionStore`
- `PromptRouter`
- `DefaultToolRuntime`
- `OpenAiBrowserAuthService`
- `ProviderService`
- `ModelService`
- `SessionService`
- `CommandService`

The composition root is deliberately explicit. There is no Spring container or DI framework in the active runtime. Constructor wiring happens in `main(...)`.

### Frontend Process

The Ink entrypoint is [index.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/index.tsx).

It:

- creates a wrapped stdout via `createInkStdout(...)`
- renders `<App />`
- disables `exitOnCtrlC`
- leaves console patching off

The frontend launches the Java backend in stdio mode via:

- [backendLaunch.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ipc/backendLaunch.ts)
- [OpenClaudeStdioClient.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ipc/OpenClaudeStdioClient.ts)

## Runtime Pipeline

### 1. Startup

Backend startup in the interactive shell path:

```text
OpenClaudeApplication.main()
  -> build core services
  -> parse CLI args with picocli
  -> if no subcommand:
       SessionBootstrap.prepareInteractiveSession(...)
       InteractiveShell.run()
  -> else if "stdio":
       OpenClaudeStdioServer.run(...)
```

Frontend startup:

```text
index.tsx
  -> render(App)
  -> App creates OpenClaudeStdioClient
  -> App requests "initialize"
  -> backend returns BackendSnapshot
  -> UI builds startup transcript, command catalog, prompt state, and overlays
```

### 2. Prompt Submission

The interactive UI sends `prompt.submit` to the backend.

The backend path is:

```text
OpenClaudeStdioServer.handle("prompt.submit")
  -> PromptRouter.executeWithEvents(...)
  -> QueryEngine.submitWithEvents(...)
```

Inside [QueryEngine.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/query/QueryEngine.java), a turn currently includes:

- loading active provider, connection, model, and effort
- loading the active session
- appending the user message
- optional time-based microcompact
- instruction rendering via `AgentsInstructionsLoader`
- prompt projection into provider-visible messages
- provider execution in streaming mode when available
- reasoning/text/tool event collection
- multi-step tool orchestration loop
- tool result reinjection
- final assistant writeback
- session save

### 3. Tool Loop

The active tool loop is centered in `QueryEngine` and no longer just a single provider round-trip.

The loop now handles:

- provider-emitted `ToolUseContentBlock`s
- streaming `ToolUseDiscoveredEvent`s
- speculative early read-only tool execution
- permission requests
- tool lifecycle updates
- hook execution
- cancellation and synthetic failure propagation
- re-entry into the model with tool results
- loop limits:
  - `MAX_TOOL_USE_TURNS = 8`
  - `MAX_TOOL_LOOP_ITERATIONS = 16`

### 4. Snapshot Projection

The backend does not stream raw internal Java objects to Ink. Instead it projects state into the stdio protocol.

`OpenClaudeStdioServer` emits:

- full `BackendSnapshot`s on initialize and mutations
- typed prompt events during runs
- typed permission events
- command panels
- prompt cancellation and progress events

The UI then reconstructs renderable transcript state from that protocol.

## Persistence Model

### Home Directory

All durable runtime state is rooted under [OpenClaudePaths.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/config/OpenClaudePaths.java):

```text
OPENCLAUDE_HOME (if set)
or ~/.openclaude
```

Important paths:

- `state.json`
- `sessions/<session-id>.json`
- `session-memory/config/template.md`
- `session-memory/config/prompt.md`
- `session-memory/sessions/<session-id>.md`
- provider auth storage under `auth/` for some provider flows

### Global State

[OpenClaudeStateStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/config/OpenClaudeStateStore.java) persists:

- active provider
- active model
- active session
- connected providers and credentials
- UI/runtime settings

It also carries a transient session-only effort override in memory.

### Session State

[ConversationSession.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/ConversationSession.java) is the durable conversation unit.

Each session stores:

- `sessionId`
- `createdAt`
- `updatedAt`
- `title`
- `workingDirectory`
- `workspaceRoot`
- `messages`
- `planMode`
- `todos`
- `readFileState`
- `sessionMemoryState`

Sessions are stored by [ConversationSessionStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/ConversationSessionStore.java) as one JSON file per session.

### Session Messages

[SessionMessage.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/SessionMessage.java) is a sealed hierarchy.

Important message kinds:

- `user`
- `assistant`
- `thinking`
- `tool`
- `tool_result`
- `compact_boundary`
- `system`
- `progress`
- `attachment`
- `tombstone`

These are the canonical transcript records. The UI later derives renderable groupings from them; it does not invent the underlying history.

## Backend Layers

### `app-cli`

This layer owns the operator-facing Java shell and the stdio service surface.

Important classes:

- [OpenClaudeApplication.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java)
- [InteractiveShell.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/InteractiveShell.java)
- [OpenClaudeStdioServer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java)
- [CommandService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CommandService.java)
- [SessionService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/SessionService.java)
- [ProviderService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ProviderService.java)
- [ModelService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ModelService.java)
- [CompactConversationService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java)

### `core`

This is the runtime kernel.

Important subareas:

- `query/`: prompt execution, compaction retries, tool loops
- `session/`: durable session model
- `sessionmemory/`: session-memory extraction and storage
- `tools/`: tool runtime, permissions, shell policy, hooks, patch rendering
- `provider/`: provider selection, prompt routing, diagnostics, limit state
- `instructions/`: `AGENTS.md` loading and system-prompt rendering
- `config/`: paths, settings, state store, effort resolution

### `provider-spi`

This module defines the boundary the core runtime talks to.

Key types:

- [ProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-spi/src/main/java/com/openclaude/provider/spi/ProviderPlugin.java)
- `PromptRequest`
- `PromptResult`
- `PromptEvent`
- `PromptMessage`
- `PromptContentBlock`
- `ToolUseContentBlock`
- `ToolResultContentBlock`
- `TextDeltaEvent`
- `ReasoningDeltaEvent`
- `ToolCallEvent`
- `ToolUseDiscoveredEvent`
- `ToolPermissionEvent`

### `provider-*`

Providers implement `ProviderPlugin` and are loaded by `ServiceLoader` through [ProviderRegistry.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/ProviderRegistry.java).

The core runtime does not special-case providers everywhere. It depends on the SPI and lets the selected plugin handle:

- auth capabilities
- model catalog
- streaming/non-streaming execution
- tool support
- provider-native web-search and browser-auth differences

## UI Architecture

### Stdio Client Layer

The UI does not embed backend logic. It is a protocol consumer.

Relevant files:

- [OpenClaudeStdioClient.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ipc/OpenClaudeStdioClient.ts)
- [backendLaunch.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ipc/backendLaunch.ts)
- [protocol.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/types/stdio/protocol.ts)

### App Shell

[app.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/app.tsx) is the coordinator for:

- backend snapshot state
- prompt input
- prompt history
- overlays and pickers
- permission flow
- live tool rows
- streaming reasoning/text
- command routing
- session resume/rewind/config panels
- compaction activity guards

### Input Stack

The prompt editing stack is split across:

- [editor.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/input/editor.ts)
- [useTextInput.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/input/useTextInput.ts)
- [BaseTextInput.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/BaseTextInput.tsx)
- [TextInput.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/TextInput.tsx)
- [PromptInputPanel.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/PromptInputPanel.tsx)
- [keybindings.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/prompt/keybindings.ts)
- [suggestions.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/prompt/suggestions.ts)

The UI supports:

- multiline editing
- prompt and bash modes
- slash-command suggestions
- `@` file suggestions
- history navigation
- incremental history search
- external-editor handoff
- stashing
- model quick switch

### Transcript Rendering

The UI does not render backend `SessionMessageView` objects directly.

The render pipeline is:

```text
BackendSnapshot.messages
  -> reorderMessagesForUI(...)
  -> buildMessageLookups(...)
  -> groupToolUses(...)
  -> collapseReadSearchGroups(...)
  -> renderable message components
```

Key files:

- [normalizeMessages.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/normalizeMessages.ts)
- [buildMessageLookups.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/buildMessageLookups.ts)
- [groupToolUses.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/groupToolUses.ts)
- [collapseReadSearchGroups.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/collapseReadSearchGroups.ts)
- [Messages.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/Messages.tsx)
- [Transcript.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/Transcript.tsx)
- [ReplShell.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/ReplShell.tsx)

### Markdown Rendering

Markdown is rendered by [Markdown.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/Markdown.tsx).

It is now part of the main transcript/message system, not a side-channel formatter. This matters because command panels, tool results, and assistant text all pass through terminal-specific formatting constraints.

## Commands, Tools, and Permissions

### Commands

The command catalog lives in [CommandService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CommandService.java) and is mirrored into the UI through `BackendSnapshot.commands`.

Important split:

- some commands are backend-executed and return panels or text
- some are frontend overlays/actions handled in Ink
- some are hybrid, where the UI opens an overlay and later sends a backend mutation

See [commands.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/commands.md).

### Tools

The active v0 tool catalog comes from [DefaultToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java).

Tools are executed via `ToolRuntime`, most commonly:

- `CompositeToolRuntime`
- `AbstractSingleToolRuntime`
- `BashToolRuntime`
- `FileReadToolRuntime`
- `FileWriteToolRuntime`
- `FileEditToolRuntime`
- `WebFetchToolRuntime`
- `WebSearchToolRuntime`
- `AskUserQuestionToolRuntime`

See [tools.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/tools.md).

### Permission Model

Permissions flow through:

- [ToolPermissionGateway.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionGateway.java)
- [ToolPermissionRequest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionRequest.java)
- [ToolPermissionDecision.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionDecision.java)
- [PermissionRulesStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/PermissionRulesStore.java)

The stdio/UI path routes those requests through `permission_requested` tool updates and dedicated `PermissionRequestEvent`s so the frontend can render modal approval UI.

## Compaction and Session Memory

Compaction is not just a UI command. It is a subsystem spanning:

- [CompactConversationService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java)
- [SessionCompaction.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/SessionCompaction.java)
- [SessionMemoryService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryService.java)
- [TimeBasedMicrocompact.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/query/TimeBasedMicrocompact.java)
- `QueryEngine` reactive compact paths

Important behavior:

- user-visible `/compact`
- preserved-tail boundaries
- post-compact attachment reinjection
- session-memory sidecar files
- skip-if-no-benefit guard before persisting a compacted session

## Diagnostics and Limits

Diagnostics are a first-class backend concern, not just UI formatting.

Important files:

- [ContextDiagnosticsService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ContextDiagnosticsService.java)
- [ProviderFailureClassifier.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/ProviderFailureClassifier.java)
- [ProviderRuntimeDiagnostics.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/ProviderRuntimeDiagnostics.java)
- [StatusLine.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/StatusLine.tsx)

Current operator-facing surfaces include:

- `/context`
- `/usage`
- `/doctor`
- provider limit/rate/auth classification
- context-left status rendering

## Build and Packaging

The root Gradle build is in [build.gradle](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/build.gradle).

Important traits:

- multi-module Gradle build
- Java toolchain set to `24`
- source/target compatibility set to Java `21`
- package output via `:app-cli:installDist`

The developer convenience build is [build.sh](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/build.sh), which:

- builds the packaged backend
- ensures UI dependencies
- runs the UI typecheck

The packaged launcher used by the Ink UI is:

- `app-cli/build/install/openclaude/bin/openclaude`

## Testing Layout

The repo has three main test layers:

1. Java unit and integration-style tests
   - `core/src/test/...`
   - `app-cli/src/test/...`
   - `provider-*/src/test/...`
2. Ink UI tests
   - `ui-ink/src/test/...`
   - `ui-ink/src/ui/*.test.tsx`
   - `ui-ink/src/messages/*.test.ts`
3. Shared behavior tests around the protocol and tool lifecycles

Representative tests:

- [QueryEngineTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/test/java/com/openclaude/core/query/QueryEngineTest.java)
- [ToolEndToEndTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/test/java/com/openclaude/core/tools/ToolEndToEndTest.java)
- [CommandServiceTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java)
- [OpenClaudeStdioServerTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java)
- [userHappyPath.test.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/test/userHappyPath.test.tsx)

## Mental Model Summary

The simplest accurate way to think about the current architecture is:

- `app-cli` is the operator and IPC shell
- `core` is the runtime kernel
- `provider-spi` is the provider contract
- `provider-*` are concrete LLM backends
- `ui-ink` is a separate terminal frontend over stdio
- sessions and state are durable JSON/Markdown artifacts under `~/.openclaude`
- transcript rendering is reconstructed from backend snapshots, not inferred from terminal text
