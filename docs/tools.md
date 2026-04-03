# Tools

This document describes the built-in tool runtime registered by [DefaultToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java).

## Active Built-In Tools

The current active runtime ships these tools:

| Tool name | Runtime class | Category | Notes |
|---|---|---|---|
| `bash` | [BashToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/BashToolRuntime.java) | shell | Read-only commands can run concurrently; supports cancellation |
| `Glob` | [GlobToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/GlobToolRuntime.java) | filesystem read | Pattern-based file discovery |
| `Grep` | [GrepToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/GrepToolRuntime.java) | filesystem read | Content search |
| `ExitPlanMode` | [ExitPlanModeToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ExitPlanModeToolRuntime.java) | session control | Leaves plan mode |
| `Read` | [FileReadToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileReadToolRuntime.java) | filesystem read | Reads files and updates file-read state |
| `Edit` | [FileEditToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileEditToolRuntime.java) | filesystem write | Structured file edits with mutation guards |
| `Write` | [FileWriteToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileWriteToolRuntime.java) | filesystem write | Whole-file writes |
| `WebFetch` | [WebFetchToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java) | network | Fetches pages and returns readable content |
| `TodoWrite` | [TodoWriteToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/TodoWriteToolRuntime.java) | session state | Updates the session todo list |
| `WebSearch` | [WebSearchToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/WebSearchToolRuntime.java) | network | Provider-native web search where supported |
| `AskUserQuestion` | [AskUserQuestionToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/AskUserQuestionToolRuntime.java) | interactive | Multi-question approval/clarification UI |
| `EnterPlanMode` | [EnterPlanModeToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/EnterPlanModeToolRuntime.java) | session control | Enables plan mode |

## Tool Runtime Architecture

### Dispatch

`DefaultToolRuntime` is a `CompositeToolRuntime`.

That means:

- tools are registered as independent runtimes
- dispatch is name-based
- concurrency and interrupt behavior are delegated to the matching runtime

### Common Base Path

Many tools use [AbstractSingleToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/AbstractSingleToolRuntime.java) for:

- definition ownership
- permission request emission
- lifecycle updates
- concurrency-safe checks

### Tool Execution Contract

[ToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolRuntime.java) defines:

- `toolDefinitions()`
- `isConcurrencySafe(...)`
- `interruptBehavior(...)`
- `execute(...)`

Execution returns a [ToolExecutionResult.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolExecutionResult.java) and can emit rich [ToolExecutionUpdate.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolExecutionUpdate.java) events while running.

## Permission Model

Permissions are not hard-coded into the UI. They are part of tool execution.

Important files:

- [ToolPermissionGateway.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionGateway.java)
- [ToolPermissionRequest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionRequest.java)
- [ToolPermissionDecision.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionDecision.java)
- [ToolPermissionRule.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionRule.java)
- [ToolPermissionSources.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolPermissionSources.java)
- [PermissionRulesStore.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/PermissionRulesStore.java)

The effective flow is:

1. tool builds a `ToolPermissionRequest`
2. runtime checks persisted decisions
3. if unresolved, backend emits permission request event
4. Ink shows modal UI
5. user response resolves backend future

## Bash Semantics

`bash` is the most security-sensitive tool, so it has extra policy layers.

Important files:

- [BashToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/BashToolRuntime.java)
- [ShellPermissionPolicy.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ShellPermissionPolicy.java)

Notable behavior:

- read-only commands can be auto-classified as concurrency-safe
- destructive or blocked commands can fail before prompt-time approval
- approval can modify the final command payload
- interrupt behavior is `CANCEL`, unlike many other tools

## File Mutation Tools

The write/edit stack is guarded by:

- file-read state
- mutation guards
- patch formatting

Important files:

- [FileReadToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileReadToolRuntime.java)
- [FileWriteToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileWriteToolRuntime.java)
- [FileEditToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileEditToolRuntime.java)
- [FileMutationGuards.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/FileMutationGuards.java)
- [UnifiedPatchRenderer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/UnifiedPatchRenderer.java)

## Network Tools

### `WebFetch`

`WebFetch` is a fetch-and-extract tool, not just a raw HTTP dump.

Important implementation concerns:

- readable content extraction
- markdown preservation where possible
- provider-specific summarization behavior
- browser-auth vs API-key paths
- failure fallback to readable content

### `WebSearch`

`WebSearch` prefers provider-native search behavior where supported and otherwise falls back to local search behavior when necessary.

Relevant files:

- [WebFetchToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java)
- [WebSearchToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/WebSearchToolRuntime.java)

## Tool Hooks

Tools participate in the hook subsystem through [ToolHooksExecutor.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolHooksExecutor.java).

Hook outputs become:

- progress rows
- additional-context attachments
- post-tool failure context

Those transcript artifacts are later grouped by the UI renderer.

## Tool Model Subrequests

Some tools use model-side helper calls through:

- [ToolModelInvoker.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolModelInvoker.java)
- [ToolModelRequest.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolModelRequest.java)
- [ToolModelResponse.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/ToolModelResponse.java)

This is how tools such as `WebFetch` can do provider-assisted secondary processing without leaving the tool system entirely.

## End-to-End Mental Model

If the model emits a tool call, the path is:

```text
provider tool request
  -> QueryEngine tool loop
  -> ToolRuntime dispatch
  -> permission + hooks
  -> concrete runtime
  -> ToolExecutionUpdate lifecycle rows
  -> ToolExecutionResult
  -> tool_result transcript row
  -> reinjected provider prompt
```

That is the core contract the entire tool subsystem is built around.
