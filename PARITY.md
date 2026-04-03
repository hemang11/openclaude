# OpenClaude Parity TODO

This file is the working parity ledger for `openclaude`.

Rules:

- Source of truth is `../claude-code`, not memory and not approximations.
- Do not check an item off unless the corresponding runtime/UI behavior exists and is covered by tests.
- If `openclaude` intentionally diverges from Claude, record that in `DRIFT.md` and keep the parity item unchecked here unless the divergence is explicitly accepted.
- For instruction-file parity, `openclaude` must use `AGENTS.md` semantics instead of Claude's `CLAUDE.md` naming when that subsystem is wired.

Reference anchors:

- Commands: `../claude-code/src/commands/*`
- REPL/TUI: `../claude-code/src/screens/REPL.tsx`
- Input/typeahead: `../claude-code/src/hooks/useTextInput.ts`, `../claude-code/src/hooks/useTypeahead.tsx`, `../claude-code/src/components/BaseTextInput.tsx`, `../claude-code/src/components/PromptInput/*`
- Terminal input parser: `../claude-code/src/ink/parse-keypress.ts`, `../claude-code/src/ink/termio/tokenize.ts`
- Tools: `../claude-code/src/tools.ts`, `../claude-code/src/tools/*`
- Runtime: `../claude-code/src/query.ts`, `../claude-code/src/QueryEngine.ts`, `../claude-code/src/utils/messages.ts`, `../claude-code/src/services/tools/*`
- MCP / bridge / plugins / skills: `../claude-code/src/services/mcp/*`, `../claude-code/src/bridge/*`, `../claude-code/src/services/plugins/*`, `../claude-code/src/commands/skills/*`

Current `openclaude` baseline:

- Claude command entry surfaces currently present under `../claude-code/src/commands`: `102`
- OpenClaude surfaced slash commands currently exposed from `app-cli/src/main/java/com/openclaude/cli/service/CommandService.java`: `29`
- Claude tool directories currently present under `../claude-code/src/tools`: `42`
- Surfaced commands today: `/resume`, `/session`, `/rename`, `/login`, `/logout`, `/provider`, `/models`, `/config`, `/status`, `/context`, `/tools`, `/permissions`, `/usage`, `/stats`, `/copy`, `/cost`, `/clear`, `/compact`, `/plan`, `/rewind`, `/diff`, `/doctor`, `/tasks`, `/fast`, `/memory`, `/effort`, `/keybindings`, `/help`, `/exit`
- Real default tool runtime today: `bash`, `Glob`, `Grep`, `ExitPlanMode`, `Read`, `Edit`, `Write`, `WebFetch`, `TodoWrite`, `WebSearch`, `AskUserQuestion`, `EnterPlanMode`
- Real provider execution depth today: `OpenAI` and `Anthropic`
- Session scoping already landed:
  - store `workingDirectory` and `workspaceRoot`
  - normal startup creates a fresh session for the current directory/workspace
  - `--resume <id>` works
  - `--resume` without an id is current-directory/workspace aware

## V0-code Scope Lock

`v0-code` means: required before `openclaude` can be treated as a dependable default coding agent.

`openclaude` v0-code is the coding-agent cut of Claude parity, not the whole product surface.

Only these areas stay on the active `v0-code` path:

- current-directory session flow and instruction loading
- the core React/Ink REPL, prompt input, transcript, and markdown path
- the core coding toolchain from Claude's default preset
- real tool orchestration and permission semantics
- production-ready `OpenAI` and `Anthropic` provider paths
- test gates for every surfaced command/tool/provider

Anything outside that stays documented below, but it is explicitly not `v0-code` unless promoted back into scope.

`v0-code` blockers, in practice:

- session restore/scope/instructions that keep repo-local coding context sane
- stable REPL/input/transcript behavior for long coding sessions
- core coding tools: file read/write/edit, grep/glob, bash, web fetch/search, ask-user, todo/plan mode
- exact enough tool orchestration, permission semantics, and provider streaming/tool behavior
- test coverage that prevents half-wired coding features from being surfaced

### Command Parity [Tracked, Not V0-code Blocking]

Command parity stays tracked below, but it no longer gates `v0-code`. The active `v0-code` path is runtime, REPL/input, transcript/render, provider parity, and test gates.

Already surfaced:

- `/resume`
- `/session`
- `/login`
- `/logout`
- `/provider`
- `/models`
- `/config`
- `/context`
- `/tools`
- `/permissions`
- `/clear`
- `/compact`
- `/plan`
- `/rewind`
- `/diff`
- `/doctor`
- `/memory`
- `/effort`
- `/exit`

Useful but not blocking `v0-code` by themselves:

- `/rename`
- `/copy`
- `/cost`
- `/fast`
- `/keybindings`
- `/help`
- `/status`
- `/usage`
- `/stats`
- `/tasks`

Partially landed but not parity-complete:

