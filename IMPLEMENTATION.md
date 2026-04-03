# Implementation Notes

## Objective

Build an OpenClaude CLI whose runtime core is in Java while the terminal UI remains in React/Ink, preserving Claude Code's architecture and command semantics as closely as practical while adding multi-provider model selection and provider connection management.

The reference implementation being studied is the TypeScript/Bun codebase in `../claude-code`.

The live parity backlog now lives in `PARITY.md`. Keep `PARITY.md` as the checklist of remaining Claude-parity work, and use this file for implementation history and decisions.

## 2026-04-03 Tool-Loop Closure Slice

- closed the remaining v0-code tool-loop gaps in `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- landed a real streamed-tool state model in the Java executor:
  - `queued`
  - `executing`
  - `completed`
  - `yielded`
- early streamed tool results now emit `yielded` as soon as the tool future completes, even while the provider stream is still blocked
- reactive-compact retries now discard speculative streamed tools instead of appending stale tool trajectories from the failed attempt
- concurrent bash failures now synthesize sibling cancellations for the rest of the parallel batch instead of waiting for every sibling future to finish
- cancellation reasons are now preserved through the streaming executor and paired synthetic tool results
- typed permission interaction payloads now cover the real v0-code tool set:
  - `bash`
  - `AskUserQuestion`
  - `EnterPlanMode`
  - `ExitPlanMode`
  - `Write`
  - `Edit`
  - `WebFetch`
  - `WebSearch`
- bash/shell parity was tightened in the runtime path with:
  - informational exit-code handling for exploratory commands
  - wildcard permission-rule matching
  - safer git/sed/gh/docker/path approval checks
  - parallel bash sibling-abort behavior in the query loop
- updated `PARITY.md` so the v0-code runtime/tool-loop items reflect the landed state instead of the older partial notes
- added/updated regressions in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `core/src/test/java/com/openclaude/core/tools/ToolEndToEndTest.java`
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/PlanModeToolRuntimeTest.java`
- verified with:
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest --tests com.openclaude.core.tools.ToolEndToEndTest --tests com.openclaude.core.tools.BashToolRuntimeSmokeTest --tests com.openclaude.core.tools.PlanModeToolRuntimeTest`

## 2026-04-03 Backend Tool Relationship Metadata Slice

- updated `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java` so snapshot flattening now runs across the whole transcript instead of one message at a time
- the stdio `SessionMessageView` now carries explicit tool-row relationship metadata:
  - `assistantMessageId`
  - `siblingToolIds`
- tool rows and tool results no longer rely only on `assistant-id:tool-id` row naming for ownership/grouping
- updated:
  - `types/stdio/protocol.ts`
  - `ui-ink/src/messages/buildMessageLookups.ts`
- the Ink lookup builder now prefers backend-provided tool ownership/sibling metadata before falling back to legacy row-id inference
- added regressions in:
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/messages/buildMessageLookups.test.ts`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
- verified with:
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH=\"$JAVA_HOME/bin:$PATH\" && export GRADLE_USER_HOME=.gradle && ./gradlew :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd openclaude/ui-ink && node --import tsx --test src/messages/buildMessageLookups.test.ts src/messages/normalizeMessages.test.ts`
  - `cd openclaude/ui-ink && npm run typecheck`

## 2026-04-03 ReorderMessagesInUI Foundation Slice

- added a real pre-normalization ordering pass in `ui-ink/src/messages/normalizeMessages.ts`
- raw snapshot rows now pass through `reorderMessagesForUI(...)` before lookups and render normalization
- the new pass explicitly re-emits:
  - `tool_use`
  - matching `tool` lifecycle rows
  - matching `tool_result`
  at the `tool_use` position instead of trusting whatever flat session order happened to land in storage
- standalone/orphan `tool` and `tool_result` rows still remain in-place when no matching `tool_use` exists
- added focused regressions in `ui-ink/src/messages/normalizeMessages.test.ts`
- verified with:
  - `cd openclaude/ui-ink && node --import tsx --test src/messages/buildMessageLookups.test.ts src/messages/normalizeMessages.test.ts`
  - `cd openclaude/ui-ink && npm run typecheck`

## 2026-04-03 AttachmentKind Snapshot Slice

- extended the stdio transcript snapshot so attachment rows now carry `attachmentKind`
- updated:
  - `core/src/main/java/com/openclaude/core/session/SessionAttachment.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
- `normalizeMessages.ts` and `Message.tsx` now preserve that attachment typing into renderable status rows
- `ui-ink/src/ui/messages/StatusMessage.tsx` now uses typed attachment labels instead of treating all attachments as the same generic source blob
- added regressions in:
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
- verified with:
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd openclaude/ui-ink && node --import tsx --test src/messages/buildMessageLookups.test.ts src/messages/normalizeMessages.test.ts`
  - `cd openclaude/ui-ink && npm run typecheck`

## 2026-04-03 Cursor-Aware `@` Completion Slice

- Tightened the prompt suggestion pipeline in:
  - `ui-ink/src/prompt/suggestions.ts`
  - `ui-ink/src/app.tsx`
- Landed the following Claude-derived `@` completion behaviors:
  - file-token extraction is now cursor-aware instead of tail-only, so completion can replace the whole `@token` even when the cursor sits in the middle of it
  - quoted `@"..."` file references are recognized and completed with Claude-style raw quoting
  - path-like `@./`, `@../`, `@~/`, and absolute-style tokens now bypass fuzzy workspace-file matching and switch to direct path completions with directories-first ordering
  - file suggestion items now carry the post-apply cursor position instead of forcing the composer to jump to the end of the whole prompt
  - `Tab`/`RightArrow` now prefer the suggestion state's autocomplete replacement, which lets file suggestions expand to a shared common prefix instead of always inserting the first full candidate
  - common-prefix completion now uses Claude's effective-token-length rule for `@` and `@"..."` tokens
  - prompt-suggestion dismissal keys are now cursor-aware so moving within the input can surface the correct suggestion set again
- Added focused regression coverage in:
  - `ui-ink/src/prompt/suggestions.test.ts`
    - quoted `@"..."` replacement
    - cursor-aware whole-token replacement around the live cursor
    - direct path-like routing for `@./...`
  - `ui-ink/src/test/userHappyPath.test.tsx`
    - quoted `@"..."` accept on `Tab`
    - common-prefix `Tab` completion around the cursor while preserving the surrounding suffix
- Verified with:
  - `cd ui-ink && npm test -- --runInBand src/prompt/suggestions.test.ts src/prompt/inputModes.test.ts src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`
- Important remaining gap:
  - this still is not Claude's fuller path/typeahead stack. Broader provider-backed suggestion sources and the larger prompt-suggestion service layer remain tracked in `PARITY.md`.

## 2026-04-03 Prompt Autocomplete and Readline Slice

- Tightened the extracted prompt-input stack in:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/prompt/suggestions.ts`
  - `ui-ink/src/input/useTextInput.ts`
  - `ui-ink/src/ui/BaseTextInput.tsx`
  - `ui-ink/src/ui/TextInput.tsx`
  - `ui-ink/src/ui/PromptSuggestionOverlay.tsx`
- Landed the following Claude-derived prompt behaviors:
  - slash-command autocomplete now owns `Enter`, `Tab`, `RightArrow`, and `Esc` instead of only `Tab`
  - partial slash commands can accept and execute the selected command suggestion directly from the composer
  - `Esc` now dismisses prompt autocomplete without clearing the current input, using a synchronous dismissal guard so a fast follow-up `Enter` cannot reaccept the stale suggestion
  - exact slash commands with arguments no longer get recaptured by command autocomplete once argument typing has started
  - static command argument hints are now threaded into `TextInput` and rendered inline after a trailing space
  - `BaseTextInput` now renders inline ghost text from the extracted input seam
  - text-layer `ctrl+c` now matches the Claude-style clear-or-exit split more closely:
    - double `ctrl+c` clears non-empty input
    - empty `ctrl+c` exits
  - text-layer `ctrl+p` / `ctrl+n` now fall back to history traversal when autocomplete is inactive
- Added focused regression coverage in:
  - `ui-ink/src/input/useTextInput.test.tsx`
    - double `ctrl+c` clear
    - empty `ctrl+c` exit
    - `ctrl+p` / `ctrl+n` history routing
  - `ui-ink/src/ui/BaseTextInput.test.tsx`
    - inline ghost-text rendering
  - `ui-ink/src/test/userHappyPath.test.tsx`
    - partial slash-command accept-and-execute on `Enter`
    - `Esc` dismiss of prompt autocomplete
    - inline command argument hints
    - `ctrl+p` / `ctrl+n` project-history fallback
- Verified with:
  - `cd ui-ink && npm test -- --runInBand src/input/useTextInput.test.tsx src/ui/BaseTextInput.test.tsx src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`
- Important remaining gap:
  - this still is not full Claude `PromptInput` / typeahead parity. Richer `@` path completion, broader prompt modes, image/reference paste flows, fuller footer orchestration, and the larger provider-backed suggestion stack remain tracked in `PARITY.md`.

## 2026-04-03 Project-Scoped Prompt History Slice

- Added a dedicated local prompt-history runtime in:
  - `ui-ink/src/local/promptHistory.ts`
- Landed the following Claude-derived history behaviors:
  - prompt history is now persisted to `OPENCLAUDE_HOME/history.jsonl`
  - history is filtered to the current project/workspace root
  - current-session entries are ordered ahead of other project-session entries
  - the prompt app loads project history on session/workspace changes instead of using only the current-process array
  - fresh UI sessions can restore preloaded project history with `↑`
- Wired the app shell to the new history runtime in:
  - `ui-ink/src/app.tsx`
- Added focused coverage in:
  - `ui-ink/src/local/promptHistory.test.ts`
    - current-project filtering
    - current-session-first ordering
    - immediate append visibility
  - `ui-ink/src/test/userHappyPath.test.tsx`
    - fresh-session `↑` history restore from a preloaded project history source
- Verified with:
  - `cd ui-ink && npm test -- --runInBand src/local/promptHistory.test.ts src/test/userHappyPath.test.tsx src/input/useTextInput.test.tsx src/ui/BaseTextInput.test.tsx src/ui/HistorySearchOverlay.test.tsx`
  - `cd ui-ink && npm run typecheck`
- Important remaining gap:
  - this still is not Claude's full history runtime. Claude's lazy reader, richer pasted-content restore, and broader history-search integration remain tracked in `PARITY.md`.

## 2026-04-03 Bash Prompt-History Slice

- Tightened the prompt-mode plumbing in:
  - `ui-ink/src/app.tsx`
- Landed the following Claude-derived prompt behaviors:
  - prompt mode now has a synchronous ref-backed mode source so `!` mode switches are visible to the very next key event instead of waiting for a render
  - bash submissions are now one-shot and reset the composer back to prompt mode after submit
  - if submit fails, the original prompt value and mode are restored
  - bash-mode arrow history now filters to bash entries only on first traversal
  - exiting bash history with `down` restores the original empty bash draft instead of dropping back to a plain prompt
- Split and strengthened TUI coverage in:
  - `ui-ink/src/test/userHappyPath.test.tsx`
    - `bash-mode arrow history only traverses bash entries`
    - `bash-mode down restores the empty bash draft before submit`
- Verified with:
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx src/input/useTextInput.test.tsx src/ui/BaseTextInput.test.tsx src/ui/HistorySearchOverlay.test.tsx`
  - `cd ui-ink && npm run typecheck`
- Important remaining gap:
  - this still is not full Claude `PromptInput` parity. Broader prompt modes, richer current-project history reading, paste/reference flows, and fuller footer/keybinding orchestration remain tracked in `PARITY.md`.

## 2026-04-03 Prompt-Local History Search Slice

- Replaced the earlier full-screen history-search path with a prompt-local `ctrl+r` mode in:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/ui/PromptFooter.tsx`
- Landed the following composer behaviors toward Claude's `PromptInput` path:
  - `ctrl+r` opens history search inside the prompt/footer path instead of a separate overlay
  - repeated `ctrl+r` advances to older matches
  - `tab` and `esc` accept the selected history match into the composer
  - `enter` executes the currently selected history match
  - `ctrl+c` cancels history search and restores the original prompt
  - suggestions are suppressed while history search is active
- Fixed the Ink test harness so synthetic keypresses go through raw stdin bytes instead of the old `keypress` event shim:
  - `ui-ink/src/testing/testTerminal.ts`
- Updated and extended focused coverage in:
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - `ui-ink/src/ui/HistorySearchOverlay.test.tsx`
- Verified with:
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx src/input/useTextInput.test.tsx src/ui/BaseTextInput.test.tsx src/ui/HistorySearchOverlay.test.tsx`
  - `cd ui-ink && npm run typecheck`
- This still is not full Claude history-search parity. The remaining gap is Claude's broader reverse history reader plus richer restore of pasted/reference payloads, which stays tracked in `PARITY.md`.

## 2026-04-03 BaseTextInput Coverage and Viewport Slice

- Tightened the new `useTextInput -> BaseTextInput -> TextInput` seam instead of adding more prompt logic back into `app.tsx`.
- Extended the extracted text-input types in:
  - `ui-ink/src/types/textInputTypes.ts`
  - added absolute wrapped-line tracking so the viewport line counter reflects the real cursor row instead of the viewport-relative row
- Tightened placeholder/render behavior in:
  - `ui-ink/src/hooks/renderPlaceholder.ts`
  - `ui-ink/src/ui/BaseTextInput.tsx`
- Landed the following parity improvements in `BaseTextInput`:
  - absolute wrapped-line counter instead of viewport-relative numbering
  - optional custom `placeholderElement` support
  - explicit `showCursor` / `dimColor` props on the new base component boundary
  - direct bracketed-paste state reporting through the extracted component
- Added focused regression coverage that was previously missing:
  - `ui-ink/src/input/useTextInput.test.tsx`
    - double-escape clear
    - disabled double-escape behavior
    - history routing when cursor movement is disabled
    - input filtering
    - submit behavior
  - `ui-ink/src/ui/BaseTextInput.test.tsx`
    - custom placeholder element rendering
    - absolute wrapped-line counter rendering
    - bracketed-paste callback/state behavior
- Re-ran the broader prompt/TUI suite to make sure the seam still holds under the app shell:
  - `cd ui-ink && npm test -- --runInBand src/input/useTextInput.test.tsx src/ui/BaseTextInput.test.tsx src/prompt/inputModes.test.ts src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`
- Important remaining gap:
  - this still is not full Claude `BaseTextInput` / `PromptInput` parity. Highlight rendering, cursor declaration, terminal-focus behavior, fuller prompt-local dialogs, history search, and broader prompt-mode flows remain tracked in `PARITY.md`.

## 2026-04-03 Input Editing Core Slice

- Started the `BaseTextInput` / `PromptInput` parity work from Claude's editing core instead of continuing to patch `app.tsx` ad hoc.
- Extended `ui-ink/src/input/editor.ts` with a Claude-derived subset of `useTextInput` behavior:
  - kill ring accumulation
  - `Ctrl+Y` yank
  - `Meta+Y` yank-pop
  - wrapped-line `Home` / `End`
  - logical-line fallback for `Up` / `Down` when wrapped movement is exhausted
