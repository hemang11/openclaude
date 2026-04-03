# Subsystems

This guide breaks the active runtime into the subsystems you are most likely to touch while working on `openclaude`.

## 1. Composition Root and Entry Modes

### Composition Root

The backend starts in [OpenClaudeApplication.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java).

It performs manual composition, not framework-driven injection.

Core objects built there:

- `ProviderRegistry`
- `OpenClaudeStateStore`
- `ConversationSessionStore`
- `PromptRouter`
- `DefaultToolRuntime`
- `ProviderService`
- `ModelService`
- `SessionService`
- `CommandService`

### Entry Modes

There are two meaningful backend modes:

- interactive CLI root command
- stdio backend mode for the Ink UI

In stdio mode, all meaningful UI interaction happens through [OpenClaudeStdioServer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java).

## 2. State and Persistence

### Global State

[OpenClaudeState.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/OpenClaudeState.java) is the durable global snapshot.

It stores:

- active provider
- active model id
- active session id
- connection map keyed by `ProviderId`
- global settings

[OpenClaudeStateStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/config/OpenClaudeStateStore.java) persists that state to `state.json`.

### Session Persistence

[ConversationSessionStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/ConversationSessionStore.java) stores one JSON file per session.

Important consequences:

- sessions are easy to inspect/debug by hand
- snapshots are durable across backend restarts
- session scope is resolved from workspace root or working directory

### Path Resolution

[OpenClaudePaths.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/config/OpenClaudePaths.java) owns the directory layout.

Key convention:

- `OPENCLAUDE_HOME` overrides the default
- otherwise everything lives under `~/.openclaude`

## 3. Session Model

[ConversationSession.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/ConversationSession.java) is immutable in style: mutation helpers return new records.

This has practical effects:

- state transitions are explicit
- tests can compare whole records
- session updates are easier to reason about than mutable object graphs

The durable fields also tell you what `openclaude` considers part of conversation state:

- transcript
- plan-mode flag
- todos
- file-read guard state
- session-memory state

## 4. Transcript Message Model

[SessionMessage.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/SessionMessage.java) is the transcript backbone.

It supports more than plain user/assistant text. The active runtime persists:

- user and assistant text
- assistant thinking
- tool lifecycle rows
- tool results
- compact boundaries
- progress rows
- attachment rows
- tombstones for internal error continuity

This is important because most UI behavior depends on reconstructing richer render groupings from these stored rows.

## 5. Query Engine

[QueryEngine.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/query/QueryEngine.java) is the central runtime subsystem.

### Responsibilities

- validates active provider/model/session prerequisites
- appends user messages
- loads and renders instruction context
- projects sessions into provider prompt messages
- executes provider calls in stream or non-stream mode
- collects reasoning/text/tool events
- runs the tool loop
- persists assistant results
- handles cancellation
- classifies and surfaces provider failures
- triggers microcompact/reactive compact/session-memory paths

### Why It Matters

Most high-level product behavior eventually reduces to a `QueryEngine` decision:

- whether a tool should run
- how many tool loops are allowed
- what prompt the provider actually sees
- what transcript survives after failures
- what context is projected after compaction

## 6. Tool Runtime

### Dispatch

[ToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolRuntime.java) is the interface.

[CompositeToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/CompositeToolRuntime.java) dispatches by tool name.

[DefaultToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java) registers the built-in tool set.

### Common Execution Pattern

[AbstractSingleToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/AbstractSingleToolRuntime.java) standardizes:

- matching tool definitions
- concurrency-safe checks
- permission request emission
- lifecycle update emission

### Important Tool Subsystems

- [BashToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/BashToolRuntime.java)
- [ShellPermissionPolicy.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ShellPermissionPolicy.java)
- [FileMutationGuards.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileMutationGuards.java)
- [UnifiedPatchRenderer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/UnifiedPatchRenderer.java)
- [ToolHooksExecutor.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolHooksExecutor.java)
- [ToolPermissionGateway.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionGateway.java)

### Tool Hooks

`openclaude` supports tool hook execution around tool invocations.

`ToolHooksExecutor` currently handles:

- `PreToolUse`
- `PostToolUse`
- `PostToolUseFailure`

Hook results are persisted as transcript progress and attachment rows, which then feed the UI pipeline.

## 7. Provider Subsystem

### Registry

[ProviderRegistry.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/ProviderRegistry.java) loads `ProviderPlugin` implementations via `ServiceLoader`.

### SPI

[ProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-spi/src/main/java/com/openclaude/provider/spi/ProviderPlugin.java) is the contract.

It defines:

- provider identity
- display name
- auth methods
- model catalog
- streaming support
- tool support
- prompt execution hooks

### Active Providers

The active build includes:

- Anthropic
- OpenAI
- Gemini
- Mistral
- Kimi
- Bedrock

The OpenAI path is the most complex because it supports both:

- API-key execution
- browser-auth Codex execution

Representative implementation files:

- [OpenAiProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiProviderPlugin.java)
- [OpenAiApiClient.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiApiClient.java)
- [OpenAiCodexResponsesClient.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiCodexResponsesClient.java)
- [AnthropicProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicProviderPlugin.java)
- [AnthropicApiClient.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicApiClient.java)

## 8. Command Service Layer