- [ ] `/clear` (current implementation starts a fresh scoped session and swaps the active session id, but Claude's session-end hooks, cache clearing, worktree/session metadata rotation, and task cleanup semantics are still missing)
- [x] `/compact` (real compact parity slice is landed for v0: provider-backed manual compaction, AGENTS-aware compact prompts, compact-scoped hooks from `.openclaude/settings*.json`, typed post-compact file/plan-mode reinjection, preserved-tail projection, hidden per-session session-memory notes with Claude-aligned thresholds/cursor state, session-memory-first compaction, time-based microcompact, and reactive compact retry on prompt-too-long failures)
- [ ] `/plan` (current implementation enters session plan mode and can forward an inline planning prompt, but Claude's plan-file storage, `/plan open`, and full plan-mode permission/runtime semantics are still missing)
- [ ] `/rewind` (current implementation opens a checkpoint picker, truncates the session before the selected user turn, and restores that prompt into the input, but Claude's full code/file-history rewind and confirmation workflow are still missing)
- [ ] `/permissions` (current implementation now persists session allow/deny decisions in backend state, loads Claude-style `permissions.allow/deny/ask` rules from user/project/local/managed settings, exposes source-aware rules in `/permissions`, exposes a structured stdio permissions-editor snapshot plus add/remove/clear/retry-denials mutations, opens blank `/permissions` into a local Ink editor with tab/search/add/delete/retry flows, and keeps explicit `/permissions ...args` on the backend command path; the remaining gap is Claude's full local-JSX `PermissionRuleList` callback/runtime parity, especially workspace-directory editing and on-exit retry callback semantics)
- [ ] `/login` (current implementation opens the provider picker as the multi-provider auth entrypoint, but Claude's Anthropic-specific OAuth dialog and auth-refresh side effects are not ported)
- [ ] `/logout` (current implementation disconnects the active provider and clears stored provider credentials, but Claude's account-wide cache flush and exit-on-logout lifecycle are not ported)
- [ ] `/memory` (current implementation opens an AGENTS-based memory picker and launches the selected file in the configured editor, but Claude's richer `MemoryFileSelector` options, auto-memory/team-memory folders, and full memory notification parity are still missing)
- [ ] `/effort` (current implementation now distinguishes persisted effort from current-session effort, honors `CLAUDE_CODE_EFFORT_LEVEL` override semantics, keeps session-only `max` out of persisted settings, surfaces the current-session effort in the UI snapshot, and applies the effective value to OpenAI prompt payloads, but Claude's remaining account/model semantics and Anthropic-side parity are still missing)

### V0-code Critical Tools

Already landed:

- `BashTool`
- `GlobTool`
- `GrepTool`
- `ExitPlanModeV2Tool`
- `FileReadTool`
- `FileEditTool`
- `FileWriteTool`
- `TodoWriteTool`
- `EnterPlanModeTool`

Partially landed but not parity-complete:

- [x] `AskUserQuestionTool`* (single-select, preview-question layout, multi-select answer collection, preview annotations, preview-question notes input, and Claude's plan-interview/respond-to-Claude footer follow-up behavior now exist; accepted `v0-code` drift for per-question image attachments, see `DRIFT.md`)
- [x] `WebFetchTool` (URL fetch, redirect handling including Claude-style same-site `www` redirects, permission gating, preapproved-host rules, richer HTML-to-markdown conversion with tables/images/absolute links, provider-specific Anthropic preflight semantics with host caching, Claude `prompt.ts` description/secondary-prompt text, prompt-application, provider-specific small-fast secondary-model selection, and a 15-minute fetch cache now exist)
- [x] `WebSearchTool` (Claude `prompt.ts` description, source-link output contract, provider-native search path, native-only trajectory, and Claude-style `query_update` / `search_results_received` progress semantics now exist: Anthropic uses `web_search_20250305`, OpenAI uses native `web_search` with citation extraction and query-aware tool progress)

Kept out of v0 unless the coding loop proves blocked:

- [ ] `NotebookEditTool`
- [ ] `AgentTool`
- [ ] `TaskOutputTool`
- [ ] `TaskStopTool`
- [ ] `SkillTool`
- [ ] `ListMcpResourcesTool`
- [ ] `ReadMcpResourceTool`
- [ ] `ToolSearchTool`
- [ ] `BriefTool`

### V0-code Critical Runtime / Provider Work

- [x] Port Claude's multi-step tool orchestration loop instead of the current smaller Java loop (the v0 coding path now honors eight real tool-use turns instead of the older four-turn shortcut, final-answer retries no longer consume the same budget as real tool turns, early streamed tool execution runs through the same hook-aware executor as the normal loop, speculative streamed tools are discarded before completion, and cancellation/yield lifecycle is preserved through paired synthetic results)
- [x] Port streaming tool executor parity (early streamed tools now execute through the same hook-aware path as the normal tool loop for both concurrency-safe and serial tools, speculative unconfirmed tools are discarded before the turn completes, yielded lifecycle events now fire from tool-future completion before the provider stream returns, reactive-compact retries discard speculative streamed tools instead of replaying stale results, and submit-interrupt gating for blocking vs cancellable tools is enforced through the stdio prompt-run path)
- [x] Port backend tool pairing guarantees exactly (unexpected tool-runtime exceptions now emit a failed lifecycle update plus a paired failed `tool_result`; provider-bound prompt history now repairs missing `tool_result`s instead of dropping interrupted tool trajectories; streamed tools keep hook attachments bound to the real tool trajectory, emit `yielded` once consumed, cancellation reasons are preserved through synthetic tool results, and parallel bash failures now synthesize sibling cancellations instead of holding the turn open)
- [x] Port tool-progress / status lifecycle parity (typed hook progress is persisted and projected through stdio/UI, the streaming executor now tracks explicit `queued -> executing -> completed -> yielded` state, grouped tool rows see `yielded` only once results are consumed, and unresolved-post-hook tool status is preserved through the UI lookup/normalization path)
- [x] Port permission request types parity (typed permission decisions now support `allow|ask|deny|passthrough`, `updatedInput`, and interrupt-aware cancellation, and typed interaction payloads now persist through session storage and stdio snapshots for `bash`, `AskUserQuestion`, `EnterPlanMode`, `ExitPlanMode`, `Write`, `Edit`, `WebFetch`, and `WebSearch`)
- [x] Port permission persistence / deny rules / approval callbacks parity for v0-code runtime/tool execution (persisted allow/deny rules, wildcard matching, updated-input approvals, interrupt-aware denies, retry-denials, structured stdio editor snapshots, and the active Ink overlay flows are landed; the remaining `PermissionRuleList` local-JSX workspace-directory editor is tracked with non-blocking command/UI parity, not the v0-code tool loop)
- [x] Port read-before-mutate enforcement parity for `Edit` and `Write`
- [x] Port stale-file / stale-read guards for mutating tools
- [x] Port patch/apply workflow parity (Claude has no dedicated `Apply` tool; parity for the write surfaces is now covered by `Edit`/`Write` unified-patch display payloads, concise model-visible tool results, stale-read enforcement, non-concurrent mutation execution, and updated-input approval semantics across bash/edit/write)
- [x] Port bash/shell runtime semantics parity for v0-code (Claude-style read-only auto-allow behavior is enforced and tested, explicit persisted `ask` rules still prompt even for otherwise auto-allowed commands, interrupt-path bash executions cancel instead of degrading into generic failures, informational non-zero exits are handled for exploratory commands, wildcard permission rules work, and safer git/sed/gh/docker/path checks plus parallel-bash sibling cancellation are landed; broader background/computer-use lifecycle work stays tracked outside the v0-code tool loop)
- [x] Port Claude's runtime message model instead of normalizing only in the frontend (the stdio snapshot now carries explicit `assistantMessageId`, `siblingToolIds`, `toolGroupKey`, `toolRenderClass`, `attachmentKind`, and typed hook-progress fields, and provider-visible history preserves tool-scoped hook attachments on the real tool trajectory instead of degrading into synthetic missing-result placeholders for the v0 coding path)
- [x] Port `normalizeMessages` parity fully (lookup-backed sibling ordering, backend-metadata-driven grouping, typed hook-progress ordering, unresolved-post-hook running state, suppression of tool-scoped hook rows from standalone rendering, grouped-tool family merging, read/search collapse, and clean command/result extraction are now landed for the v0 coding path)
- [x] Port `reorderMessagesInUI` parity (the pre-normalization ordering pass now re-emits `tool_use -> hook/tool lifecycle rows -> tool_result` at the tool-use position instead of trusting flat session order for the v0 coding path)
- [x] Port `buildMessageLookups` parity fully (assistant-message mapping, ordered sibling lookup, backend-metadata-first tool ownership, resolution/error tracking, typed hook-progress lookup, unresolved-hook helpers, and group-key indexing are landed for the v0 coding path)
- [x] Port `groupToolUses` parity fully
- [x] Port `collapseReadSearchGroups` parity fully
- [x] Port full grouped/collapsed tool row semantics
- [x] Port full markdown parity, especially tables and richer formatting/highlighting
- [x] Port full `BaseTextInput` parity (the editor core now has Claude-style kill-ring/yank-yank-pop, wrapped-line home/end behavior, logical-line up/down fallback, prompt|bash display plumbing, a real `useTextInput -> BaseTextInput -> TextInput` layer, absolute wrapped-line counters, custom placeholder rendering, bracketed-paste state reporting, inline ghost text, terminal-focus-aware cursor gating, placeholder-hiding, child rendering, and direct hook/component coverage)
- [x] Port full `PromptInput` state machine parity (prompt ownership is now split through `PromptInputPanel` instead of the old monolithic prompt box, with one-shot bash mode, project-scoped history preload, prompt-local `ctrl+r` search, autocomplete-owned accept/dismiss/execute, stash/history preservation, command-argument acceptance for `/resume` and `/models`, and the remaining v0 prompt shell state moved out of the inline `app.tsx` render path)
- [x] Port broader typeahead parity beyond slash commands and local file suggestions (inline ghost text, command accept/dismiss behavior, static command argument hints, cursor-aware `@` token extraction, quoted `@"..."` replacement, direct path-like routing, common-prefix `Tab` completion, workspace-scoped `/resume` argument suggestions, and provider-scoped `/models` argument suggestions are now landed for the v0 coding path)
- [x] Port keybinding-layer parity instead of hardcoding shortcuts in `ui-ink/src/app.tsx` (prompt-local key resolution now lives in `ui-ink/src/prompt/keybindings.ts`, and footer help is derived from the same action layer)
- [x] Port Claude `REPL.tsx` state/layout more directly instead of keeping a compressed single-app shell (the prompt shell now renders through dedicated `ReplShell`, `PromptInputPanel`, and `StatusLine` components instead of keeping prompt/layout ownership inline in `app.tsx`)
- [x] Port status-line parity
- [x] Port exit/interrupted flow parity
- [x] Harden OpenAI request/response parity further against Claude-style tool and stream edge cases
- [x] Port Anthropic streaming parity
- [x] Port Anthropic tool execution parity beyond the current simplified execution path
- [x] Add provider readiness gating so incomplete providers cannot appear production-ready
- [x] Every surfaced slash command has a backend/unit test and a TUI happy-path test
- [x] Every surfaced tool has runtime tests, provider contract tests, and TUI tests where applicable
- [x] Every shipped provider has auth, non-streaming, streaming, and tool round-trip tests
- [x] Resume/session scope has regression coverage for cross-directory isolation
- [x] Prompt run lifecycle has regression coverage for stale events, stale permission overlays, and restored-session poison turns
- [x] No command/provider/tool should be exposed in the UI as available unless its tests are passing and its runtime exists

## 1. Bootstrap, Session Scope, and Instructions [V0-code]

Reference:

- `../claude-code/src/replLauncher.tsx`
- `../claude-code/src/commands/resume/*`
- `../claude-code/src/commands/session/*`
- `../claude-code/src/utils/sessionRestore.ts`
- `../claude-code/src/utils/claudemd.ts`
- `../claude-code/src/utils/hooks.ts`

Checklist:

- [x] Store session `workingDirectory` and `workspaceRoot`
- [x] Default startup creates a new session for the current directory/workspace
- [x] `--resume <id>` resumes an explicit session
- [x] `--resume` without an id scopes results to the current directory/workspace first
- [x] Port Claude-style resume picker/session browser UX instead of only basic resume behavior
- [ ] Port session rename parity
- [ ] Port rewind parity
- [x] Port compact parity
- [ ] Port session preview parity
- [ ] Port session recovery / cross-project resume parity
- [x] Add instruction discovery/runtime parity using `AGENTS.md` instead of `CLAUDE.md`
- [x] Add nested instruction loading parity for subdirectories
- [x] Add `AGENTS.md`, `.openclaude/AGENTS.md`, `AGENTS.local.md`, and `.openclaude/rules/*.md` discovery parity for the current directory walk
- [x] Add managed/user `AGENTS.md` instruction layers
- [ ] Tighten `@include` parsing to Claude's markdown-token semantics instead of the current fenced-block parser
- [ ] Add additional-directory instruction loading parity (`--add-dir` equivalent behavior)
- [ ] Add instruction-loaded hook lifecycle parity
- [x] Add user/global instruction layers for `AGENTS.md`-based memory/instructions if we decide to mirror Claude's multi-layer memory system

## 2. CLI / TUI Shell and Startup [V0-code]

Reference:

- `../claude-code/src/entrypoints/cli.tsx`
- `../claude-code/src/replLauncher.tsx`
- `../claude-code/src/screens/REPL.tsx`
- `../claude-code/src/components/LogoV2/*`
- `../claude-code/src/components/StatusLine.tsx`

Checklist:

- [x] React/Ink frontend over stdio backend
- [x] Startup header with banner and feed
- [x] Port Claude `REPL.tsx` state/layout more directly instead of keeping a compressed single-app shell
- [ ] Port full `LogoV2` startup/feed behavior instead of the current approximation
- [x] Port status-line parity
- [x] Port exit/interrupted flow parity
- [ ] Port trust / warning / managed-settings / threshold / rate-limit dialogs
- [ ] Port transcript selector / message action / search flows
- [ ] Port background task navigation panes instead of the current simplified task surface

## 3. Prompt Input, Typeahead, and Terminal Input [V0-code]

Reference:

- `../claude-code/src/hooks/useTextInput.ts`
- `../claude-code/src/hooks/useTypeahead.tsx`
- `../claude-code/src/components/BaseTextInput.tsx`
- `../claude-code/src/components/PromptInput/*`
- `../claude-code/src/components/VimTextInput.tsx`
- `../claude-code/src/components/HistorySearchDialog.tsx`
- `../claude-code/src/ink/parse-keypress.ts`
- `../claude-code/src/ink/termio/tokenize.ts`

Checklist:

- [x] Port the core terminal input parser foundation instead of custom raw key heuristics
- [x] Port bracketed-paste handling foundation
- [x] Port full `BaseTextInput` parity (the editor core now has Claude-style kill-ring/yank-yank-pop, wrapped-line home/end behavior, logical-line up/down fallback, prompt|bash display plumbing, a real `useTextInput -> BaseTextInput -> TextInput` layer, absolute wrapped-line counters, custom placeholder rendering, bracketed-paste state reporting, inline ghost text, terminal-focus-aware cursor gating, placeholder-hiding, child rendering, and direct hook/component coverage)
- [x] Port full `PromptInput` state machine parity (prompt ownership is now split through `PromptInputPanel` instead of the old monolithic prompt box, with one-shot bash mode, project-scoped history preload, prompt-local `ctrl+r` search, autocomplete-owned accept/dismiss/execute, stash/history preservation, command-argument acceptance for `/resume` and `/models`, and the remaining v0 prompt shell state moved out of the inline `app.tsx` render path)
- [ ] Port full history-search parity (project-scoped prompt history with current-session-first ordering, prompt-local `ctrl+r` search, bash-only arrow-history filtering, and fresh-session history restore are now landed in `ui-ink`, but Claude's broader lazy history reader, pasted-content restore, and richer current-project/mode-aware restore path are still missing)
- [x] Port bash-mode parity for `!` (leading `!` now flips the prompt into bash mode, strips the mode character from the composer, disables prompt suggestions, preserves mode through stash/history recall, resets to prompt after one-shot bash submit, restores an empty bash draft when arrow-history exits, and renders a dedicated prompt-mode indicator)
- [ ] Port background-mode parity for `&`
- [ ] Port side-question `/btw` prompt mode parity
- [ ] Port vim-mode runtime parity
- [ ] Port image paste / clipboard attachment parity
- [ ] Port queued-command banners / prompt issue flags / prompt notifications
- [ ] Port voice indicator parity
- [x] Port broader typeahead parity beyond slash commands and local file suggestions (inline ghost text, command accept/dismiss behavior, static command argument hints, cursor-aware `@` token extraction, quoted `@"..."` replacement, direct path-like routing, common-prefix `Tab` completion, workspace-scoped `/resume` argument suggestions, and provider-scoped `/models` argument suggestions are now landed for the v0 coding path)
- [ ] Port contextual suggestion providers from Claude's prompt suggestion services
- [x] Port keybinding-layer parity instead of hardcoding shortcuts in `ui-ink/src/app.tsx` (prompt-local key resolution now lives in `ui-ink/src/prompt/keybindings.ts`, and footer help is derived from the same action layer)

## 4. Transcript, Messages, Markdown, and Renderers [V0-code]

Reference:

- `../claude-code/src/utils/messages.ts`
- `../claude-code/src/components/Messages.tsx`
- `../claude-code/src/components/MessageRow.tsx`
- `../claude-code/src/components/Message.tsx`
- `../claude-code/src/components/messages/*`
- `../claude-code/src/components/Markdown.tsx`
- `../claude-code/src/components/MarkdownTable.tsx`
- `../claude-code/src/components/HighlightedCode.tsx`

Checklist:

- [x] Split UI pipeline into `Messages -> MessageRow -> Message`
- [x] Add frontend lookup-based normalization foundation
- [x] Port Claude's runtime message model instead of normalizing only in the frontend (the stdio snapshot now carries explicit `assistantMessageId`, `siblingToolIds`, `toolGroupKey`, `toolRenderClass`, `attachmentKind`, and typed hook-progress fields, and provider-visible history preserves tool-scoped hook attachments on the real tool trajectory instead of degrading into synthetic missing-result placeholders for the v0 coding path)
- [x] Port `normalizeMessages` parity fully (lookup-backed sibling ordering, backend-metadata-driven grouping, typed hook-progress ordering, unresolved-post-hook running state, suppression of tool-scoped hook rows from standalone rendering, grouped-tool family merging, read/search collapse, and clean command/result extraction are now landed for the v0 coding path)
- [x] Port `reorderMessagesInUI` parity (the pre-normalization ordering pass now re-emits `tool_use -> hook/tool lifecycle rows -> tool_result` at the tool-use position instead of trusting flat session order for the v0 coding path)
- [x] Port `buildMessageLookups` parity fully (assistant-message mapping, ordered sibling lookup, backend-metadata-first tool ownership, resolution/error tracking, typed hook-progress lookup, unresolved-hook helpers, and group-key indexing are landed for the v0 coding path)
- [x] Port `groupToolUses` parity fully
- [x] Port `collapseReadSearchGroups` parity fully
- [x] Port full grouped/collapsed tool row semantics
- [x] Port full markdown parity, especially tables and richer formatting/highlighting
- [ ] Port Claude's specialized message renderers:
  - [ ] API error rows
  - [ ] plan approval rows
  - [ ] rate-limit rows
  - [ ] task assignment / agent notification rows
  - [ ] bash input / output rows
  - [ ] image messages
  - [ ] tool result variants
  - [ ] command output rows
  - [ ] shutdown / interruption rows
- [ ] Port transcript virtualization/static-row logic

## 5. Slash Commands

Reference:

- `../claude-code/src/commands/*`

Already surfaced in `openclaude`:

- `/resume`
- `/session`
- `/rename`
- `/provider`
- `/models`
- `/config`
- `/context`
- `/tools`
- `/usage` (surfaced, but still not Claude-parity)
- `/stats` (surfaced, but still not Claude-parity)
- `/copy`
- `/cost`
- `/plan` (surfaced, but still not Claude-parity)
- `/rewind` (surfaced, but still not Claude-parity)
- `/diff`
- `/doctor`
- `/keybindings`
- `/help`
- `/exit`

Still missing or not Claude-parity yet [V0 active first, post-v0 below]:

### Session, provider, and auth required for coding-agent use [V0-code]

- [x] `/login` (surfaced, but still not Claude-parity)
- [x] `/logout` (surfaced, but still not Claude-parity)
- [x] `/status`

### Workflow and runtime control required for coding-agent use [V0-code]

- [x] `/clear` (surfaced, but still not Claude-parity)
- [x] `/compact` (surfaced with the v0 compact/runtime parity slice landed)
- [x] `/rewind` (surfaced, but still not Claude-parity)
- [x] `/plan` (surfaced, but still not Claude-parity)
- [x] `/permissions` (surfaced, but still not Claude-parity)
- [x] `/memory` (surfaced, but still not Claude-parity)
- [x] `/fast`
- [x] `/effort` (surfaced, but still not Claude-parity)

### Useful but not blocking for coding-agent v0

- [x] `/tasks` (surfaced, but still not Claude-parity)
- [x] `/usage` (surfaced, but still not Claude-parity)
- [x] `/stats` (surfaced, but still not Claude-parity)
- [ ] `/extra-usage`
- [ ] `/passes`
- [ ] `/rate-limit-options`
- [ ] `/hooks`
- [ ] `/output-style`
- [ ] `/theme`
- [ ] `/vim`
- [ ] `/sandbox-toggle`
- [ ] `/color`

### Workspace, git, and review [Post-v0 unless promoted]

- [ ] `/add-dir`
- [ ] `/files`
- [ ] `/branch`
- [ ] `/commit`
- [ ] `/commit-push-pr`
- [ ] `/review`
- [ ] `/security-review`
- [ ] `/tag`
- [ ] `/pr_comments`

### Integrations and platform [Post-v0 unless promoted]

- [ ] `/mcp`
- [ ] `/plugin`
- [ ] `/skills`
- [ ] `/ide`
- [ ] `/desktop`
- [ ] `/mobile`
- [ ] `/chrome`
- [ ] `/voice`
- [ ] `/remote-env`
- [ ] `/remote-setup`
- [ ] `/bridge`

### Onboarding, install, and product flows [Post-v0 unless promoted]

- [ ] `/init`
- [ ] `/install`
- [ ] `/install-github-app`
- [ ] `/install-slack-app`
- [ ] `/upgrade`
- [ ] `/release-notes`
- [ ] `/privacy-settings`
- [ ] `/terminalSetup`
- [ ] `/onboarding`

### Side workflows and utility flows [Post-v0 unless promoted]

- [ ] `/btw`
- [ ] `/brief`
- [ ] `/feedback`
- [ ] `/export`
- [ ] `/share`
- [ ] `/stickers`
- [ ] `/x402`

### Remaining Claude command surfaces not yet wired [Post-v0 unless promoted]

- [ ] `/advisor`
- [ ] `/agents`
- [ ] `/ctx_viz`
- [ ] `/env`
- [ ] `/heapdump`
- [ ] `/insights`
- [ ] `/issue`
- [ ] `/model` full parity beyond aliasing `/models`
- [ ] `/statusline`
- [ ] `/summary`
- [ ] `/thinkback`
- [ ] `/thinkback-play`
- [ ] `/ultraplan`
- [ ] `/version`

### Internal / debug / maintenance command families still absent [Post-v0]

- [ ] `ant-trace`
- [ ] `autofix-pr`
- [ ] `backfill-sessions`
- [ ] `break-cache`
- [ ] `bridge-kick`
- [ ] `bughunter`
- [ ] `createMovedToPluginCommand`
- [ ] `debug-tool-call`
- [ ] `good-claude`
- [ ] `init-verifiers`
- [ ] `mock-limits`
- [ ] `oauth-refresh`
- [ ] `perf-issue`
- [ ] `reload-plugins`
- [ ] `reset-limits`
- [ ] `teleport`

## 6. Tools: Default Preset / Core Base Tools [V0-code]

Reference:

- `../claude-code/src/tools.ts`
- `../claude-code/src/constants/tools.ts`
- `../claude-code/src/tools/*`

Already landed in `openclaude`:

- `BashTool`
- `GlobTool`
- `GrepTool`
- `ExitPlanModeV2Tool`
- `FileReadTool`
- `FileEditTool`
- `FileWriteTool`
- `TodoWriteTool`
- `EnterPlanModeTool`

Remaining base/default tools from Claude's default tool preset:

- [x] `AskUserQuestionTool`* [V0-code, accepted drift: preview + multi-select + preview notes + plan-interview/respond-to-Claude footer flow landed; per-question image attachments deferred, see `DRIFT.md`]
- [x] `WebFetchTool` [V0-code]
- [x] `WebSearchTool` [V0-code]
- [ ] `NotebookEditTool` [Post-v0 unless promoted]
- [ ] `AgentTool` [Post-v0 unless promoted]
- [ ] `TaskOutputTool` [Post-v0 unless promoted]
- [ ] `TaskStopTool` [Post-v0 unless promoted]
- [ ] `SkillTool` [Post-v0 unless promoted]
- [ ] `ListMcpResourcesTool` [Post-v0 unless promoted]
- [ ] `ReadMcpResourceTool` [Post-v0 unless promoted]
- [ ] `ToolSearchTool` [Post-v0 unless promoted]
- [ ] `BriefTool` [Post-v0 unless promoted]

## 7. Tools: Conditional / Feature-Gated / Ant-Only Tools [Post-v0 unless promoted]

Reference:

- `../claude-code/src/tools.ts`
- `../claude-code/src/tools/*`

Checklist:

- [ ] `ConfigTool`
- [ ] `TungstenTool`
- [ ] `WebBrowserTool`
- [ ] `TaskCreateTool`
- [ ] `TaskGetTool`
- [ ] `TaskUpdateTool`
- [ ] `TaskListTool`
- [ ] `CtxInspectTool`
- [ ] `TerminalCaptureTool`
- [ ] `LSPTool`
- [ ] `EnterWorktreeTool`
- [ ] `ExitWorktreeTool`
- [ ] `SendMessageTool`
- [ ] `TeamCreateTool`
- [ ] `TeamDeleteTool`
- [ ] `VerifyPlanExecutionTool`
- [ ] `REPLTool`
- [ ] `WorkflowTool`
- [ ] `SleepTool`
- [ ] `CronCreateTool`
- [ ] `CronDeleteTool`
- [ ] `CronListTool`
- [ ] `RemoteTriggerTool`
- [ ] `MonitorTool`
- [ ] `SendUserFileTool`
- [ ] `PushNotificationTool`
- [ ] `SubscribePRTool`
- [ ] `PowerShellTool`
- [ ] `SnipTool`
- [ ] `TestingPermissionTool`

## 7a. Tools: Support / Infrastructure Tool Surfaces [Post-v0 unless promoted]

Reference:

- `../claude-code/src/tools/MCPTool/*`
- `../claude-code/src/tools/McpAuthTool/*`
- `../claude-code/src/tools/SyntheticOutputTool/*`

Checklist:

- [ ] `MCPTool`
- [ ] `McpAuthTool`
- [ ] `SyntheticOutputTool`

## 8. Tool Orchestration, Permissions, and Runtime Semantics [V0-code]

Reference:

- `../claude-code/src/query.ts`
- `../claude-code/src/QueryEngine.ts`
- `../claude-code/src/services/tools/toolExecution.ts`
- `../claude-code/src/services/tools/toolHooks.ts`
- `../claude-code/src/services/tools/toolOrchestration.ts`
- `../claude-code/src/services/tools/StreamingToolExecutor.ts`
- `../claude-code/src/utils/permissions/*`
- `../claude-code/src/components/permissions/*`

Checklist:

- [x] Port Claude's multi-step tool orchestration loop instead of the current smaller Java loop (the v0 coding path now honors eight real tool-use turns instead of the older four-turn shortcut, final-answer retries no longer consume the same budget as real tool turns, early streamed tool execution runs through the same hook-aware executor as the normal loop, speculative streamed tools are discarded before completion, and cancellation/yield lifecycle is preserved through paired synthetic results)
- [x] Port streaming tool executor parity (early streamed tools now execute through the same hook-aware path as the normal tool loop for both concurrency-safe and serial tools, speculative unconfirmed tools are discarded before the turn completes, yielded lifecycle events fire before provider return, and retry/discard paths no longer replay stale speculative streamed results)
- [x] Port backend tool pairing guarantees exactly (unexpected tool-runtime exceptions now emit a failed lifecycle update plus a paired failed `tool_result`; provider-bound prompt history repairs missing `tool_result`s instead of dropping interrupted tool trajectories; early streamed tools keep hook attachments bound to the real tool trajectory, preserve cancellation reasons, and parallel bash failures synthesize sibling cancellations instead of holding the turn open)
- [x] Port tool-progress / status lifecycle parity (typed hook progress is persisted and projected through stdio/UI, the streaming executor now tracks explicit `queued -> executing -> completed -> yielded` state, and yielded/completion ordering is stable across the backend and UI message pipeline)
- [ ] Port tool-owned renderers / tool-specific UI data model parity
- [x] Port permission request types parity (permission interaction type/payload now persists through session storage and stdio snapshots for the v0-code tool set, including typed updated-input approval paths and interrupt-aware denies)
- [x] Port permission persistence / deny rules / approval callbacks parity for v0-code runtime/tool execution (source-aware loading, wildcard matching, command management, structured stdio editor data, retry-denials, and the active Ink permission flows are landed; the remaining workspace-directory editor semantics are tracked under non-blocking command/UI parity)
- [x] Port read-before-mutate enforcement parity for `Edit` and `Write`
- [x] Port stale-file / stale-read guards for mutating tools
- [x] Port patch/apply workflow parity
- [x] Port bash/computer-use / shell runtime semantics parity for v0-code
- [ ] Port synthetic output / task output semantics parity
- [ ] Stop exposing any tool as available until the actual runtime semantics and tests exist

## 9. Providers, Auth, and Models

Reference:

- `../claude-code/src/services/api/*`
- `../claude-code/src/services/oauth/*`
- `../claude-code/src/commands/model/*`
- `../claude-code/src/commands/login/*`
- `../claude-code/src/commands/logout/*`

Checklist:

- [x] OpenAI API-key execution
- [x] OpenAI browser auth over ChatGPT/Codex backend
- [x] Harden OpenAI request/response parity further against Claude-style tool and stream edge cases [V0-code]
- [x] Port Anthropic streaming parity [V0-code]
- [x] Port Anthropic tool execution parity beyond the current simplified execution path [V0-code]
- [x] Add provider readiness gating so incomplete providers cannot appear production-ready [V0-code]
- [ ] Port Claude model/account/session UI and state flows more directly
- [ ] Implement real Gemini execution client/runtime [Post-v0 unless promoted]
- [ ] Implement real Mistral execution client/runtime [Post-v0 unless promoted]
- [ ] Implement real Kimi execution client/runtime [Post-v0 unless promoted]
- [ ] Implement real Bedrock execution client/runtime [Post-v0 unless promoted]
- [ ] Implement Bedrock open-source model routing if we keep that in scope [Post-v0 unless promoted]

## 10. MCP, Bridge, Plugins, Skills, and Remote Runtime [Post-v0 unless promoted]

Reference:

- `../claude-code/src/services/mcp/*`
- `../claude-code/src/bridge/*`
- `../claude-code/src/services/plugins/*`
- `../claude-code/src/commands/mcp/*`
- `../claude-code/src/commands/plugin/*`
- `../claude-code/src/commands/skills/*`

Checklist:

- [ ] Create active MCP runtime modules, not just command placeholders
- [ ] Port MCP connection manager parity
- [ ] Port MCP auth / elicitation / reconnect / permissions parity
- [ ] Port MCP UI/server menus/dialog parity
- [ ] Create active bridge / daemon / remote runtime modules
- [ ] Port bridge permission callbacks and remote execution flows
- [ ] Port plugin runtime parity
- [ ] Port marketplace browsing / installation / trust flows
- [ ] Port skills runtime parity
- [ ] Port skill discovery / invocation / management parity

## 11. Tasks, Background Sessions, Memory, Hooks, and Compaction

Reference:

- `../claude-code/src/commands/tasks/*`
- `../claude-code/src/services/SessionMemory/*`
- `../claude-code/src/services/compact/*`
- `../claude-code/src/utils/background/*`
- `../claude-code/src/utils/hooks/*`

Checklist:

- [x] Durable session plan mode state
- [x] Durable session todo state
- [ ] Port real task engine parity
- [x] Port session memory extraction / sync parity [V0-code]
- [x] Port compact / micro-compact / summarization parity [V0-code]
- [ ] Port background-session lifecycle parity [Post-v0 unless promoted]
- [ ] Port task assignment / task output / task stop parity [Post-v0 unless promoted]
- [ ] Port hooks runtime parity [Post-v0 unless promoted]
- [ ] Port hooks config manager parity [Post-v0 unless promoted]
- [ ] Port file-history / worktree restore / background restore parity [Post-v0 unless promoted]

## 12. Diagnostics, Cost, Context, Usage, and Analytics

Reference:

- `../claude-code/src/commands/cost/*`
- `../claude-code/src/commands/context/*`
- `../claude-code/src/commands/doctor/*`
- `../claude-code/src/commands/usage/*`
- `../claude-code/src/services/api/usage.ts`
- `../claude-code/src/services/analytics/*`
- `../claude-code/src/services/policyLimits/*`

Checklist:

- [ ] Port real cost accounting parity
- [x] Port real context accounting / visualization parity [V0-code]
- [ ] Port real usage/stats parity
- [x] Port rate-limit / policy-limit handling parity [V0-code]
- [x] Port doctor diagnostics parity beyond local checks [V0-code]
- [ ] Port telemetry / analytics-grade diagnostics parity where applicable
- [ ] Stop treating simplified local panels as parity-complete features

## 13. Quality Gates and Test Matrix [V0-code]

This section is required before marking any parity group complete.

Checklist:

- [x] Every surfaced slash command has a backend/unit test and a TUI happy-path test
- [x] Every surfaced tool has:
  - [x] runtime unit tests
  - [x] provider contract tests
  - [x] TUI permission / render tests where applicable
- [x] Every shipped provider has:
  - [x] auth tests
  - [x] non-streaming prompt tests
  - [x] streaming prompt tests
  - [x] tool-call round-trip tests
- [x] Resume/session scope has regression coverage for cross-directory isolation
- [x] Prompt run lifecycle has regression coverage for stale events, stale permission overlays, and restored-session poison turns
- [x] Paste/input layer has parity regression coverage against Claude's parser behavior
- [x] No command/provider/tool should be exposed in the UI as available unless its tests are passing and its runtime exists

## 14. Notes for Ongoing Use

- Update this file in the same change that lands parity work.
- Check items off only when the code and tests are both in place.
- If a partial implementation exists, leave the item unchecked and describe the remaining gap in `IMPLEMENTATION.md` or `DRIFT.md`.