- Tightened the wrapped-layout cursor model so wrapped-line navigation is no longer based only on the old flat row/column approximation.
- Added targeted regression coverage in:
  - `ui-ink/src/input/editor.test.ts`
    - kill-to-line-end + yank
    - yank-pop across kill-ring entries
    - wrapped-line `Home` / `End`
    - logical-line `Up` / `Down` fallback
- Verified with:
  - `cd ui-ink && npm test -- --runInBand src/input/editor.test.ts`
  - `cd ui-ink && npm run typecheck`
- Important remaining gap:
  - this is still not Claude's full `TextInput -> useTextInput -> BaseTextInput -> PromptInput` stack. Focus management, placeholder/highlight rendering, mode handling, history search, ghost text, and richer paste/reference behavior remain tracked in `PARITY.md`.

## 2026-04-03 Streaming Tool Executor Slice

- Extended the provider event contract with:
  - `provider-spi/src/main/java/com/openclaude/provider/spi/ToolUseDiscoveredEvent.java`
- OpenAI streaming transport now emits that internal event when a streamed function call is complete and its final input JSON is known:
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiResponsesSupport.java`
- `QueryEngine` now uses that signal to start concurrency-safe runtime-managed tools before the streaming provider returns, instead of waiting for the entire streamed prompt result first:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- The first landed slice is intentionally narrow:
  - concurrency-safe local tools only
  - streamed execution begins early
  - tool lifecycle events still surface immediately
  - tool invocation/tool result session messages are replayed into persisted order only after the assistant tool-use message is appended
- Added regression coverage in:
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java`
    - proves the OpenAI stream emits `ToolUseDiscoveredEvent`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
    - proves a read-only streamed tool can start before the provider returns
- Verified with:
  - `./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`
  - `./gradlew :provider-openai:test --tests com.openclaude.provider.openai.OpenAiApiClientTest`
- Important remaining gap:
  - this is not yet Claude's full streaming tool executor/runtime state machine; synthetic streamed tool results, cancellation/aborts, and broader non-read-only streaming transitions remain tracked in `PARITY.md`.

## 2026-04-03 Runtime Tool-Pairing Hardening Slice

- Kept command parity out of the active `v0-code` blocker path in `PARITY.md`; the active cut stays focused on runtime, REPL/input, transcript/render, provider parity, and test gates.
- Hardened `QueryEngine` so unexpected tool-runtime exceptions no longer tear down the whole turn and leave orphaned `tool_use` blocks behind.
- `QueryEngine` now wraps tool execution through a safe path that:
  - converts unexpected runtime exceptions into a failed tool lifecycle update
  - persists a paired failed `tool_result`
  - keeps the turn alive so the provider can respond from the failed tool result instead of crashing out
- Landed in:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- Added regression coverage for the concurrent read-only batch path:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - verifies that one crashing concurrent tool still yields paired tool results and a final assistant reply
- Verified with:
  - `./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`
- Important remaining gap:
  - this is not yet Claude's full streaming/synthetic tool-result path; sibling cancellation and streamed-abort pairing still remain tracked in `PARITY.md`.

## 2026-04-02 Compact Runtime Completion Slice

- Finished the remaining `/compact` subsystems that were still open in the parity ledger:
  - hidden per-session session-memory sidecar and extraction cursor state
  - session-memory-first compaction path
  - time-based microcompact before provider requests
  - reactive compact retry on prompt-too-long failures
- Landed the session-memory runtime in:
  - `core/src/main/java/com/openclaude/core/session/SessionMemoryState.java`
  - `core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryService.java`
  - `core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryPrompts.java`
  - `core/src/main/java/com/openclaude/core/config/OpenClaudePaths.java`
  - `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
- Wired the query loop to use the compact runtime in:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
  - `core/src/main/java/com/openclaude/core/query/TimeBasedMicrocompact.java`
- Wired `/compact` to prefer session memory and preserved-tail projection in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java`
- Added/updated regression coverage in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `core/src/test/java/com/openclaude/core/query/TimeBasedMicrocompactTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CompactConversationServiceTest.java`
- Verified with:
  - `./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest --tests com.openclaude.core.query.TimeBasedMicrocompactTest :app-cli:test --tests com.openclaude.cli.service.CompactConversationServiceTest`

## 2026-04-02 Effort Command Slice

- Wired `/effort` end to end instead of leaving it as a parity gap.
- Added persisted effort settings in:
  - `core/src/main/java/com/openclaude/core/config/OpenClaudeSettings.java`
  - `core/src/main/java/com/openclaude/core/config/OpenClaudeEffort.java`