[CommandService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CommandService.java) is the backend slash-command owner.

It is responsible for:

- advertising the command catalog
- executing backend-handled commands
- building command panels
- exposing permission editor snapshots/mutations
- updating settings
- routing `/compact`

Important supporting services:

- [SessionService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/SessionService.java)
- [ProviderService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ProviderService.java)
- [ModelService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ModelService.java)
- [ContextDiagnosticsService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ContextDiagnosticsService.java)
- [CompactConversationService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java)

## 9. Compaction and Session Memory

Compaction spans backend services and core projection logic.

Important parts:

- session compaction boundaries
- preserved-tail support
- compact hooks
- post-compact cleanup
- session-memory sidecar
- skip-if-no-benefit compaction guard

Key files:

- [CompactConversationService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java)
- [CompactHooksExecutor.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CompactHooksExecutor.java)
- [PostCompactCleanup.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/PostCompactCleanup.java)
- [SessionCompaction.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/SessionCompaction.java)
- [SessionMemoryService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryService.java)

## 10. Instructions and Workspace Context

[AgentsInstructionsLoader.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/instructions/AgentsInstructionsLoader.java) is the current instruction-loading subsystem.

It is responsible for rendering the system-prompt preamble from repository-local instruction files such as `AGENTS.md`-style content.

This matters because the provider-visible prompt is not just the session transcript. It is:

- instruction prompt
- projected conversation
- tool definitions
- current user input

## 11. Stdio Protocol and Backend Snapshotting

[OpenClaudeStdioServer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java) translates backend state into the UI protocol defined in [protocol.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/types/stdio/protocol.ts).

Important protocol concepts:

- request / response / event envelopes
- `BackendSnapshot`
- `SessionMessageView`
- `PanelView`
- `PromptToolEvent`
- `PermissionRequestEvent`

The stdio server is where raw backend transcript state becomes UI-ready snapshot state. It also owns permission request correlation and active prompt-run bookkeeping.

## 12. Ink UI Shell

### Main Coordinator

[app.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/app.tsx) is the UI orchestrator.

It owns:

- backend client lifecycle
- prompt input and mode state
- busy/working/thinking state
- permission overlays
- command pickers
- panel overlays
- prompt history
- prompt submission guards
- compaction activity guards
- status feed

### Shell Layout

[ReplShell.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/ReplShell.tsx) is intentionally simple. It composes:

- header
- transcript
- pending turn
- thinking row
- live messages
- tasks
- overlay
- prompt
- status line
- status feed

### Rendering Discipline

The UI is terminal-first. The transcript is not rendered as arbitrary HTML; it is rendered as a normalized message stream using Ink primitives and custom grouping logic.

## 13. Transcript Normalization

The message normalization subsystem is one of the most important UI layers because it decides what the terminal shows for persisted and live data.

Key files:

- [normalizeMessages.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/normalizeMessages.ts)
- [buildMessageLookups.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/buildMessageLookups.ts)
- [groupToolUses.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/groupToolUses.ts)
- [collapseReadSearchGroups.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/collapseReadSearchGroups.ts)

What it currently does:

- reorder tool-related rows into UI-friendly order
- bind tool rows back to assistant turns
- group sibling tool uses
- collapse read/search-heavy activity
- preserve hook/progress attachment context

## 14. Prompt Editing and Input

The input stack is layered:

```text
terminal bytes
  -> useTerminalInput
  -> editor reducer
  -> useTextInput
  -> BaseTextInput
  -> TextInput
  -> PromptInputPanel
  -> app-level prompt mode / command routing
```

The UI also keeps separate concepts for:

- raw prompt mode
- display value
- stash/history draft
- bash mode
- prompt mode
- command suggestion state
- history search state

## 15. Diagnostics and Limit Handling

This subsystem sits across backend and UI.

Backend:

- calculates context estimates
- classifies provider failures
- surfaces rate/policy/auth failures
- builds `/doctor`, `/usage`, and `/context` panels

Frontend:

- renders current status line
- exposes context-left summary
- renders diagnostics panels and status messages

## 16. Testing Strategy

The repo uses targeted tests around subsystem boundaries rather than one giant black-box suite.

Good anchor tests:

- [QueryEngineTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/test/java/com/openclaude/core/query/QueryEngineTest.java)
- [SessionCompactionTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/test/java/com/openclaude/core/session/SessionCompactionTest.java)
- [WebFetchToolRuntimeTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java)
- [OpenClaudeStdioServerTest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java)
- [userHappyPath.test.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/test/userHappyPath.test.tsx)
- [normalizeMessages.test.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/normalizeMessages.test.ts)

## 17. Practical Mental Map

If you are debugging a feature, this shortcut is usually right:

- command or panel issue -> `app-cli/service/*` plus `app.tsx`
- provider selection or model issue -> `ProviderService`, `ModelService`, `ProviderRegistry`, `provider-*`
- prompt execution issue -> `QueryEngine`
- permission issue -> `ToolPermissionGateway`, `PermissionRulesStore`, `app.tsx`
- transcript glitch -> `OpenClaudeStdioServer`, `normalizeMessages`, `buildMessageLookups`, renderer components
- compaction or context issue -> `CompactConversationService`, `SessionCompaction`, `SessionMemoryService`, `StatusLine`
