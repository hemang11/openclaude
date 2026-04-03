# Execution Flows

This document walks through the highest-value runtime flows in the active `openclaude` architecture.

## 1. UI Startup

```text
ui-ink/src/index.tsx
  -> createInkStdout(process.stdout)
  -> render(<App />)

App mount
  -> create OpenClaudeStdioClient
  -> request "initialize"
  -> receive BackendSnapshot
  -> seed transcript, prompt history, command catalog, and status lines
```

Relevant files:

- [index.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/index.tsx)
- [app.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/app.tsx)
- [OpenClaudeStdioClient.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ipc/OpenClaudeStdioClient.ts)

## 2. Backend Startup

```text
OpenClaudeApplication.main()
  -> instantiate stores and registries
  -> build PromptRouter and services
  -> parse CLI arguments
  -> interactive root:
       SessionBootstrap.prepareInteractiveSession(...)
       InteractiveShell.run()
     or stdio:
       OpenClaudeStdioServer.run(...)
```

Relevant files:

- [OpenClaudeApplication.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java)
- [SessionBootstrap.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/SessionBootstrap.java)
- [OpenClaudeStdioServer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java)

## 3. Prompt Submit Flow

```text
User presses Enter
  -> App validates mode/overlays/busy guards
  -> OpenClaudeStdioClient.request("prompt.submit")
  -> OpenClaudeStdioServer.submitPromptAsync(...)
  -> PromptRouter.executeWithEvents(...)
  -> QueryEngine.submitWithEvents(...)
```

Inside `QueryEngine` the active turn sequence is:

1. load global state
2. require active provider connection
3. resolve active model
4. resolve effort
5. load session
6. append user message
7. maybe run time-based microcompact
8. render instructions via `AgentsInstructionsLoader`
9. project prompt messages
10. execute provider call
11. collect streamed reasoning/text/tool events
12. run tool loop if needed
13. append assistant/thinking/tool transcript rows
14. persist session
15. return `QueryTurnResult`

Relevant files:

- [PromptRouter.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/PromptRouter.java)
- [QueryEngine.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/query/QueryEngine.java)

## 4. Provider Streaming Flow

Providers are called through the SPI, not directly from UI code.

```text
QueryEngine
  -> ProviderRegistry.findExecutable(...)
  -> ProviderPlugin.executePromptStream(...)
  -> PromptEvent stream
      - TextDeltaEvent
      - ReasoningDeltaEvent
      - ToolCallEvent
      - ToolUseDiscoveredEvent
      - ToolPermissionEvent
      - PromptStatusEvent
```

`QueryEngine` consumes those events and:

- forwards them to the caller event consumer
- appends durable transcript records
- decides whether early tool execution is possible

Relevant files:

- [ProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-spi/src/main/java/com/openclaude/provider/spi/ProviderPlugin.java)
- [OpenAiProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiProviderPlugin.java)
- [AnthropicProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicProviderPlugin.java)

## 5. Tool Loop Flow

```text
Provider emits tool use
  -> QueryEngine records tool intent
  -> if read-only and safe, may execute early
  -> tool runtime dispatches to matching runtime
  -> permission gateway may prompt or reuse rules
  -> hooks run around tool
  -> tool result persisted
  -> result fed back into provider-visible prompt
  -> loop repeats until final assistant answer
```

Important runtime rules:

- maximum tool-use turns per provider response: `8`
- maximum total loop iterations: `16`
- concurrency-safe read-only tools can be batched
- `bash` can be interruption-cancellable
- failed or cancelled tools synthesize paired results so the transcript remains coherent

Relevant files:

- [DefaultToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java)
- [CompositeToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/CompositeToolRuntime.java)
- [AbstractSingleToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/AbstractSingleToolRuntime.java)
- [ToolHooksExecutor.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolHooksExecutor.java)

## 6. Permission Flow

```text
Tool runtime needs approval
  -> build ToolPermissionRequest
  -> check persisted rules through ToolPermissionGateway.lookupPersistedDecision(...)
  -> if no reusable rule:
       emit permission_requested lifecycle update
       stdio server forwards PermissionRequestEvent
       Ink renders permission overlay
       user selects allow/deny
       permission.respond goes back to backend
       pending CompletableFuture resolves
  -> tool continues or fails
```