- Added backend command handling in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- Added prompt propagation so effort becomes a real provider request property instead of a dead UI setting:
  - `provider-spi/src/main/java/com/openclaude/provider/spi/PromptRequest.java`
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- Added OpenAI payload mapping for reasoning effort in:
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiResponsesSupport.java`
- Added stdio/UI snapshot support in:
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/ui/StartupHeader.tsx`
- Added coverage in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java`
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiCodexResponsesClientTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- Important remaining gap:
  - this is not yet full Claude-parity effort behavior; Anthropic-side effort semantics, full model/account precedence, and richer UI/status integration remain tracked in `PARITY.md`.

## 2026-04-02 Bash Runtime and Live Tool Event Fixes

- Replaced the old `ShellPermissionPolicy` heuristic with a stricter Claude-aligned read-only shell validator that now permits safe compound read-only bash flows:
  - `cd ... && ls ...`
  - newline-separated read-only commands
  - pipelines between read-only commands
- The validator still blocks shell redirection, backgrounding, command substitution, unsafe `find -exec/-delete`, and mutating commands.
- `BashToolRuntime` now has regression coverage proving compound read-only commands run without surfacing a permission prompt:
  - `core/src/test/java/com/openclaude/core/tools/ShellPermissionPolicyTest.java`
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java`
- Extended the shared prompt-event contract so live tool events carry the actual command string instead of only generic status text:
  - `provider-spi/src/main/java/com/openclaude/provider/spi/ToolCallEvent.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
  - `ui-ink/src/messages/normalizeMessages.ts`
- Added UI coverage for that live command path in:
  - `ui-ink/src/messages/normalizeMessages.test.ts`
- Verified in this pass with:
  - `./gradlew :core:test --tests com.openclaude.core.tools.ShellPermissionPolicyTest --tests com.openclaude.core.tools.BashToolRuntimeSmokeTest --tests com.openclaude.core.query.QueryEngineTest`
  - `./gradlew :app-cli:test :provider-openai:test`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
  - `./build.sh`

## 2026-04-02 Parity Ledger Refresh

- Re-audited `../claude-code` command, REPL/input, tool, and runtime surfaces and tightened `PARITY.md` to be the single grouped parity ledger.
- Added current source snapshot counts to `PARITY.md` so progress is measured against the real reference tree:
  - `102` command entry surfaces under `../claude-code/src/commands`
  - `42` tool directories under `../claude-code/src/tools`
  - `26` currently surfaced `openclaude` slash commands
- Added explicit support-tool tracking for `MCPTool`, `McpAuthTool`, and `SyntheticOutputTool` so they are not lost between the default-tool and MCP/runtime workstreams.

## 2026-04-02 AGENTS Instructions Loader

- Added a real instruction discovery/runtime path in:
  - `core/src/main/java/com/openclaude/core/instructions/AgentsInstructionsLoader.java`
  - `core/src/main/java/com/openclaude/core/instructions/InstructionFile.java`
  - `core/src/main/java/com/openclaude/core/instructions/InstructionScope.java`
- `openclaude` now loads instruction files using `AGENTS.md` naming instead of Claude's `CLAUDE.md` naming:
  - managed: `/etc/openclaude/AGENTS.md` and `/etc/openclaude/rules/*.md`
  - user: `~/.openclaude/AGENTS.md` and `~/.openclaude/rules/*.md`
  - per-directory walk from workspace root toward the current directory:
    - `AGENTS.md`
    - `.openclaude/AGENTS.md`
    - `.openclaude/rules/*.md`
    - `AGENTS.local.md`
- The loader is now injected into `QueryEngine`, so discovered instructions become a real system prompt segment instead of a future placeholder.
- Added coverage in:
  - `core/src/test/java/com/openclaude/core/instructions/AgentsInstructionsLoaderTest.java`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- Important remaining gap: `@include` support exists, but it is still a smaller fenced-block parser rather than Claude's markdown-token-based parsing from `src/utils/claudemd.ts`. That remains explicitly unchecked in `PARITY.md`.

## 2026-04-02 V0 Scope Lock

- Reset the active backlog in `PARITY.md` from "all Claude surfaces at once" to a strict v0 coding-agent cut.
- v0 now means:
  - current-directory session flow and `AGENTS.md` instruction loading
  - the core React/Ink REPL, prompt input, transcript, markdown, and status-line path
  - the core coding slash-command set
  - the core coding toolchain from Claude's default preset
  - real tool orchestration, permission semantics, and tool/result pairing
  - production-ready `OpenAI` and `Anthropic` provider paths
  - hard test gates for every surfaced command/tool/provider
- `PARITY.md` now separates:
  - active v0-critical commands and tools
  - active v0 runtime/provider gaps
  - explicitly deferred post-v0 Claude surfaces such as MCP, bridge/daemon, plugin marketplace, skills runtime, Gemini/Mistral/Kimi/Bedrock execution, and feature-gated/ant-only tools
- The active v0 tool cut is intentionally limited to Claude's core coding loop:
  - landed: `BashTool`, `GlobTool`, `GrepTool`, `FileReadTool`, `FileEditTool`, `FileWriteTool`, `TodoWriteTool`, `EnterPlanModeTool`, `ExitPlanModeV2Tool`
  - partially landed: `AskUserQuestionTool`
  - still required for v0: `WebFetchTool`, `WebSearchTool`
- The rest of the tool surface stays documented in `PARITY.md`, but is no longer treated as an active blocker for shipping v0 unless it is promoted back into scope.

## 2026-04-02 AskUserQuestion Tool Slice

- Extended the shared tool-interaction contract so a tool can request structured user input instead of only allow/deny approval:
  - `ToolPermissionDecision` now supports optional `responseJson`
  - `ToolPermissionRequest`, `ToolPermissionEvent`, `ToolExecutionUpdate`, the stdio server, and `types/stdio/protocol.ts` now carry optional interaction metadata
- Added `AskUserQuestionToolRuntime` in `core/src/main/java/com/openclaude/core/tools/AskUserQuestionToolRuntime.java` and wired it into `DefaultToolRuntime` in Claude source order.
- The runtime now:
  - validates and sanitizes the incoming `questions` payload
  - emits a structured `ask_user_question` interaction request
  - round-trips user answers back into the tool loop
  - returns a Claude-style tool result summary for the model to continue from
- Added the Ink-side question overlay in `ui-ink/src/app.tsx`:
  - single-select question flow
  - built-in `Other` answer path
  - review-and-submit step
  - stdio round-trip back through `permission.respond`
- Added coverage in:
  - `core/src/test/java/com/openclaude/core/tools/AskUserQuestionToolRuntimeTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - updated `DefaultToolRuntimeTest` for the new default preset
- Important remaining gap: this is not yet full Claude parity for `AskUserQuestionTool`. Preview rendering and multi-select behavior remain explicitly unchecked in `PARITY.md`.

## Confirmed Product Scope

- Full-project rebuild in Java inside `openclaude/`
- Java 21 target with Gradle Groovy DSL
- Provider coverage:
  - Anthropic / Claude
  - OpenAI
  - Gemini
  - Mistral
  - Kimi
  - Bedrock
- Command goals:
  - `/provider` for connecting a provider
  - `/models` for selecting models exposed by connected providers
- Auth goals:
  - API key support across providers
  - browser-auth support planned first for OpenAI family

## Reference Architecture Extraction

### CLI bootstrap

Reference files:

- `src/entrypoints/cli.tsx`
- `src/main.tsx`
- `src/commands.ts`

Key behaviors to preserve:

- lightweight bootstrap before loading the heavy CLI runtime
- fast-path handling for specific flags and subcommands
- a large command registry with built-in and dynamic commands
- separation between top-level CLI commands and in-REPL slash commands
- a clean backend boundary so a React/Ink terminal client can remain separate from the Java engine

### Query and tool loop

Reference files:

- `src/QueryEngine.ts`
- `src/query.ts`
- `src/services/api/claude.ts`
- `src/services/tools/toolOrchestration.ts`
- `src/services/tools/StreamingToolExecutor.ts`

Key behaviors to preserve:

- streaming-first response handling
- explicit message normalization and session state mutation
- tool-use / tool-result pairing guarantees
- retry and non-streaming fallback paths
- tool concurrency partitioning based on safety

### Provider, auth, and model selection

Reference files:

- `src/cli/handlers/auth.ts`
- `src/utils/auth.ts`
- `src/utils/model/providers.ts`
- `src/utils/model/model.ts`
- `src/utils/model/modelOptions.ts`
- `src/components/ModelPicker.tsx`

Key behaviors to preserve:

- provider-aware auth state
- provider-aware model normalization and selection
- session-level and persisted model overrides
- explicit user-facing model picker flow

## Java Module Plan

Current module layout:

- `app-cli`
- `auth`
- `core`
- `provider-spi`
- `provider-anthropic`
- `provider-openai`
- `provider-gemini`
- `provider-mistral`
- `provider-kimi`
- `provider-bedrock`
- `ui-ink`
- `types/stdio`

Expected later modules:

- `query-engine`
- `tools`
- `permissions`
- `sessions`
- `mcp`
- `bridge`
- `daemon`

## Current Implementation Strategy

### Phase 0

- establish repo and build bootstrap
- standardize the build on Groovy DSL only
- define provider SPI
- define canonical internal model catalog per provider
- persist provider connection metadata under `~/.openclaude`
- expose top-level `provider` and `models` handlers as the first reusable command surface

### Phase 1

- add terminal REPL shell and a stdio server mode
- introduce slash command registry and route `/provider` and `/models` through it
- implement provider connection flows:
  - API key connection
  - browser auth for OpenAI first
- route plain-text prompts through a provider-neutral execution contract
- define a typed stdio contract for the React/Ink frontend

### Phase 2

- build provider-neutral request model
- port the query loop
- port tool execution orchestration
- add streaming, fallback, and session persistence

## Implemented So Far

The repo now contains the first executable slice of the architecture:

- Gradle multi-module skeleton for app CLI, auth, core, provider SPI, and provider adapters
- reusable bootstrap commands for `provider` and `models`
- a minimal interactive shell that routes `/provider` and `/models`
- a dedicated `stdio` backend mode for the React/Ink frontend
- persisted provider-state storage under `~/.openclaude/state.json`
- persisted conversation transcripts under `~/.openclaude/sessions/<session-id>.json`
- a provider registry contract and initial provider capability matrix
- browser-auth scaffolding with PKCE and a localhost callback server
- provider-neutral prompt execution contracts
- a `QueryEngine`-style turn runner that maintains an active session id in state
- a working OpenAI API-key execution path using the Responses API
- a first-party OpenAI browser OAuth flow with persisted tokens and refresh support
- a direct OpenAI account-backed execution path through `https://chatgpt.com/backend-api/codex/responses`
- OpenAI streaming text handling in the interactive shell
- a shared stdio IPC contract in `types/stdio/protocol.ts`
- a bootable React/Ink client in `ui-ink/` that connects to `openclaude stdio`
- structured prompt stream events for text, reasoning, status, and tool-call activity
- persisted `thinking` transcript messages in the Java session store
- an Ink transcript renderer with Claude-style labeled blocks for user, assistant, thinking, and status output
- a custom prompt-composer input engine in the Ink client with cursor movement and multiline entry
- persisted tool transcript messages in the Java session store
- grouped tool blocks in the Ink transcript for persisted and live tool activity
- inline prompt suggestion overlays for slash commands and `@` file paths
- assistant-turn transcript normalization that groups thinking, tools, and final assistant text into one rendered turn
- footer suggestions for slash commands and `@` file references
- prompt history navigation and tab completion in the Ink client
- a reusable Ink theme surface for brand color and transcript message styling
- markdown-aware startup-header feeds so tips and recent-activity rows render inline command/code styling instead of raw markdown markers
- free-flow transcript rows instead of the earlier bordered transcript container
- gray-highlighted user prompt rows modeled after Claude's prompt transcript styling
- assistant markdown rendering for headings, lists, quotes, fenced code, and inline code in the Ink transcript
- a dedicated live assistant tail above the pinned prompt area so streaming thinking and tool activity render where the Claude UI places them
- current-run transcript filtering so restored history stays in startup/recent-activity context instead of flooding the live transcript after the first prompt
- pending user-prompt rendering during an in-flight turn so the active question stays visible above the streaming thinking/assistant response
- a front-end busy thinking indicator above the prompt so the UI shows activity even when the backend has not emitted reasoning deltas yet
- TUI regressions covering streamed thinking placement and assistant markdown rendering
- bracketed terminal paste handling in the Ink input hook so `ESC[200~ ... ESC[201~` sequences no longer leak into the prompt as gibberish
- paste-aware editor insertion semantics so multiline pasted text inserts as text instead of accidentally triggering submit on embedded newlines
- TUI regressions covering bracketed-paste input through the real Ink terminal path
- Anthropic initially landed with a provider-private read-only bash loop; that older stopgap has since been removed and superseded by the shared tool-runtime path described later in this log
- cold-build and unit-test verification through Gradle

Concrete classes added so far:

- `app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java`
- `app-cli/src/main/java/com/openclaude/cli/OpenClaudeCommand.java`
- `app-cli/src/main/java/com/openclaude/cli/InteractiveShell.java`
- `app-cli/src/main/java/com/openclaude/cli/commands/ProviderCommands.java`
- `app-cli/src/main/java/com/openclaude/cli/commands/ModelsCommand.java`
- `app-cli/src/main/java/com/openclaude/cli/service/ProviderService.java`
- `app-cli/src/main/java/com/openclaude/cli/service/ModelService.java`
- `app-cli/src/main/java/com/openclaude/cli/stdio/StdioServerCommand.java`
- `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
- `auth/src/main/java/com/openclaude/auth/BrowserAuthCoordinator.java`
- `auth/src/main/java/com/openclaude/auth/BrowserAuthRequest.java`
- `auth/src/main/java/com/openclaude/auth/BrowserAuthSession.java`
- `auth/src/main/java/com/openclaude/auth/BrowserLauncher.java`
- `auth/src/main/java/com/openclaude/auth/DefaultBrowserAuthCoordinator.java`
- `auth/src/main/java/com/openclaude/auth/DesktopBrowserLauncher.java`
- `auth/src/main/java/com/openclaude/auth/LocalCallbackServer.java`
- `auth/src/main/java/com/openclaude/auth/PkceUtil.java`
- `core/src/main/java/com/openclaude/core/config/OpenClaudePaths.java`
- `core/src/main/java/com/openclaude/core/config/OpenClaudeStateStore.java`
- `core/src/main/java/com/openclaude/core/provider/OpenClaudeState.java`
- `core/src/main/java/com/openclaude/core/provider/ProviderConnectionState.java`
- `core/src/main/java/com/openclaude/core/provider/ProviderRegistry.java`
- `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- `core/src/main/java/com/openclaude/core/query/QueryTurnResult.java`
- `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
- `core/src/main/java/com/openclaude/core/session/ConversationSessionStore.java`
- `core/src/main/java/com/openclaude/core/session/SessionMessage.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/ProviderPlugin.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/ProviderId.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/ModelDescriptor.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptExecutionContext.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptMessage.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptMessageRole.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptRequest.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptEvent.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptStatusEvent.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/ReasoningDeltaEvent.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/ToolCallEvent.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/TextDeltaEvent.java`
- `provider-spi/src/main/java/com/openclaude/provider/spi/PromptResult.java`
- `core/src/main/java/com/openclaude/core/provider/PromptRouter.java`
- `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiApiClient.java`
- `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiBrowserAuthService.java`
- `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiCodexResponsesClient.java`
- `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiOAuthStore.java`
- `provider-openai/src/main/java/com/openclaude/provider/openai/HttpOpenAiOAuthApi.java`
- `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiResponsesSupport.java`
- `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
- `types/stdio/protocol.ts`
- `ui-ink/src/ipc/OpenClaudeStdioClient.ts`
- `ui-ink/src/app.tsx`
- `ui-ink/src/input/editor.ts`
- `ui-ink/src/messages/types.ts`
- `ui-ink/src/messages/normalizeMessages.ts`
- `ui-ink/src/ui/Messages.tsx`
- `ui-ink/src/ui/MessageRow.tsx`
- `ui-ink/src/ui/Message.tsx`
- `ui-ink/src/ui/messages/AssistantThinkingMessage.tsx`
- `ui-ink/src/ui/messages/AssistantToolUseMessage.tsx`
- `ui-ink/src/ui/messages/AssistantToolResultMessage.tsx`
- `ui-ink/src/ui/messages/AssistantTextMessage.tsx`
- `ui-ink/src/ui/messages/UserTextMessage.tsx`
- `ui-ink/src/ui/messages/StatusMessage.tsx`
- `ui-ink/src/ui/Picker.tsx`
- `ui-ink/src/ui/PromptComposer.tsx`
- `ui-ink/src/ui/Transcript.tsx`
- `ui-ink/src/ui/PromptFooter.tsx`
- `ui-ink/src/local/system.ts`

## Latest Parity Slice

This slice focused on prompt-input parity and transcript/tool rendering rather than adding new providers.

Backend:

- `PromptEvent` is now a typed stream surface, not only raw text deltas
- OpenAI stream parsing emits:
  - response status updates
  - reasoning deltas
  - tool lifecycle updates when the upstream stream exposes them
- the Java session model now persists both `thinking` and simplified `tool` transcript messages between user and assistant turns
- the stdio backend includes the richer prompt event stream plus persisted `tool` messages in snapshots

Frontend:

- the Ink prompt no longer depends on `ink-text-input`
- the custom prompt composer supports:
  - inline cursor rendering
  - wrapped multiline display
  - `Shift+Enter` / `Meta+Enter` newline insertion
  - `\` + `Enter` newline insertion
  - line/word cursor movement
  - line/word deletion shortcuts
  - prompt history handoff when vertical movement can no longer move inside the buffer
- the Ink prompt now has inline suggestion overlays for:
  - slash commands
  - trailing `@` file references
  - selection with `Up` / `Down` or `Ctrl+N` / `Ctrl+P`
  - `Tab` completion
  - first-submit command completion for partial slash commands
- the Ink transcript now normalizes raw snapshot plus live stream state in `ui-ink/src/messages/normalizeMessages.ts`
- the Ink render path now follows `Messages -> MessageRow -> Message` instead of a monolithic transcript renderer
- assistant turns now carry structured content blocks for:
  - thinking
  - assistant text
  - tool use
  - tool result
- tool rows are now explicitly paired in the UI model as `tool_use` plus `tool_result` when the available backend phase data allows it
- `/config` can toggle persisted reasoning visibility
- the footer now carries generic shortcut guidance while the prompt overlay handles live suggestions
- `Tab` autocompletes the selected slash command or file reference

Reference audit after this slice:

- prompt/input parity work still points at:
  - `src/components/PromptInput/PromptInput.tsx`
  - `src/hooks/useTextInput.ts`
  - `src/components/TextInput.tsx`
  - `src/hooks/useTypeahead.tsx`
  - `src/utils/slashCommandParsing.ts`
  - `src/utils/suggestions/commandSuggestions.ts`
  - `src/hooks/useArrowKeyHistory.tsx`
  - `src/keybindings/*`
- transcript parity work still points at:
  - `src/utils/messages.ts`
  - `src/components/Messages.tsx`
  - `src/components/MessageRow.tsx`
  - `src/components/Message.tsx`
  - `src/utils/groupToolUses.ts`
  - `src/utils/collapseReadSearch.ts`
  - tool-owned renderers under `src/components/messages/*`

What is still missing from this area:

- full Claude text-input parity:
  - kill ring / yank behavior
  - history search
  - ghost text
  - paste / SSH carriage-return normalization
  - configurable keybinding contexts
- full `useTypeahead` parity for MCP, Slack, bash history, side-question flows, and prompt overlays
- Claude's full message normalization pipeline for raw multi-block history, UI reordering, lookup tables keyed by `tool_use_id`, grouped tool uses, collapsed read/search output, tool-owned renderers, diff-style message rows, and transcript virtualization

## Canonical Model Catalog Strategy

The Java port will use provider-neutral internal identifiers first, then map them to concrete provider API model IDs inside each adapter.

This avoids hardwiring the entire engine to Anthropic-shaped model naming and leaves room for provider-specific transport differences.

Initial catalog sources:

- Anthropic: https://docs.anthropic.com/en/docs/about-claude/models/overview
- OpenAI: https://platform.openai.com/docs/guides/gpt-5
- Google Gemini: https://ai.google.dev/gemini-api/docs/models/gemini-v2
- Mistral: https://docs.mistral.ai/getting-started/models/
- Kimi: https://platform.moonshot.ai/

## Current OpenAI Execution Path

Implemented now:

- `provider connect openai --api-key-env OPENAI_API_KEY`
- `provider connect openai --browser`
- `provider use openai`
- plain-text shell prompts routed through `PromptRouter` into `QueryEngine`
- user and assistant turns persisted into a session transcript
- OpenAI API-key sessions issue both non-streaming and streaming requests to the Responses API
- OpenAI browser-auth sessions launch a PKCE login flow against `https://auth.openai.com/oauth/authorize`
- OpenAI browser tokens are stored under `~/.openclaude/auth/openai/default.json`
- browser-auth sessions refresh access tokens through `https://auth.openai.com/oauth/token`
- browser-auth Codex requests now send `instructions` separately and exclude `system` turns from the `input` array, matching the ChatGPT/Codex-style request shape more closely than the public Responses API payload
- `openclaude stdio` accepts newline-delimited JSON requests and emits newline-delimited JSON responses/events
- the React/Ink frontend now uses a command-aware stdio contract for:
  - `initialize`
  - `provider.connect`
  - `provider.use`
  - `models.select`
  - `command.run` / `command.execute`
  - `settings.update`
  - `prompt.submit`

## Current UI / Backend Split

The project is now explicitly split:

- Java backend:
  - provider auth and connection logic
  - query execution
  - session persistence
  - stdio server
- React/Ink frontend:
  - prompt input
  - transcript rendering
  - picker overlays
  - protocol client
- shared contract:
  - `types/stdio`

This keeps the provider-neutral runtime in Java while avoiding a full reimplementation of Claude Code's terminal UI in Java.

## Current Parity Slice

The current Ink client is no longer just a provider/model demo shell.

Implemented in the React/Ink UI over stdio:

- `/` opens a command palette populated from the Java command registry
- `/provider` opens an interactive provider picker and auth-method picker
- `/models` and `/model` open an interactive model picker
- `/config` opens a config panel with persisted backend settings plus session-local task visibility
- `/context` opens an estimated context-usage panel with a colored grid
- `/copy` copies the latest assistant response, or `/copy N` copies the Nth-latest assistant response
- `/cost`, `/diff`, and `/doctor` execute in the Java backend and render as scrollable panels
- `/keybindings` creates or opens `~/.openclaude/keybindings.json`
- `meta+p`, `meta+o`, `ctrl+o`, `ctrl+t`, `ctrl+s`, `ctrl+g`, and double-`Esc` are now wired in the Ink client
- transcript rendering now runs through:
  - `ui-ink/src/messages/normalizeMessages.ts`
  - `ui-ink/src/ui/Messages.tsx`
  - `ui-ink/src/ui/MessageRow.tsx`
  - `ui-ink/src/ui/Message.tsx`
- assistant transcript rows now use structured `thinking`, `text`, `tool_use`, and `tool_result` blocks instead of the previous flat grouping helper
- the startup UI header now follows a Claude-style split layout:
  - left banner block with the OpenClaude logo and session/provider summary
  - right feed column with `Tips for getting started` and `Recent activity`
- the startup layout now gives the banner more space and centers it inside the left block instead of squeezing it into the narrower earlier layout
- the empty transcript state is now reduced to `No conversation yet.` so startup guidance lives in the header feed instead of being duplicated in the transcript panel
- the Ink UI suite now covers the startup header panels via `ui-ink/src/test/userHappyPath.test.tsx`
- restored session details are now kept in the startup `Recent activity` feed instead of replaying the full transcript immediately under the header on boot
- the Ink runtime entrypoint now wraps `stdout` so Ink does not hit its `clearTerminal` path for tall renders, which avoids the terminal warning about control sequences attempting to clear scroll history

Verified in this pass:

- `cd ui-ink && npm run typecheck`
- `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app-cli:test`
- browser-auth prompt execution calls `https://chatgpt.com/backend-api/codex/responses` directly with the stored bearer token and `ChatGPT-Account-Id`
- streaming text deltas are printed directly in the interactive shell for both OpenAI auth modes
- final response text is extracted from `output[].content[].type == output_text` for API-key non-streaming fallback
- OpenAI browser auth uses a fixed localhost callback URI at `http://localhost:1455/auth/callback`
- OpenAI browser auth defaults to Codex-compatible models first in `/models`
- Ink prompt submission now catches backend failures and reports them in the status area instead of crashing the UI process

Still missing in the OpenAI path:

- tool calls
- structured outputs
- auth-aware `/models` filtering
- live manual verification of the new browser login flow in this session
- Codex-specific parity details such as `session_id` headers or special instruction mapping beyond the current message transcript

## Verification

Verified locally with the Gradle wrapper:

- `./gradlew :auth:compileJava :provider-openai:test :app-cli:compileJava`
- `./gradlew :app-cli:run --args="provider list"`
- `./gradlew :app-cli:run --args="provider connect openai --api-key-env TEST_OPENAI_KEY"` from the earlier API-key slice
- `./gradlew :app-cli:test`
- `./gradlew :app-cli:installDist`
- `cd ui-ink && npm run typecheck`

Not re-run manually in this pass:

- `provider connect openai --browser` through a real browser login
- a live prompt execution using the newly stored OAuth session

Current unit coverage:

- `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- `provider-anthropic/src/test/java/com/openclaude/provider/anthropic/AnthropicApiClientTest.java`
- `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java`
- `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiBrowserAuthServiceTest.java`
- `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiCodexResponsesClientTest.java`

Latest additions in this pass:

- Anthropic API-key prompt execution is now implemented through `provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicApiClient.java`
- the Anthropic provider now reports `supportsPromptExecution() == true` and executes prompts through the Messages API
- legacy OpenClaude model ids like `anthropic/sonnet` are mapped to current Anthropic API model ids inside the client so existing saved state keeps working
- the Ink UI test suite now covers exact slash-command execution on Enter via `ui-ink/src/test/userHappyPath.test.tsx`
- the core tool runtime now has a real lifecycle contract in:
  - `core/src/main/java/com/openclaude/core/tools/ToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolExecutionUpdate.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionGateway.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionRequest.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionDecision.java`
- `BashToolRuntime` now emits lifecycle updates (`started`, `permission_requested`, `progress`, `completed` / `failed`) instead of only returning one final result
- `QueryEngine` now:
  - normalizes provider-visible messages before re-entry so orphan `tool_result` messages are not sent back to the model
  - keeps the tool loop on the structured `assistant(tool_use) -> tool_result -> assistant` path
  - emits a dedicated `ToolPermissionEvent` for UI-facing approval prompts
  - preserves streaming for provider text/reasoning on tool-capable providers instead of forcing the old non-streaming path
- the stdio protocol now supports interactive permission approval:
  - backend event: `permission.requested`
  - frontend request: `permission.respond`
- `OpenClaudeStdioServer` now processes `prompt.submit` asynchronously while continuing to accept `permission.respond`, which is the first real concurrent request path in the backend
- the Ink client now opens a permission picker while the original prompt request is still running and sends the decision back over stdio
- persisted `tool` session rows now carry durable invocation metadata (`inputJson`, `command`, `permissionRequestId`, `isError`) instead of being plain phase/text status lines
- the stdio session snapshot now exposes those invocation fields to the Ink client through `SessionMessageView`
- the Ink message pipeline now renders one grouped tool row per `toolUseId` via:
  - `ui-ink/src/messages/normalizeMessages.ts`
  - `ui-ink/src/messages/buildMessageLookups.ts`
  - `ui-ink/src/messages/types.ts`
  - `ui-ink/src/ui/messages/AssistantGroupedToolMessage.tsx`
- the Ink message pipeline is now split into:
  - a lookup pass in `ui-ink/src/messages/buildMessageLookups.ts`
  - a normalization/render pass in `ui-ink/src/messages/normalizeMessages.ts`
- `buildMessageLookups.ts` now tracks:
  - `toolUseById`
  - `toolEventsById`
  - `toolResultById`
  - `toolUseIdsByAssistantMessageId`
  - `siblingToolUseIdsByToolUseId`
  - `resolvedToolUseIds`
- grouped tool rows now absorb:
  - assistant `tool_use`
  - persisted `tool` invocation snapshots
  - persisted `tool_result`
  into one durable row with command, running/approval/completed state, and final result text
- the Ink transcript now has a first collapsed row pass on top of grouped tools:
  - sibling tool uses from the same assistant turn are looked up by `toolUseId`
  - collapsible families currently include `bash`, `file_search`, `web_search`, `grep`, `glob`, `read`, and `file_read`
  - multi-tool sibling sets in those families collapse into a single `collapsed_read_search` block
- grouped tool and collapsed read/search rows are now first-class renderable transcript rows instead of nested assistant content blocks:
  - historical rows are emitted from `ui-ink/src/messages/normalizeMessages.ts`
  - live streaming rows are emitted from the same module via `createLiveRenderableMessages(...)`
  - `ui-ink/src/ui/Message.tsx` now renders `grouped_tool` and `collapsed_read_search` at the top row level
- collapsed row rendering is in:
  - `ui-ink/src/ui/messages/AssistantCollapsedReadSearchMessage.tsx`
- the provider-visible prompt history now always starts with a default OpenClaude system message that explicitly tells the model:
  - it is running in a terminal on the user's machine
  - local tools are available in the session
  - environment-specific questions should use tools instead of claiming no access
  - this default system prompt is injected in `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- the query engine now has a guarded local-tool retry for tool-capable providers:
  - if the provider answers a local filesystem/workspace question with a refusal like "I can't access your Desktop directly" and no tool use
  - OpenClaude retries once with an additional system prompt that explicitly says to use the local bash tool instead of asking the user to run commands
  - the first refusal response is discarded from the persisted transcript and from streamed UI events
- OpenAI/Codex local-environment prompts now also force `bash` at the payload level:
  - `PromptRequest` carries an optional `requiredToolName`
  - `QueryEngine` sets `requiredToolName="bash"` for local filesystem/workspace questions on tool-capable providers
  - `OpenAiResponsesSupport` translates that into `tool_choice: { type: "function", name: "bash" }`
  - this closes the remaining case where Codex saw the tool but still answered with "run this command and paste the output"
- new regression coverage added:
  - `ui-ink/src/messages/buildMessageLookups.test.ts`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- tool/runtime follow-up fixes:
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiResponsesSupport.java` now drops nameless OpenAI/Codex `function` artifacts instead of persisting them as fake `toolName="function"` tool calls
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java` now covers the exact mixed stream shape where a real `bash` call and an unnamed function artifact arrive in the same stream
  - `core/src/main/java/com/openclaude/core/tools/ShellPermissionPolicy.java` now treats plain `echo` as read-only while still blocking redirection/background operators
  - `core/src/test/java/com/openclaude/core/tools/ShellPermissionPolicyTest.java` verifies that plain `echo` is allowed and redirected `echo` is still denied
  - `ui-ink/src/app.tsx` now renders a dedicated permission dialog so the shell command appears once in the dialog body instead of being repeated under every picker option
  - `ui-ink/src/test/userHappyPath.test.tsx` now verifies the permission overlay action copy instead of the old duplicated-command layout
- shared tool smoke suite:
  - `provider-spi/build.gradle` now enables `java-test-fixtures` so provider modules can import one shared tool contract instead of duplicating smoke assertions
  - `provider-spi/src/testFixtures/java/com/openclaude/provider/spi/testing/ToolSmokeAssertions.java` defines the current cross-provider tool smoke assertions:
    - bash tool advertised in the payload
    - exactly one real `bash` tool use with preserved arguments
    - no phantom `function`/`function_call` tool uses
    - expected lifecycle events for the tool
    - expected final text block
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java` now runs the shared bash tool smoke contract against the OpenAI transport/parser path
  - `provider-anthropic/src/test/java/com/openclaude/provider/anthropic/AnthropicApiClientTest.java` now runs the shared bash tool smoke contract against the Anthropic transport path and also verifies Anthropic's read-only bash implementation allows plain `echo`
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java` now covers the shared runtime directly for:
    - allowed `pwd`
    - allowed plain `echo`
    - denied mutating commands like `rm -rf`
  - `ui-ink/src/test/userHappyPath.test.tsx` now includes the explicit tool approval happy path at the TUI layer
- restored-session tool-history filtering:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java` now filters provider-visible tool history down to completed, known tool calls only
  - incomplete legacy tool turns are no longer replayed into the next provider request
  - malformed legacy tool names such as `function`/`function_call` are dropped from provider-visible history even if they exist in old persisted sessions
  - empty assistant turns created only by stripped tool metadata are also dropped from provider-visible history
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java` now has a regression that resumes a poisoned session containing:
    - an unresolved `bash` tool call
    - a legacy phantom `function` tool call/result
    - then verifies that a new prompt does not replay those stale tool blocks into the next provider request
- closed tool-trajectory filtering:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java` now preserves trailing `assistant(tool_use) + tool_result` blocks while the active tool loop is still running, but drops that same trajectory once a later user message starts a new turn without a follow-up assistant reply
  - this matches the Claude-style rule that orphaned completed tool results from a prior turn must not leak into the next user prompt
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java` now includes `submitSkipsOrphanedCompletedToolTrajectoryWithoutFollowUpAssistant`, which reproduces the live failure where a fresh `hi` inherits the answer from a previous folder-summary tool run
- directory-scoped session bootstrap:
  - `core/src/main/java/com/openclaude/core/session/ConversationSession.java` now stores `workingDirectory` and `workspaceRoot` alongside session timestamps and messages
  - `core/src/main/java/com/openclaude/core/session/ConversationSessionStore.java` now supports session listing and explicit session loading for resume selection
  - `app-cli/src/main/java/com/openclaude/cli/SessionBootstrap.java` now resolves the current workspace root, creates fresh sessions for normal startup, and scopes `--resume` without an id to sessions from the current directory/workspace
  - `app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java` now routes plain interactive startup and `--resume` through the new bootstrap path
  - `app-cli/src/main/java/com/openclaude/cli/stdio/StdioServerCommand.java` now starts a fresh session by default instead of reusing the last global active session
  - `app-cli/src/main/java/com/openclaude/cli/OpenClaudeCommand.java` now treats `--resume` as optional-id input, matching the “resume picker” behavior instead of only `--resume <id>`
  - `app-cli/src/test/java/com/openclaude/cli/SessionBootstrapTest.java` now covers:
    - fresh session creation with stored cwd/workspace metadata
    - explicit resume by session id
    - current-workspace resume selection
    - cross-directory isolation
    - fresh stdio startup without global session bleed
- Claude parser-backed terminal input:
  - `ui-ink/src/input/useTerminalInput.ts` no longer relies on Node's default keypress emitter
  - `ui-ink/src/input/claude-ink/parse-keypress.ts`, `ui-ink/src/input/claude-ink/termio/tokenize.ts`, `ui-ink/src/input/claude-ink/termio/csi.ts`, and `ui-ink/src/input/claude-ink/termio/ansi.ts` are now ported from Claude's input parser stack
  - `ui-ink/src/input/claude-ink/inputAdapter.ts` bridges Claude's parsed key events into OpenClaude's `InputKey` shape
  - `ui-ink/src/input/usePasteHandler.ts` now owns paste aggregation above the parser layer, matching Claude's `BaseTextInput -> useInput -> usePasteHandler` architecture instead of mixing paste heuristics into the raw stdin hook
  - this fixes the prior class of bugs where:
    - bracketed paste leaked escape gibberish
    - plain multiline paste could trigger submit incorrectly
    - Enter and slash-command submission depended on Node keypress behavior rather than Claude's parser
  - `ui-ink/src/test/userHappyPath.test.tsx` now covers:
    - bracketed paste
    - plain multiline paste
    - prompt submit after backspace and Enter
    - exact slash-command submit from the TUI
  - verified with:
    - `cd ui-ink && npm run typecheck`
    - `cd ui-ink && npm test`

## Claude-Backed Tool Expansion

- the default tool runtime is no longer `bash`-only:
  - `core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java`
  - tool order now follows the implemented subset of Claude's base registry from `claude-code/src/tools.ts`
- currently wired base tools:
  - `bash`
  - `Glob`
  - `Grep`
  - `ExitPlanMode`
  - `Read`
  - `Edit`
  - `Write`
  - `TodoWrite`
  - `EnterPlanMode`
- `OpenClaudeApplication` and `QueryEngine` now both use the shared default tool runtime instead of a standalone `BashToolRuntime`
- the tool execution contract now carries session context and session effects:
  - `core/src/main/java/com/openclaude/core/tools/ToolExecutionRequest.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolExecutionResult.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolSessionEffect.java`
- session state now persists Claude-style plan/todo state alongside transcript history:
  - `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
  - `core/src/main/java/com/openclaude/core/session/TodoItem.java`
  - `QueryEngine` applies returned tool session effects before saving the updated session
- new stateful Claude tool runtimes:
  - `core/src/main/java/com/openclaude/core/tools/TodoWriteToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/EnterPlanModeToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/ExitPlanModeToolRuntime.java`
- `/tools` and stdio now expose the expanded runtime-backed list instead of a `bash`-only backend
- direct runtime coverage added for each implemented tool:
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/FileReadToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/GlobToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/GrepToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/FileWriteToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/FileEditToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/TodoWriteToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/PlanModeToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/DefaultToolRuntimeTest.java`
- query/session persistence coverage added for stateful tool effects:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- stdio/UI coverage updated so `/tools` no longer masks a stale one-tool runtime:
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
- verified with:
  - `./gradlew :core:test :app-cli:test`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- next Claude base-tool batch:
  - `core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java` now exposes the current Claude-backed local tool set:
    - `bash`
    - `Glob`
    - `Grep`
    - `ExitPlanMode`
    - `Read`
    - `Edit`
    - `Write`
    - `TodoWrite`
    - `EnterPlanMode`
  - session-scoped plan/todo state is now durable in:
    - `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
    - `core/src/main/java/com/openclaude/core/session/TodoItem.java`
    - `core/src/main/java/com/openclaude/core/tools/ToolSessionEffect.java`
  - the tool loop now applies tool-produced session effects after each tool result in:
    - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
  - the new direct runtime tests are in:
    - `core/src/test/java/com/openclaude/core/tools/DefaultToolRuntimeTest.java`
    - `core/src/test/java/com/openclaude/core/tools/TodoWriteToolRuntimeTest.java`
    - `core/src/test/java/com/openclaude/core/tools/PlanModeToolRuntimeTest.java`
  - `/tools` coverage now asserts the new tool set through:
    - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
    - `ui-ink/src/test/userHappyPath.test.tsx`
  - session todos / plan mode are now exposed over stdio in:
    - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
    - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
    - `types/stdio/protocol.ts`
  - the Ink client now renders current-session todos / plan mode in:
    - `ui-ink/src/app.tsx`
  - verified with:
    - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test :app-cli:test`
    - `cd ui-ink && npm test`
    - `cd ui-ink && npm run typecheck`

## Environment Notes

Observed locally:

- default `java` points to Java 8
- a JDK 17 installation is the reliable Gradle launcher in this environment
- a JDK 24 installation is also present locally
- global `gradle` is broken on this host
- the packaged `openclaude` launcher requires a Java 21+ runtime because the project is compiled with `--release 21`

Mitigation:

- use the repo wrapper with `JAVA_HOME` set to the JDK 17 launcher in this environment
- compilation is configured for Java 21 bytecode via `--release 21`
- packaged launcher runs should use `JAVA_HOME` pointing to Java 21+ here
- local Gradle wrapper commands should be run with `JAVA_HOME=$(/usr/libexec/java_home -v 17)` here unless the host Gradle/JDK setup changes

## Parity Tracking

- `PARITY.md` is now the active parity checklist for `openclaude`
- it supersedes ad hoc parity tracking for day-to-day implementation sequencing
- it is grouped by Claude source areas and should be updated in the same change that lands parity work
- any intentional deviation from Claude parity should still be recorded in `DRIFT.md`

## 2026-04-02 AskUserQuestion Tool Slice

- `AskUserQuestion` is now a real runtime-backed tool in:
  - `core/src/main/java/com/openclaude/core/tools/AskUserQuestionToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java`
- the permission contract now carries interactive payloads so the UI can answer tool-driven questions:
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionDecision.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionRequest.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolExecutionUpdate.java`
  - `provider-spi/src/main/java/com/openclaude/provider/spi/ToolPermissionEvent.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
- the Ink client now renders a Claude-style question overlay and returns answers through stdio:
  - `ui-ink/src/app.tsx`
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/AskUserQuestionToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/DefaultToolRuntimeTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.DefaultToolRuntimeTest --tests com.openclaude.core.tools.AskUserQuestionToolRuntimeTest --tests com.openclaude.core.query.QueryEngineTest :app-cli:test`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this was the first AskUserQuestion runtime cut; preview and multi-select still remained on `PARITY.md` at that stage

## 2026-04-02 AskUserQuestion Preview And Multi-Select Slice

- `AskUserQuestion` moved forward on both runtime and Ink parity:
  - `core/src/main/java/com/openclaude/core/tools/AskUserQuestionToolRuntime.java`
  - `ui-ink/src/app.tsx`
- the Java runtime now accepts and formats `annotations` in tool results, including selected preview text and user notes when present
- the Ink permission overlay now supports:
  - Claude-style preview questions with a side-by-side option list and preview panel
  - multi-select answer collection with comma-separated answer strings
  - preview annotation round-trip in the payload returned through `permission.respond`
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/AskUserQuestionToolRuntimeTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.AskUserQuestionToolRuntimeTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is still not full Claude parity; notes entry, pasted-image attachments, and the richer plan-interview / respond-to-Claude branches remain on `PARITY.md`

## 2026-04-02 WebFetch Foundational Slice

- `WebFetch` is now in the default runtime:
  - `core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java`
- the current implementation covers:
  - URL validation
  - read-only permission gating by host
  - local `http` exceptions for `localhost` / `127.0.0.1`
  - same-host redirect following
  - cross-host redirect stop with a Claude-style retry message
  - readable-content extraction from fetched HTML/text
- direct runtime coverage is in:
  - `core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/DefaultToolRuntimeTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx` for `/tools` surface updates
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.DefaultToolRuntimeTest --tests com.openclaude.core.tools.AskUserQuestionToolRuntimeTest --tests com.openclaude.core.tools.WebFetchToolRuntimeTest --tests com.openclaude.core.query.QueryEngineTest :app-cli:test`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is still foundational, not full Claude parity; Claude's prompt-application path (`applyPromptToMarkdown`) and preapproved-host/cache semantics are still missing and remain unchecked in `PARITY.md`

## 2026-04-02 WebFetch Prompt-Application Slice

- `WebFetch` now applies the fetch result to a second model prompt instead of returning only raw fetched content:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolExecutionRequest.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolModelInvoker.java`
  - `core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java`
- the runtime now adds:
  - a scoped tool-model invoker passed from `QueryEngine`
  - Claude-style secondary prompt construction over fetched content plus the user prompt
  - a local 15-minute fetch cache before network re-fetch
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/AskUserQuestionToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.AskUserQuestionToolRuntimeTest --tests com.openclaude.core.tools.WebFetchToolRuntimeTest --tests com.openclaude.core.query.QueryEngineTest`
  - `./build.sh`
- parity note:
  - this is still partial versus Claude; OpenClaude currently uses the active provider/model for the secondary prompt instead of Claude's dedicated small-model path, and the preapproved-host / preflight / richer markdown-conversion semantics remain on `PARITY.md`

## 2026-04-02 Provider Readiness Gating

- executable-provider gating is now enforced so registry-only providers do not appear production-ready in v0:
  - `core/src/main/java/com/openclaude/core/provider/ProviderRegistry.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/ProviderService.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/ModelService.java`
  - `app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `app-cli/src/main/java/com/openclaude/cli/commands/ProviderCommands.java`
  - `app-cli/src/main/java/com/openclaude/cli/commands/ModelsCommand.java`
- the gate now does three things:
  - only providers with `supportsPromptExecution()` are surfaced in provider/model views
  - connect/use/model-select flows reject non-executable providers
  - stale saved active providers/models are sanitized on startup and before stdio snapshots
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/service/ProviderServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.ProviderServiceTest --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`

## 2026-04-02 Fast Command Slice

- `/fast` is now a surfaced slash command in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the Ink client now handles `/fast`, `/fast on`, and `/fast off` against the existing persisted settings path in:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
- TUI coverage landed in:
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.ProviderServiceTest --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is the v0 command surface for fast mode, not Claude's full fast-mode product logic around availability, org status, or cooldowns

## 2026-04-02 Status Command Slice

- `/status` is now a real backend-rendered panel in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the panel now reports v0 runtime state from the current backend snapshot:
  - executable provider status and active auth mode
  - active model and session id
  - tool-runtime availability for the active provider
  - workspace/environment basics for the current shell
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.ProviderServiceTest --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is a v0 status panel, not Claude's full settings-tab/status experience

## 2026-04-02 WebSearch Foundational Slice

- `WebSearch` is now in the default runtime:
  - `core/src/main/java/com/openclaude/core/tools/WebSearchToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java`
- the current implementation covers:
  - Claude-aligned input fields: `query`, `allowed_domains`, `blocked_domains`
  - permission-gated search execution
  - markdown source-link output with the same citation reminder shape the model needs downstream
  - domain allow/block filtering and deterministic result limiting
  - `/tools` and status-surface exposure through the default runtime count
- direct coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/WebSearchToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/DefaultToolRuntimeTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.DefaultToolRuntimeTest --tests com.openclaude.core.tools.WebSearchToolRuntimeTest :app-cli:test --tests com.openclaude.cli.service.ProviderServiceTest --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is still foundational, not full Claude parity; Claude uses Anthropic's built-in server web search tool with native streaming/progress semantics, while OpenClaude v0 currently uses a local search runtime and keeps the TODO item intentionally unchecked

## 2026-04-02 Tasks Command Slice

- `/tasks` and the Claude alias `/bashes` are now surfaced in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the Ink shell now routes both commands into the existing OpenClaude task panel in:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.ProviderServiceTest --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this exposes the command and alias, but it still reuses OpenClaude's simpler session task panel instead of Claude's full `BackgroundTasksDialog`, so the TODO item stays intentionally unchecked

## 2026-04-02 Clear Command Slice

- `/clear`, `/reset`, and `/new` now reset the active conversation from the Ink shell by creating a fresh scoped session and switching the active session id in:
  - `app-cli/src/main/java/com/openclaude/cli/service/SessionService.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
- the TUI regression now validates the live post-clear frame instead of the cumulative terminal output buffer in:
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is still a scoped-session reset, not Claude's full `/clear` lifecycle with session-end hooks, cache eviction, worktree/session metadata rotation, and foreground-task cleanup

## 2026-04-02 Permissions Command Slice

- `/permissions` and the Claude alias `/allowed-tools` are now surfaced as a backend-rendered panel in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the current v0 panel covers:
  - active provider/model/workspace summary
  - approval-sensitive tool grouping from the live runtime
  - the current bash permission policy summary
  - recent permission outcomes derived from the active session transcript
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is intentionally read-only for v0; Claude's full `PermissionRuleList` editor, persistent rule updates, and retry-denials flow are still not ported

## 2026-04-03 Structured Permission Editor API Slice

- the backend now exposes a structured stdio permissions-editor snapshot and mutation API in:
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `types/stdio/protocol.ts`
- the structured snapshot includes:
  - behavior tabs for `recent`, `allow`, `ask`, and `deny`
  - source-grouped Claude-style permission rules
  - recent denied permission activity derived from the active session transcript
- the mutation API supports:
  - `add`
  - `remove`
  - `clear`
  - `retry-denials`
- backend verification landed in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
- parity note:
  - the remaining gap is the full Ink `PermissionRuleList` editor and callback wiring, which is intentionally UI-side and not touched in this backend slice

## 2026-04-03 Permissions Overlay Reconciliation Slice

- removed the duplicate in-file `permissions-editor` implementation from `ui-ink/src/app.tsx` and reconciled the TUI back onto the existing `PermissionsOverlay.tsx` path instead of maintaining two competing permission UIs
- kept the backend `permissions.editor.*` protocol/test support in place, but stopped treating that backend slice as if the full Claude local-JSX editor had already landed
- refreshed the TUI coverage for the real overlay path in:
  - `ui-ink/src/test/permissionsOverlay.test.tsx`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `cd ui-ink && npm run typecheck`
  - `cd ui-ink && node --import tsx --test src/test/permissionsOverlay.test.tsx`
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx`
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
- status after this slice:
  - backend rule storage, source-aware loading, add/remove/clear/retry mutations, and the Ink overlay are green
  - the remaining `/permissions` parity gap is Claude's full local-JSX callback/runtime semantics, not the backend rule store itself

## 2026-04-02 Usage Command Slice

- `/usage` is now surfaced as a backend-rendered panel in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the current v0 panel covers:
  - active provider/auth/model summary
  - current-session estimated context usage
  - an explicit placeholder for provider/account quota APIs that are not wired yet
- the Ink happy-path and stdio/backend tests are now green after tightening the panel assertions to visible first-page content in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is a v0 usage summary panel, not Claude's full usage/settings-tab experience with account quota data

## 2026-04-02 Stats Command Slice

- `/stats` is now surfaced as a backend-rendered panel in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the current v0 panel aggregates local session history from `ConversationSessionStore` and reports:
  - total sessions/messages/active days
  - first/last session dates
  - longest session and peak activity day
  - assistant-turn counts by provider and model
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is a local aggregate stats panel, not Claude's fuller stats UI or full source-calculation parity

## 2026-04-02 Bracketed Paste Submit Fix

- fixed the Ink paste state machine in:
  - `ui-ink/src/input/usePasteHandler.ts`
- Enter now force-flushes a pending bracketed paste buffer before submit, so pasted multiline text can be submitted immediately instead of being dropped when the paste timeout has not expired yet.
- regression coverage stays in:
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`

## 2026-04-02 Plan Command Slice

- `/plan` is now surfaced as a frontend slash command in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `ui-ink/src/app.tsx`
- the current v0 behavior mirrors the first part of Claude's `/plan` flow:
  - if not already in plan mode, `openclaude` enables session plan mode through the backend
  - `/plan <description>` immediately forwards the inline description as the first planning prompt
  - if already in plan mode, `openclaude` reports that no written plan exists yet and shows the local v0 plan-state panel
- stdio/session mutation support for plan mode now exists in:
  - `app-cli/src/main/java/com/openclaude/cli/service/SessionService.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
  - `ui-ink/src/testing/fakeClient.ts`
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && node --import tsx --test --test-name-pattern '/plan enters plan mode and submits the inline description from the Ink TUI' src/test/userHappyPath.test.tsx`
- parity note:
  - this is still intentionally partial versus Claude; OpenClaude does not yet have Claude's plan-file storage, `/plan open` editor flow, or full plan-mode permission/runtime semantics

## 2026-04-02 Rewind Command Slice

- `/rewind` is now surfaced as a frontend slash command with Claude's `checkpoint` alias in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `ui-ink/src/app.tsx`
- the current v0 flow mirrors Claude's command shape:
  - `/rewind` opens a picker of prior user turns from the current session
  - selecting a turn truncates the session to just before that user message
  - the selected user prompt is restored into the input so it can be edited/resubmitted
- stdio/session support for the truncate step now exists in:
  - `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/SessionService.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
  - `ui-ink/src/testing/fakeClient.ts`
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && node --import tsx --test --test-name-pattern '/rewind opens the checkpoint picker and restores the selected user prompt' src/test/userHappyPath.test.tsx`
- parity note:
  - this is still intentionally partial versus Claude; OpenClaude does not yet have Claude's file-history/code rewind, confirmation path, or deeper rewind-state restoration beyond the conversation and prompt text

## 2026-04-02 Login / Logout Command Slice

- `/login` and `/logout` are now surfaced in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `ui-ink/src/app.tsx`
- the v0 behavior is the multi-provider auth analogue of Claude's Anthropic-only commands:
  - `/login` opens the provider picker so the user can select a provider and auth method
  - `/logout` disconnects the active provider from the current OpenClaude state
- provider credential cleanup now happens on disconnect in:
  - `app-cli/src/main/java/com/openclaude/cli/service/ProviderService.java`
  - `core/src/main/java/com/openclaude/core/config/ApiKeyStore.java`
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiOAuthStore.java`
- regression coverage landed in:
  - `app-cli/src/test/java/com/openclaude/cli/service/ProviderServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.ProviderServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this is intentionally partial versus Claude; OpenClaude does not yet mirror Claude's Anthropic-specific OAuth dialog, post-auth cache refresh cascade, or exit-on-logout lifecycle

## 2026-04-02 Session Scope Regression Coverage

- added direct session-scope regression coverage in:
  - `app-cli/src/test/java/com/openclaude/cli/SessionBootstrapTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/SessionServiceTest.java`
- current coverage now explicitly checks:
  - `--resume` picker isolates sessions to the current workspace
  - cross-directory sessions do not bleed into the current repo
  - `SessionService.listCurrentScopeSessions()` filters by workspace root and excludes the active session
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.SessionBootstrapTest --tests com.openclaude.cli.service.SessionServiceTest`

## 2026-04-02 File Mutation Read-State Slice

- ported the first Claude-backed file mutation safety layer into the Java session/runtime path:
  - `core/src/main/java/com/openclaude/core/session/FileReadState.java`
  - `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
  - `core/src/main/java/com/openclaude/core/tools/FileMutationGuards.java`
  - `core/src/main/java/com/openclaude/core/tools/FileReadToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/FileEditToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/FileWriteToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolSessionEffect.java`
- `Read` now records a durable per-file snapshot in the session:
  - file content
  - file mtime
  - read offset/limit
  - partial-view flag
- `Edit` and `Write` now enforce Claude's v0-critical file safety rules before requesting mutation permission:
  - existing files must have a prior `Read`
  - stale files are rejected if the disk copy changed after the read
  - if only the mtime changed but a full-read content snapshot still matches, the write/edit is allowed
- successful `Edit` and `Write` calls now refresh the session's read snapshot to the just-written content so subsequent mutations use fresh state
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/FileReadToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/FileEditToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/FileWriteToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/SessionBootstrapTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.FileReadToolRuntimeTest --tests com.openclaude.core.tools.FileEditToolRuntimeTest --tests com.openclaude.core.tools.FileWriteToolRuntimeTest --tests com.openclaude.core.query.QueryEngineTest`
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.SessionBootstrapTest`
- parity note:
  - this lands Claude's read-before-mutate and stale-file guards, but OpenClaude still does not mirror Claude's fuller file-edit stack around quote normalization, patch generation, encoding/line-ending preservation, backups, and notebook/file-history special cases

## 2026-04-02 AskUserQuestion Notes Slice

- ported the next Claude preview-question behavior into the Ink permission overlay:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- preview questions now support notes entry alongside the preview panel:
  - `n` opens notes editing for the focused preview option
  - the notes editor uses the same cursor-aware input pipeline as the rest of the TUI
  - saving notes preserves them through the final option selection and review screen
- the AskUserQuestion response payload now returns both:
  - the selected preview content
  - the user's notes for that question
- regression coverage landed in:
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this closes the preview-notes gap, but OpenClaude still does not mirror Claude's pasted-image attachments or plan-interview follow-up flow for `AskUserQuestion`

## 2026-04-02 WebFetch Preapproved Hosts Slice

- ported Claude's preapproved host/path gate for `WebFetch` into:
  - `core/src/main/java/com/openclaude/core/tools/WebFetchPreapprovedHosts.java`
  - `core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java`
- `WebFetch` now skips the permission prompt for the same class of code-related documentation hosts Claude preapproves, including path-scoped entries like `github.com/anthropics`
- boundary matching for path-scoped entries now mirrors Claude's segment rule:
  - `/anthropics` matches
  - `/anthropics/...` matches
  - `/anthropics-evil/...` does not match
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.WebFetchToolRuntimeTest`
- parity note:
  - this closes the preapproved-host gap, but OpenClaude still does not mirror Claude's dedicated small-model selection, Anthropic provider-side preflight/domain checks, or richer markdown conversion path

## 2026-04-02 TODO Ledger Cleanup + Bash/WebFetch Source-Anchored Slice

- cleaned `PARITY.md` so the high-level v0 summaries stop drifting from the detailed sections:
  - duplicated "already surfaced/landed" summaries now use plain bullets instead of checkbox state
  - surfaced-but-incomplete commands in the detailed sections are now marked consistently
  - known landed duplicates such as provider readiness gating and cross-directory session regression coverage are now aligned
- locked down the Claude `bashPermissions.ts` read-only behavior in OpenClaude's runtime tests:
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java`
  - read-only bash commands now have an explicit regression proving they do not request permission
- ported the exact Claude `WebFetchTool/prompt.ts` description and `makeSecondaryModelPrompt()` behavior into:
  - `core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java`
  - normal domains still get the strict quote/lyrics/legal guidance branch
  - preapproved domains now get Claude's relaxed documentation/details branch
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.BashToolRuntimeSmokeTest --tests com.openclaude.core.tools.WebFetchToolRuntimeTest`

## 2026-04-02 WebSearch Prompt/Result Direct Port

- ported the exact Claude `WebSearchTool/prompt.ts` tool description into:
  - `core/src/main/java/com/openclaude/core/tools/WebSearchToolRuntime.java`
- ported Claude's `mapToolResultToToolResultBlockParam` result shape into the local WebSearch runtime:
  - result text now uses the `Links: [{"title":"...","url":"..."}]` contract instead of the earlier local markdown list
  - the mandatory `Sources:` reminder remains verbatim
- added regression coverage in:
  - `core/src/test/java/com/openclaude/core/tools/WebSearchToolRuntimeTest.java`
  - verifies both the result-text contract and the model-facing tool description contract
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.BashToolRuntimeSmokeTest --tests com.openclaude.core.tools.WebFetchToolRuntimeTest --tests com.openclaude.core.tools.WebSearchToolRuntimeTest`
- parity note:
  - this ports Claude's prompt/result contract exactly, but OpenClaude still does not mirror Claude's Anthropic-native server-tool execution or streaming progress event model for `WebSearch`

## 2026-04-02 AskUserQuestion Footer Follow-Up Slice

- ported the next Claude `AskUserQuestionPermissionRequest` interaction branch into OpenClaude:
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionDecision.java`
  - `core/src/main/java/com/openclaude/core/tools/AskUserQuestionToolRuntime.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `ui-ink/src/app.tsx`
- `AskUserQuestion` now mirrors Claude's footer follow-up branches:
  - `Chat about this`
  - `Skip interview and plan immediately` when session plan mode is active
- deny-with-feedback now flows end-to-end through stdio instead of being treated as a plain rejection:
  - the Ink footer sends `permission.respond` with `decision: deny` and a Claude-shaped feedback payload
  - the tool runtime returns that feedback text to the model path instead of collapsing to `User declined to answer questions.`
- also fixed the AskUserQuestion overlay state machine so:
  - `Esc` exits notes/other editing without denying the whole tool
  - footer focus can be entered and exited cleanly from the option list
- regression coverage landed in:
  - `core/src/test/java/com/openclaude/core/tools/AskUserQuestionToolRuntimeTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.AskUserQuestionToolRuntimeTest :app-cli:test`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this closes the plan-interview/respond-to-Claude footer gap, but OpenClaude still does not mirror Claude's pasted-image attachments for `AskUserQuestion`

## 2026-04-02 Anthropic Shared-Tool Runtime Slice

- removed the old provider-private Anthropic bash loop and moved Anthropic onto the same block-based tool contract the shared `QueryEngine` expects:
  - `provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicApiClient.java`
  - `provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicProviderPlugin.java`
- Anthropic request payloads now:
  - serialize assistant `tool_use` blocks and user `tool_result` blocks from `PromptRequest.messages()`
  - advertise shared tool definitions from `PromptRequest.tools()`
  - apply forced tool selection with Anthropic `tool_choice: { type: "tool", name: ... }` when `QueryEngine` requires a specific tool such as `bash`
- Anthropic responses now return `PromptResult` content blocks instead of executing tools inside the provider:
  - `text` -> `TextContentBlock`
  - `thinking` -> `ReasoningDeltaEvent`
  - `tool_use` -> `ToolUseContentBlock`
- deleted the old provider-only `ReadOnlyBashTool` stopgap, so Anthropic tool execution now goes through the shared OpenClaude runtime instead of a hidden bash-only path
- regression coverage landed in:
  - `provider-anthropic/src/test/java/com/openclaude/provider/anthropic/AnthropicApiClientTest.java`
  - verifies structured tool trajectories in Anthropic payloads
  - verifies forced `tool_choice`
  - verifies Anthropic returns shared `ToolUseContentBlock`s without executing them
  - verifies plain text responses still map to a leading text block
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :provider-anthropic:test :core:test :app-cli:test`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
- parity note:
  - this closes the Anthropic shared-tool execution gap, but Anthropic streaming is still not Claude-parity yet

## 2026-04-02 Dev Backend Rebuild + Read-Only Bash Regression Slice

- added a real backend regression in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- that regression proves a read-only `bash` tool turn can complete through the real `QueryEngine` + `BashToolRuntime` path without ever surfacing a permission request
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`
  - `cd ui-ink && npm test`
  - `cd ui-ink && npm run typecheck`
  - `cd ui-ink && npm run dev` smoke, then clean exit with the session resume banner
- note:
  - the temporary `predev` backend rebuild hook was reverted so `npm run dev` remains UI-only; rebuilding the packaged Java backend is again an explicit manual step

## 2026-04-02 Desktop Bash + Live Tool Render Regression Slice

- locked the exact Desktop-style bash command shape into the shell/runtime tests instead of only covering `cd .` variants:
  - `core/src/test/java/com/openclaude/core/tools/ShellPermissionPolicyTest.java`
  - `core/src/test/java/com/openclaude/core/tools/BashToolRuntimeSmokeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/ToolEndToEndTest.java`
- covered:
  - `cd ~/desktop 2>/dev/null && find . -type f | wc -l` at the shell-policy layer
  - `cd ~ 2>/dev/null && pwd | wc -c` through the real bash runtime and the shared tool end-to-end suite
- hardened the Ink active-turn shell so the startup/home state cannot coexist with a live tool turn:
  - `ui-ink/src/app.tsx`
  - active turns now suppress the startup header / empty-state path whenever there is pending prompt content, live tool content, or permission interaction
- added a TUI regression for the exact live-render failure mode:
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - verifies that a live `bash` row with `cd ~/desktop 2>/dev/null && find . -type d | wc -l` shows the active command and that the empty-state copy does not reappear after the tool run starts
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.ShellPermissionPolicyTest --tests com.openclaude.core.tools.BashToolRuntimeSmokeTest --tests com.openclaude.core.tools.ToolEndToEndTest`
  - `cd ui-ink && npm test -- --runInBand userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`

## 2026-04-02 Repeated Desktop Tool Call Loop Guard

- fixed the query-loop bug where the provider could repeat the same completed Desktop bash tool call and OpenClaude would execute it again instead of forcing a final answer:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- new behavior:
  - track completed tool call signatures for the current turn
  - if the provider emits the same completed tool call again with no assistant text, do not execute it a second time
  - retry the model once with an explicit final-answer system prompt
  - fail loudly if the provider still repeats the exact completed tool call after that retry
- added a focused regression in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - covers the exact Desktop-count loop shape: first tool execution succeeds, second provider pass repeats the same bash command, OpenClaude suppresses the duplicate execution and gets a final answer instead
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH=\"$JAVA_HOME/bin:$PATH\"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`
  - `./build.sh`

## 2026-04-02 Permission Overlay + Startup Noise UI Fixes

- fixed the Ink permission interaction path so handled permission request ids are remembered for the active prompt run:
  - `ui-ink/src/app.tsx`
  - repeated `permission.requested` events with an already-handled `requestId` are now ignored instead of reopening the same overlay
- kept the interaction-close path optimistic in the UI and retained the queue-clearing logic around active permission overlays:
  - `ui-ink/src/app.tsx`
- removed startup status noise from the real CLI entrypoint while leaving the default/test app behavior unchanged:
  - `ui-ink/src/index.tsx`
  - the shipped runtime now starts with `showStartupStatusLines={false}`, so `Connected to OpenClaude backend over stdio.` is no longer rendered in the live CLI
- filtered backend empty-input parse noise from the visible status area:
  - `ui-ink/src/app.tsx`
  - `No content to map due to end-of-input` / matching invalid-stdio-request lines are now dropped from visible stderr status output
- adjusted tool-row styling so the tool name/header stays strong while command/result previews remain muted background activity:
  - `ui-ink/src/ui/messages/AssistantGroupedToolMessage.tsx`
  - `ui-ink/src/ui/messages/AssistantCollapsedReadSearchMessage.tsx`
- added a latest-frame getter to the TTY test harness for future UI-frame assertions:
  - `ui-ink/src/testing/testTerminal.ts`
- verified with:
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`

## 2026-04-02 /memory Command Slice

- surfaced `/memory` in the backend command registry so it shows up in the shared slash-command list:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- added AGENTS-based memory-file discovery for OpenClaude:
  - `ui-ink/src/local/memory.ts`
  - current discovery covers user `AGENTS.md`, user `rules/*.md`, workspace/project `AGENTS.md`, `AGENTS.local.md`, `.openclaude/AGENTS.md`, and `.openclaude/rules/*.md`
- added real file-editor launch support for editing a selected memory file:
  - `ui-ink/src/local/system.ts`
- wired a `/memory` picker into the Ink UI and hooked selection to the external editor flow:
  - `ui-ink/src/app.tsx`
- updated the fake client command surface and added regressions:
  - `ui-ink/src/testing/fakeClient.ts`
  - `ui-ink/src/local/memory.test.ts`
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
- scope note:
  - this is a v0 AGENTS-memory slice, not full Claude memory parity yet
  - missing Claude-depth pieces still include the richer `MemoryFileSelector` UX, auto-memory/team-memory folder entries, and memory update notification parity
- verified with:
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx src/local/memory.test.ts`
  - `cd ui-ink && npm run typecheck`
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest`

## 2026-04-02 /compact Command Slice

- surfaced `/compact` as a real backend command in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- added a real manual compaction service instead of a placeholder clear/reset flow:
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java`
  - it now:
    - loads the active scoped session
    - slices only the uncompacted suffix after the latest compact boundary + compact summary
    - asks the active provider for a no-tools conversation summary using a Claude-derived compact prompt
    - strips `<analysis>` content and stores the resulting continuation summary back into the session
    - appends `compact_boundary + compact_summary` to the full transcript instead of discarding prior history
- added explicit compact-boundary and compact-summary session support in:
  - `core/src/main/java/com/openclaude/core/session/SessionMessage.java`
  - compact boundaries are now durable session events and compact summaries are marked on user messages for future slice detection
- added a minimal typed post-compact attachment path in:
  - `core/src/main/java/com/openclaude/core/session/SessionAttachment.java`
  - `core/src/main/java/com/openclaude/core/session/SessionMessage.java`
  - attachment messages can now carry structured post-compact state and project it back into provider-visible prompt messages
- hardened the manual compact flow with post-compact state reinjection and stale cache cleanup:
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java`
  - it now:
    - restores up to 5 recently read files as post-compact attachments using Claude’s `POST_COMPACT_MAX_FILES_TO_RESTORE` / `POST_COMPACT_MAX_TOKENS_PER_FILE` / `POST_COMPACT_TOKEN_BUDGET` constants as the source reference
    - emits compact-file references instead of inline file content when the restore budget is exceeded
    - re-announces plan mode after compaction when the session is still in plan mode
    - clears `readFileState` after successful compact so mutation guards do not think the model still has invisible pre-compact file context
- aligned the compact request path more closely with the normal query path:
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java`
  - it now:
    - threads AGENTS-derived instructions into the compaction request via `AgentsInstructionsLoader`
    - projects typed attachment messages into provider-visible prompt messages instead of silently dropping them from the compact request path
- added compact-aware session projection and prompt history slicing:
  - `core/src/main/java/com/openclaude/core/session/SessionCompaction.java`
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/SessionService.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - future prompts and the live UI now read from the post-boundary slice while the full transcript remains on disk
  - prompt projection now includes typed attachment messages so post-compact restored state is visible to the model on the next turn
- updated the local help text and fake command surface for the new slash command:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
- added targeted coverage for:
  - real compaction append + prompt shape:
    - `app-cli/src/test/java/com/openclaude/cli/service/CompactConversationServiceTest.java`
  - backend command surface:
    - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
    - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - compact-aware prompt projection after the last boundary:
    - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - Ink slash-command path:
    - `ui-ink/src/test/userHappyPath.test.tsx`
- scope note:
  - this is the first honest `/compact` slice, not full Claude compact parity yet
  - missing Claude-depth pieces still include microcompact, session-memory compaction, reactive compaction, pre/post compact hooks, transcript relinking, richer attachment reinjection families, and broader post-compact cleanup semantics
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.service.CompactConversationServiceTest`
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`

## 2026-04-02 /compact Hooks + Preserved Tail Slice

- added compact-scoped hook loading and execution in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactHookConfigLoader.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactHooksExecutor.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/PostCompactCleanup.java`
- the manual compact flow in `app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java` now:
  - loads `PreCompact`, `PostCompact`, and `SessionStart(compact)` command hooks from:
    - `/etc/openclaude/settings.json`
    - `~/.openclaude/settings.json`
    - `<workspace>/.openclaude/settings.json`
    - `<workspace>/.openclaude/settings.local.json`
  - matches hook `matcher` values with Claude-style exact/pipe/regex semantics
  - feeds Claude-style compact hook JSON on stdin
  - merges successful `PreCompact` stdout into the compact instructions
  - surfaces `PreCompact` / `PostCompact` results in the command result text
  - turns successful `SessionStart(compact)` stdout into typed post-compact context attachments
  - centralizes post-compact cleanup through `PostCompactCleanup`
- extended compact boundary/session metadata in:
  - `core/src/main/java/com/openclaude/core/session/SessionMessage.java`
  - `core/src/main/java/com/openclaude/core/session/SessionAttachment.java`
  - `CompactBoundaryMessage` now supports optional preserved-tail metadata
  - `SessionAttachment` now supports `HookAdditionalContextAttachment`
- added preserved-tail projection in:
  - `core/src/main/java/com/openclaude/core/session/SessionCompaction.java`
  - `messagesAfterCompactBoundary(...)` can now splice a preserved pre-boundary tail back into the active post-compact view using boundary metadata
  - `sliceForCompaction(...)` now works from the compact-aware projected active view rather than raw “after last boundary” slicing
- added targeted coverage in:
  - `core/src/test/java/com/openclaude/core/session/SessionCompactionTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CompactConversationServiceTest.java`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.session.SessionCompactionTest --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.service.CompactConversationServiceTest`

## 2026-04-02 Session Memory Subsystem Slice

- standardized on the existing `core/sessionmemory` implementation and removed the duplicate `core/session` branch before wiring more compact behavior on top
- landed hidden per-session session memory storage and extraction in:
  - `core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryService.java`
  - `core/src/main/java/com/openclaude/core/sessionmemory/SessionMemoryPrompts.java`
  - `core/src/main/java/com/openclaude/core/session/SessionMemoryState.java`
  - `core/src/main/java/com/openclaude/core/session/ConversationSession.java`
  - `core/src/main/java/com/openclaude/core/config/OpenClaudePaths.java`
- the session-memory runtime now:
  - keeps a hidden notes sidecar under `~/.openclaude/session-memory/sessions/<sessionId>.md`
  - loads optional custom template/prompt files from `~/.openclaude/session-memory/config/{template,prompt}.md`
  - persists extraction cursor state in the session JSON via `SessionMemoryState`
  - uses Claude-aligned thresholds:
    - initialize at `10_000` estimated tokens
    - update after `5_000` additional estimated tokens
    - prefer updates after `3` tool calls or a natural non-tool assistant turn
  - runs extraction asynchronously after successful prompt completion and waits up to `15s` when `/compact` needs a fresh notes snapshot
  - uses provider streaming when available so browser-auth OpenAI paths do not fail on stream-only transports
- wired session-memory compaction into:
  - `app-cli/src/main/java/com/openclaude/cli/service/CompactConversationService.java`
  - `/compact` now tries session-memory compaction first when no custom compact instructions are present
  - preserved-tail relinking is now anchored to the real `lastSummarizedMessageId` from session memory state instead of the post-compact summary message id
  - when no anchor exists yet, `/compact` falls back to directly appending the kept tail instead of dropping it
- wired post-turn extraction into:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- added/updated targeted coverage in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CompactConversationServiceTest.java`
  - `core/src/test/java/com/openclaude/core/session/SessionCompactionTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew --rerun-tasks :core:test --tests com.openclaude.core.query.QueryEngineTest --tests com.openclaude.core.session.SessionCompactionTest :app-cli:test --tests com.openclaude.cli.service.CompactConversationServiceTest`

## 2026-04-02 Microcompact + Reactive Compact Slice

- landed time-based microcompact in:
  - `core/src/main/java/com/openclaude/core/query/TimeBasedMicrocompact.java`
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- the microcompact runtime now:
  - runs before provider requests on the main prompt path
  - follows Claude's time-based rule of only firing when the gap since the last assistant turn exceeds `60` minutes
  - only clears compactable tool results (`Read`, `bash`, `Grep`, `Glob`, `WebSearch`, `WebFetch`, `Edit`, `Write`)
  - keeps the most recent `5` compactable tool results and replaces older ones with `[Old tool result content cleared]`
  - persists the rewritten tool results back into the session transcript before the request is sent
- landed reactive compact retry in:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- the reactive compact path now:
  - detects prompt-too-long style provider failures from the normal query path and the tool loop
  - waits for any in-flight session-memory extraction
  - tries the session-memory compact candidate first
  - falls back to a provider-backed no-tools compact summary while preserving the newest user prompt when no session-memory anchor exists
  - appends a real `compact_boundary + compact_summary + kept tail` recovery block and retries the turn once instead of failing immediately
  - updates `SessionMemoryState` to anchor future extraction after the new compact summary message
- added/updated targeted coverage in:
  - `core/src/test/java/com/openclaude/core/query/TimeBasedMicrocompactTest.java`
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CompactConversationServiceTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew --rerun-tasks :core:test --tests com.openclaude.core.query.TimeBasedMicrocompactTest --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.service.CompactConversationServiceTest`

## 2026-04-02 Persisted Permission Rules Slice

- landed backend-backed persisted exact-match permission decisions in:
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionRule.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionGateway.java`
  - `core/src/main/java/com/openclaude/core/tools/AbstractSingleToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/config/OpenClaudeSettings.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
- the permission runtime now:
  - persists non-interactive allow/deny decisions into OpenClaude state
  - auto-reuses exact-match decisions before emitting a permission prompt
  - suppresses `permission_requested` for tool calls that already match a persisted rule
  - exposes persisted rules in `/permissions`
  - supports `/permissions clear` to wipe the persisted cache
- added/updated targeted coverage in:
  - `core/src/test/java/com/openclaude/core/tools/FileWriteToolRuntimeTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.FileWriteToolRuntimeTest :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`

## 2026-04-03 Claude-Style Permission Sources Slice

- switched permission rule storage toward Claude's source model instead of an openclaude-only cache shape:
  - Claude-style `permissions.allow/deny/ask` rule strings are now loaded from:
    - `/etc/openclaude/settings.json`
    - `~/.openclaude/settings.json`
    - `<workspace>/.openclaude/settings.json`
    - `<workspace>/.openclaude/settings.local.json`
  - session-scoped rules remain in OpenClaude state for prompt-time decisions
- added Claude-style rule-string parsing/formatting in:
  - `core/src/main/java/com/openclaude/core/tools/PermissionRuleStringCodec.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionSources.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolPermissionRule.java`
- added source-aware permission loading and mutation in:
  - `app-cli/src/main/java/com/openclaude/cli/service/PermissionRulesStore.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `app-cli/src/main/java/com/openclaude/cli/service/SessionService.java`
- `/permissions` now supports:
  - listing rules grouped by source
  - `/permissions add <source> <allow|deny|ask> <Rule>`
  - `/permissions remove <source> <allow|deny|ask> <Rule>`
  - `/permissions clear [source]`
  - `/permissions retry-denials`
- `/permissions retry-denials` now clears matching session deny rules and appends a Claude-style `Allowed ...` retry marker to the active session
- added/updated coverage in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./build.sh`

## 2026-04-03 Permissions Editor Cutover Slice

- removed the remaining mixed panel/editor `/permissions` path in the Ink app and cut blank `/permissions` over to the structured stdio editor snapshot instead of `command.run`
- blank `/permissions` now:
  - requests `permissions.editor.snapshot`
  - opens a local Ink editor with Claude-style tabs
  - supports local search on allow/ask/deny tabs
  - supports add-rule input plus source selection
  - supports rule-details delete confirmation
  - supports recent-tab retry-denials through `permissions.editor.mutate`
- explicit `/permissions ...args` still stay on the backend command path
- updated the new editor rendering in:
  - `ui-ink/src/ui/PermissionsOverlay.tsx`
- updated the app state machine in:
  - `ui-ink/src/app.tsx`
- updated fake client support and TUI coverage in:
  - `ui-ink/src/testing/fakeClient.ts`
  - `ui-ink/src/test/permissionsOverlay.test.tsx`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `cd ui-ink && npm run typecheck`
  - `cd ui-ink && node --import tsx --test src/test/permissionsOverlay.test.tsx`
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx`
- remaining honest gap:
  - Claude's full `PermissionRuleList` callback/runtime semantics are still not complete, especially workspace-directory editing and on-exit retry callback behavior
## 2026-04-03 Web Tool Native-Path Slice

- widened the secondary-model contract behind tools via:
  - `core/src/main/java/com/openclaude/core/tools/ToolModelInvoker.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolModelRequest.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolModelResponse.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolModelProgress.java`
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- extended provider tool definitions to carry native provider tool metadata in:
  - `provider-spi/src/main/java/com/openclaude/provider/spi/ProviderToolDefinition.java`
  - `provider-spi/src/main/java/com/openclaude/provider/spi/WebSearchResultContentBlock.java`
  - `provider-spi/src/main/java/com/openclaude/provider/spi/PromptContentBlock.java`
- `WebFetchToolRuntime` now requests provider-specific small-fast secondary models instead of always piggybacking on the main loop model
- `WebSearchToolRuntime` now prefers provider-native search:
  - Anthropic: `web_search_20250305`
  - OpenAI: native `web_search`
  - local HTML search remains as the fallback when native search is unavailable or insufficient for the requested filters
- Anthropic now parses native `web_search_tool_result` blocks, and OpenAI now extracts `url_citation` annotations from Responses output
- tests added/updated:
  - `core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/WebSearchToolRuntimeTest.java`
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java`
  - `provider-anthropic/src/test/java/com/openclaude/provider/anthropic/AnthropicApiClientTest.java`
- verified with:
  - `./gradlew :core:test --tests com.openclaude.core.tools.WebFetchToolRuntimeTest --tests com.openclaude.core.tools.WebSearchToolRuntimeTest`
  - `./gradlew --rerun-tasks :provider-openai:test --tests com.openclaude.provider.openai.OpenAiApiClientTest :provider-anthropic:test --tests com.openclaude.provider.anthropic.AnthropicApiClientTest`

## 2026-04-03 Web Tool Closure Slice

- closed the remaining `v0-code` gaps for `WebFetchTool` in:
  - `core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java`
- specifically added:
  - Claude-style same-site redirect handling that allows `www` add/remove redirects while still refusing cross-site redirects
  - host-keyed Anthropic domain-preflight caching
  - provider-specific preflight semantics so Anthropic uses the domain check and non-Anthropic providers do not
  - richer HTML-to-markdown conversion with absolute links, images, tables, fenced code, and safer whitespace normalization
- closed the remaining `v0-code` gaps for `WebSearchTool` in:
  - `core/src/main/java/com/openclaude/core/tools/WebSearchToolRuntime.java`
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiResponsesSupport.java`
- specifically added:
  - native-only provider trajectory for supported providers
  - Claude-style `query_update` / `search_results_received` progress text in the runtime path
  - query-aware OpenAI native web-search tool event parsing so native tool updates preserve the actual search query instead of opaque JSON
- updated tests in:
  - `core/src/test/java/com/openclaude/core/tools/WebFetchToolRuntimeTest.java`
  - `core/src/test/java/com/openclaude/core/tools/WebSearchToolRuntimeTest.java`
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.tools.WebFetchToolRuntimeTest --tests com.openclaude.core.tools.WebSearchToolRuntimeTest`
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :provider-openai:test --tests com.openclaude.provider.openai.OpenAiApiClientTest :provider-anthropic:test --tests com.openclaude.provider.anthropic.AnthropicApiClientTest`

## 2026-04-03 Runtime Orchestration + Effort Semantics Slice

- switched back to the higher-value `v0-code` runtime blocker instead of continuing command-only cleanup
- `QueryEngine` now batches consecutive concurrency-safe read-only tool calls together and runs that batch concurrently, closer to Claude's `toolOrchestration.ts`
- concurrency-safe batching currently covers:
  - `Read`
  - `Grep`
  - `Glob`
  - `WebFetch`
  - `WebSearch`
  - read-only `bash` commands validated through `ShellPermissionPolicy`
- concurrent batch results now preserve mutable session effects after the batch completes instead of forcing the whole tool loop through strictly serial execution
- `OpenClaudeEffort` now mirrors Claude-style `CLAUDE_CODE_EFFORT_LEVEL` override semantics
- `OpenClaudeStateStore` now tracks current-session effort separately from persisted settings so session-only `max` does not leak into saved settings
- `CommandService`, `QueryEngine`, `CompactConversationService`, and stdio snapshots now resolve effort from the same current-session/effective path instead of diverging
- tests added/updated:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`

## 2026-04-03 Runtime-Owned Concurrency Classification Slice

- finished the next Claude-shaped orchestration cleanup by moving concurrency safety out of `QueryEngine` name guessing and into the tool runtime boundary
- added `ToolRuntime.isConcurrencySafe(toolName, inputJson)` and routed it through:
  - `core/src/main/java/com/openclaude/core/tools/ToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/CompositeToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/AbstractSingleToolRuntime.java`
- `QueryEngine` now partitions tool batches using the runtime contract instead of a hardcoded switch on tool names:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- landed tool-specific concurrency declarations in:
  - `core/src/main/java/com/openclaude/core/tools/BashToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/FileReadToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/GrepToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/GlobToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/WebFetchToolRuntime.java`
  - `core/src/main/java/com/openclaude/core/tools/WebSearchToolRuntime.java`
- strengthened the batching regression so it now uses custom tool names and only passes if `QueryEngine` is consulting the runtime contract:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- verified with:
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH="$JAVA_HOME/bin:$PATH"; export GRADLE_USER_HOME=.gradle; ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`

## 2026-04-03 Prompt Mode Plumbing Slice

- ported the Claude `inputModes.ts` seam into `ui-ink`:
  - `ui-ink/src/prompt/inputModes.ts`
- added a dedicated prompt-mode indicator component:
  - `ui-ink/src/ui/PromptInputModeIndicator.tsx`
- wired `app.tsx` to separate raw prompt mode from displayed input:
  - leading `!` at cursor position `0` flips the prompt into bash mode instead of being rendered as a literal character
  - pasted `!cmd` into empty input enters bash mode and strips the mode character from the composer
  - bash mode suppresses slash/file prompt suggestions
  - stash, history restore, external-editor round trips, and submit now preserve the prompt mode through the Claude-style display/raw split
  - slash commands remain prompt-mode only; bash mode submits the `!`-prefixed text to the backend
- updated prompt theme support for the bash-mode marker:
  - `ui-ink/src/ui/theme.ts`
- added tests in:
  - `ui-ink/src/prompt/inputModes.test.ts`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `cd ui-ink && npm test -- --runInBand src/prompt/inputModes.test.ts src/test/userHappyPath.test.tsx`
  - `cd ui-ink && npm run typecheck`

## 2026-04-03 TextInput Extraction Slice

- added the first real Claude-shaped input hook boundary in:
  - `ui-ink/src/input/useTextInput.ts`
- the new hook now owns:
  - editor routing through `applyInputSequence`
  - submit/history fallback callbacks
  - double-escape clear behavior
  - `Ctrl-D` empty-input exit behavior
  - viewport/cursor reporting for renderer consumption
- extracted a real renderer layer in:
  - `ui-ink/src/ui/BaseTextInput.tsx`
  - `ui-ink/src/ui/TextInput.tsx`
- `BaseTextInput` now renders from hook state instead of `app.tsx` directly owning wrapped-layout rendering
- `TextInput` now owns:
  - terminal input hookup
  - paste wrapping
  - the `beforeInput` interception seam for higher-level app shortcuts
- `app.tsx` now keeps the high-level overlay/shortcut/mode orchestration, but the main text-editing branch no longer manually owns submit/history/edit routing
- verified with:
  - `cd ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx src/prompt/inputModes.test.ts`
  - `cd ui-ink && npm run typecheck`

## 2026-04-03 Backend Tool Pairing Repair Slice

- updated `core/src/main/java/com/openclaude/core/query/QueryEngine.java` so provider-bound history no longer drops interrupted tool trajectories by default
- prompt-building now:
  - preserves completed tool-only trajectories even when no final assistant text followed
  - filters legacy/unknown tool names from assistant tool uses
  - synthesizes missing `tool_result` blocks with the Claude placeholder text `[Tool result missing due to internal error]`
  - drops orphaned filtered tool results by only pairing against retained tool-use IDs
- updated `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java` to invert the old "skip broken tool turns" expectations into repair/preserve expectations
- verified with:
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`

## 2026-04-03 Early Streamed Tool Failure Persistence Slice

- updated `core/src/main/java/com/openclaude/core/query/QueryEngine.java` so concurrency-safe streamed tools discovered before provider return are persisted even if the provider stream fails
- on provider-stream failure after `ToolUseDiscoveredEvent`, `openclaude` now:
  - appends the assistant tool-use message into the session
  - awaits and persists the tracked read-only tool results
  - then lets the outer failure path append the tombstone
- added a regression in `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java` covering `ToolUseDiscoveredEvent -> tool executes -> provider throws`
- verified with:
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`

## 2026-04-03 Typed Hook Progress Runtime-Message Slice

- extended `core/src/main/java/com/openclaude/core/session/SessionMessage.java` so `ProgressMessage` can carry typed hook metadata:
  - `progressKind`
  - `toolUseId`
  - `hookEvent`
  - `hookName`
  - `command`
  - `isError`
- added tool-hook config loading and execution in:
  - `core/src/main/java/com/openclaude/core/tools/ToolHookConfigLoader.java`
  - `core/src/main/java/com/openclaude/core/tools/ToolHooksExecutor.java`
- `QueryEngine` now executes `PreToolUse`, `PostToolUse`, and `PostToolUseFailure` command hooks around runtime-managed tools and persists their emitted progress/attachment rows:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
- extended the stdio snapshot so progress rows preserve typed hook metadata instead of collapsing everything to plain text:
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
- preserved `HookAdditionalContextAttachment` hook metadata through session persistence:
  - `core/src/main/java/com/openclaude/core/session/SessionAttachment.java`
- `buildMessageLookups.ts` now indexes typed hook progress rows by tool-use id, not only hook attachment rows:
  - `ui-ink/src/messages/buildMessageLookups.ts`
- `normalizeMessages.ts` now reorders typed `PreToolUse` / `PostToolUse` progress rows around the matching tool trajectory before grouped rendering:
  - `ui-ink/src/messages/normalizeMessages.ts`
- added regressions in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/messages/buildMessageLookups.test.ts`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
- verified with:
  - `cd openclaude/ui-ink && node --import tsx --test src/messages/buildMessageLookups.test.ts src/messages/normalizeMessages.test.ts`
  - `cd openclaude/ui-ink && npm run typecheck`
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`

## 2026-04-03 Tool Attachment Pairing and Hook Row Suppression Slice

- fixed provider-visible tool history in `core/src/main/java/com/openclaude/core/query/QueryEngine.java` so tool-scoped hook attachments no longer flush a pending tool trajectory before its real `tool_result`
- `PendingToolTrajectory` now buffers tool-scoped hook attachments and emits:
  - `assistant(tool_use)`
  - pre-tool hook attachments
  - real `tool_result`
  - post-tool hook attachments
- this closes the synthetic-placeholder regression where post-tool hook attachments could make the provider see `[Tool result missing due to internal error]` even though the real tool output existed later in the session
- added backend verification in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
- extended `ui-ink/src/messages/buildMessageLookups.ts` with `hasUnresolvedHooksFromLookup(...)`
- `ui-ink/src/messages/normalizeMessages.ts` now:
  - keeps a grouped tool row in `running` state while `PostToolUse` / `PostToolUseFailure` hooks remain unresolved
  - suppresses tool-scoped hook progress rows and `hook_additional_context` attachments from standalone transcript rendering when they already belong to a grouped tool trajectory
- added UI regressions in:
  - `ui-ink/src/messages/buildMessageLookups.test.ts`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
- verified with:
  - `cd openclaude/ui-ink && node --import tsx --test src/messages/buildMessageLookups.test.ts src/messages/normalizeMessages.test.ts`
  - `cd openclaude/ui-ink && npm run typecheck`
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest`

## 2026-04-03 Streaming Tool Executor Lifecycle Slice

- `core/src/main/java/com/openclaude/core/query/QueryEngine.java` now routes early streamed read-only tools through `executeToolWithHooks(...)` instead of the thinner `executeToolSafely(...)` path
- the streaming executor now buffers both lifecycle updates and hook/session messages in execution order, then replays them into persisted session state before appending the paired `tool_result`
- early speculative tools that were discovered during provider streaming but never confirmed in the final assistant response are now discarded before the turn completes instead of being left hanging in the executor
- early streamed tools now execute against a synthetic assistant/tool-use session context rather than the pre-turn base session, so hooks and tool-side context see the real tool trajectory earlier
- permission interaction metadata now persists through session storage and stdio snapshots:
  - `core/src/main/java/com/openclaude/core/session/SessionMessage.java`
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
- added regressions in:
  - `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
- verified with:
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd openclaude/ui-ink && npm run typecheck`

## 2026-04-03 Prompt / REPL Parity Closure Slice

- moved the prompt shell out of the old inline `app.tsx` render block and into dedicated UI pieces:
  - `ui-ink/src/ui/ReplShell.tsx`
  - `ui-ink/src/ui/PromptInputPanel.tsx`
  - `ui-ink/src/ui/StatusLine.tsx`
- extracted prompt-local key resolution into `ui-ink/src/prompt/keybindings.ts`
  - autocomplete navigation/accept/execute
  - prompt-local history-search bindings
  - model picker / fast / verbose / tasks / stash / external editor actions
  - bash-mode enter/exit handling
  - busy-turn cancel routing
- completed the remaining `BaseTextInput`/`TextInput` parity gap for the v0 coding path:
  - terminal-focus-aware cursor gating
  - placeholder hiding
  - child rendering
  - retained inline ghost text, bracketed-paste state, and wrapped viewport counters
- completed the remaining `PromptInput` state-machine slice for the v0 coding path:
  - startup-time prompt-history preload
  - prompt-local history availability immediately after boot
  - command-argument acceptance and execution for `/resume` and `/models`
  - prompt footer help now driven from the extracted keybinding layer
- broadened prompt typeahead for v0 coding:
  - workspace-scoped `/resume <session-id>` suggestions
  - provider-scoped `/models <provider:model>` suggestions
- added a real prompt-shell status line that shows:
  - provider
  - model
  - context usage
  - fast/effort/mode/todo state
  - ready / busy / interrupting activity text
- tightened exit/interrupted flow for the prompt shell:
  - busy-turn `ctrl+c` now drives explicit interrupt state in the status line until cancellation settles
  - history-search and prompt-history restore are ready earlier during startup instead of racing the first prompt keypresses
- added and updated UI regressions in:
  - `ui-ink/src/prompt/suggestions.test.ts`
  - `ui-ink/src/input/useTextInput.test.tsx`
  - `ui-ink/src/ui/BaseTextInput.test.tsx`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- verified with:
  - `cd openclaude/ui-ink && npm run typecheck`
  - `cd openclaude/ui-ink && node --import tsx --test src/prompt/suggestions.test.ts src/input/useTextInput.test.tsx src/ui/BaseTextInput.test.tsx`
  - `cd openclaude/ui-ink && node --import tsx --test src/test/userHappyPath.test.tsx`

## 2026-04-03 Tool Orchestration And Message Pipeline Closure Slice

- removed the leftover four-tool-only heuristic from `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
  - the v0 coding path now honors eight real tool-use turns
  - final-answer retries no longer spend the same budget as real tool turns
  - the eight-step orchestration regression in `core/src/test/java/com/openclaude/core/query/QueryEngineTest.java` now passes
- completed the backend message metadata needed for Claude-style tool grouping in:
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - `types/stdio/protocol.ts`
  - explicit `assistantMessageId`, `siblingToolIds`, `toolGroupKey`, `toolRenderClass`, attachment metadata, and typed hook-progress fields now survive through the stdio snapshot
- completed the frontend message pipeline in:
  - `ui-ink/src/messages/buildMessageLookups.ts`
  - `ui-ink/src/messages/groupToolUses.ts`
  - `ui-ink/src/messages/collapseReadSearchGroups.ts`
  - `ui-ink/src/messages/normalizeMessages.ts`
  - grouped sibling tool families now normalize through backend-owned group keys instead of row-id heuristics
  - `reorderMessagesForUI(...)` now re-emits `tool_use -> hook/tool lifecycle -> tool_result` at the tool-use position
  - read/search/list/bash families now collapse after grouping, and grouped tool rows keep clean command previews instead of leaking raw input JSON
- replaced the old handwritten markdown path with a `marked`-based renderer in `ui-ink/src/ui/Markdown.tsx`
  - added `Markdown`, `StreamingMarkdown`, and `InlineMarkdown`
  - tables and links now render as terminal-friendly content instead of raw markdown syntax
  - prompt XML tags like `<analysis>` / `<summary>` are stripped before rendering
- added focused markdown coverage in `ui-ink/src/ui/Markdown.test.tsx`
- updated the TUI test runner in `ui-ink/package.json` to use `--test-concurrency=1`
  - Ink/raw-stdin happy-path tests share terminal state and were flaky under the default concurrent Node test runner
- updated regressions in:
  - `ui-ink/src/messages/buildMessageLookups.test.ts`
  - `ui-ink/src/messages/normalizeMessages.test.ts`
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - `ui-ink/src/ui/Markdown.test.tsx`
- verified with:
  - `cd openclaude/ui-ink && npm test`
  - `cd openclaude/ui-ink && npm run typecheck`
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :core:test --tests com.openclaude.core.query.QueryEngineTest :app-cli:test --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && ./build.sh`

## 2026-04-03 Diagnostics, Context, And Limits Closure Slice

- added provider/runtime diagnostics primitives in:
  - `provider-spi/src/main/java/com/openclaude/provider/spi/ProviderHttpException.java`
  - `core/src/main/java/com/openclaude/core/provider/ProviderLimitState.java`
  - `core/src/main/java/com/openclaude/core/provider/ProviderRuntimeDiagnostics.java`
  - `core/src/main/java/com/openclaude/core/provider/ProviderFailureClassifier.java`
- extended persisted provider state in:
  - `core/src/main/java/com/openclaude/core/provider/ProviderConnectionState.java`
  - `core/src/main/java/com/openclaude/core/config/OpenClaudeStateStore.java`
  - provider/runtime health, last success, last failure, and last observed limit state now survive across sessions
- added context-window metadata to provider model descriptors and populated the active v0 providers:
  - `provider-spi/src/main/java/com/openclaude/provider/spi/ModelDescriptor.java`
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiProviderPlugin.java`
  - `provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicProviderPlugin.java`
- switched provider clients to throw typed HTTP failures instead of generic runtime errors:
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiApiClient.java`
  - `provider-openai/src/main/java/com/openclaude/provider/openai/OpenAiCodexResponsesClient.java`
  - `provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicApiClient.java`
- reused the real query projection path for diagnostics in:
  - `core/src/main/java/com/openclaude/core/query/QueryEngine.java`
  - context estimation now uses provider-visible projected prompt messages instead of a local approximation
- closed the backend `/context`, `/usage`, and `/doctor` diagnostics path in:
  - `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`
  - `/context` now renders backend context analysis with projected prompt messages, token budget, breakdown, and health
  - `/usage` now reports real provider/auth/model/runtime diagnostics and last observed limit state instead of a placeholder
  - `/doctor` now includes provider/model/runtime/context diagnostics beyond local filesystem checks
- classified provider failures at the stdio boundary in:
  - `app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java`
  - prompt failures now surface `rate_limit`, `policy_limit`, and `auth_error` protocol errors instead of always collapsing to `runtime_error`
- moved `/context` onto the backend command path in the Ink client:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
  - the context panel now behaves like the other backend diagnostics panels instead of a local-only overlay
- improved context-panel visibility in `ui-ink/src/app.tsx`
  - the flattened context panel now surfaces projected-message and token-budget summary lines at the top of the first page instead of hiding the important numbers below the fold
- updated diagnostics regressions in:
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - `app-cli/src/test/java/com/openclaude/cli/stdio/OpenClaudeStdioServerTest.java`
  - `ui-ink/src/test/userHappyPath.test.tsx`
- updated the parity ledger in `PARITY.md`
- verified with:
  - `cd openclaude/ui-ink && npm run typecheck`
  - `cd openclaude/ui-ink && npm test -- --runInBand src/test/userHappyPath.test.tsx`
  - `cd openclaude && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH="$JAVA_HOME/bin:$PATH" && export GRADLE_USER_HOME=.gradle && ./gradlew :app-cli:test --tests com.openclaude.cli.service.CommandServiceTest --tests com.openclaude.cli.stdio.OpenClaudeStdioServerTest`

## 2026-04-03 Provider Parity And Test-Gate Closure Slice

- closed real Anthropic streaming parity in:
  - `provider-anthropic/src/main/java/com/openclaude/provider/anthropic/AnthropicApiClient.java`
  - Anthropic now sends `stream: true`, consumes SSE `content_block_*` events, emits text/thinking deltas, discovers streamed tool uses, and surfaces native web-search progress/results instead of replaying a completed non-streaming turn
- hardened OpenAI provider failure coverage in:
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiApiClientTest.java`
  - `provider-openai/src/test/java/com/openclaude/provider/openai/OpenAiCodexResponsesClientTest.java`
  - added non-2xx execute/stream tests and stream-error-event coverage for API-key and browser-auth paths
- tightened provider streaming/test coverage in:
  - `provider-anthropic/src/test/java/com/openclaude/provider/anthropic/AnthropicApiClientTest.java`
  - added SSE text/thinking, streamed tool-use, native web-search progress, non-2xx, and error-event regressions
- closed remaining surfaced-command TUI/backend coverage in:
  - `ui-ink/src/app.tsx`
  - `ui-ink/src/testing/fakeClient.ts`
  - `ui-ink/src/test/userHappyPath.test.tsx`
  - `app-cli/src/test/java/com/openclaude/cli/service/CommandServiceTest.java`
  - injected safe test seams for clipboard, keybindings, and exit output
  - added TUI coverage for `/cost`, `/diff`, `/doctor`, `/copy`, `/keybindings`, and `/exit`
  - added backend coverage for `/diff`
- synced the parity ledger in `PARITY.md`