Relevant files:

- [ToolPermissionGateway.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionGateway.java)
- [PermissionRulesStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/PermissionRulesStore.java)
- [OpenClaudeStdioServer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java)
- [PermissionsOverlay.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/PermissionsOverlay.tsx)

## 7. Slash Command Flow

Command handling is split three ways.

### Backend command

```text
User types /context
  -> App recognizes backend command
  -> command.execute
  -> CommandService.run("context", ...)
  -> PanelView returned
  -> App renders panel overlay
```

### Frontend command

```text
User types /provider
  -> App recognizes frontend/overlay command
  -> App opens picker or overlay directly
  -> overlay selections may trigger backend mutations
```

### Hybrid settings mutation

```text
User changes config in UI
  -> settings.update
  -> backend persists OpenClaudeSettings
  -> BackendSnapshot returned
  -> App re-renders status/config/transcript context
```

Relevant files:

- [CommandService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CommandService.java)
- [app.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/app.tsx)

## 8. Compaction Flow

```text
User runs /compact
  -> App blocks other prompt submissions while compacting
  -> command.execute("compact")
  -> CompactConversationService.compact(...)
  -> compact hooks run
  -> provider asked for no-tools compact summary
  -> boundary + summary + preserved tail prepared
  -> projected pre/post context estimated
  -> if no benefit:
       skip persistence
     else:
       save compacted session
  -> post-compact attachments restored
  -> snapshot refreshed
```

Relevant files:

- [CompactConversationService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java)
- [SessionCompaction.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/session/SessionCompaction.java)
- [SessionMemoryService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryService.java)

## 9. Context and Diagnostics Flow

```text
/context or /usage or /doctor
  -> CommandService panel builder
  -> ContextDiagnosticsService / provider diagnostics / session projection
  -> PanelView returned
  -> UI panel overlay renders wrapped viewport
  -> status line reflects context-left summary
```

Relevant files:

- [ContextDiagnosticsService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/ContextDiagnosticsService.java)
- [StatusLine.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/StatusLine.tsx)

## 10. Snapshot -> Transcript Rendering Flow

```text
BackendSnapshot.messages
  -> reorderMessagesForUI(...)
  -> buildMessageLookups(...)
  -> groupToolUses(...)
  -> collapseReadSearchGroups(...)
  -> RenderableMessage[]
  -> Transcript / Messages / Message components
```

Why this exists:

- stored transcript rows are durable and backend-shaped
- terminal output should be assistant-turn-shaped and tool-group-shaped
- hook and tool lifecycle rows need grouping, not raw dumping

Relevant files:

- [normalizeMessages.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/normalizeMessages.ts)
- [buildMessageLookups.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/buildMessageLookups.ts)
- [Transcript.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/ui/Transcript.tsx)

## 11. Session Resume and Rewind Flow

```text
UI asks for sessions.list
  -> backend filters sessions to current scope
  -> user selects a session
  -> sessions.resume mutation
  -> activeSessionId updated in global state
```

Rewind:

```text
UI builds rewind candidates from transcript
  -> sessions.rewind(messageId)
  -> SessionService truncates message list before selected user checkpoint
  -> snapshot refreshed
```

Relevant files:

- [SessionService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/SessionService.java)

## 12. Failure Classification Flow

```text
Provider throws
  -> QueryEngine catches / routes failure
  -> ProviderFailureClassifier maps to rate_limit / policy_limit / auth_error / generic
  -> backend emits protocol error or status
  -> UI status feed and/or panels reflect classified failure
```

Relevant files:

- [ProviderFailureClassifier.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/provider/ProviderFailureClassifier.java)

## 13. Build and Package Flow

```text
./build.sh
  -> Gradle installDist for app-cli
  -> npm ci if needed for ui-ink
  -> npm run typecheck
```

The dev UI then shells out to the packaged backend binary rather than trying to embed the Java runtime directly into the UI workspace.
