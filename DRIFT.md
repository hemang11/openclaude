# Drift Log

This file records deliberate deviations from the TypeScript reference implementation.

## Active Drift

### Config home

- Decision: use `~/.openclaude` instead of `~/.claude`
- Reason: avoid overwriting or corrupting a real Claude Code installation while the Java port is incomplete
- Status: active

### Runtime and UI stack

- Decision: use a split runtime: Java backend plus React/Ink terminal frontend
- Reason: the project target for the provider/runtime core is Java, but the best path to Claude TUI fidelity is to keep the frontend in the same React/Ink family as the reference implementation
- Status: active

### Process boundary

- Decision: React/Ink and Java communicate over newline-delimited JSON on stdio
- Reason: this is the simplest CLI-friendly IPC boundary and avoids binding a localhost port for local CLI use
- Status: active

### Shared protocol contract

- Decision: keep the stdio contract in `types/stdio`
- Reason: the frontend/backend boundary needs an explicit, dedicated home instead of being implied by one side's implementation
- Status: active

### Provider core

- Decision: define a provider-neutral SPI up front instead of retaining Anthropic-shaped request types as the center of the architecture
- Reason: OpenAI, Gemini, Mistral, Kimi, and Bedrock support is a core requirement, not an add-on
- Status: active

### Bedrock auth modeling

- Decision: expose an explicit `AWS_CREDENTIALS` auth mode for Bedrock
- Reason: representing Bedrock as plain API-key auth would be misleading and would hide a real transport/auth difference
- Status: active

### Browser auth rollout

- Decision: browser auth is planned first for OpenAI family only
- Reason: this matches the current project scope; other providers will start with API-key or cloud-credential integration until their browser flows are defined
- Status: active

### OpenAI browser transport

- Decision: OpenAI browser-auth sessions use a first-party OAuth flow but execute prompts against `https://chatgpt.com/backend-api/codex/responses` instead of `https://api.openai.com/v1/responses`
- Reason: ChatGPT/Codex account tokens are account-entitlement credentials, not public API-key credentials; the Codex backend matches the browser-auth account model
- Status: active

### OpenAI Codex payload shape

- Decision: browser-auth OpenAI requests use a Codex-specific payload with a required `instructions` field and conversation turns in `input`, instead of reusing the public Responses API body shape verbatim
- Reason: the ChatGPT/Codex backend rejects the plain Responses payload and expects system instructions to be carried separately
- Status: active

### OpenAI browser callback pinning

- Decision: OpenAI browser auth uses a fixed redirect URI of `http://localhost:1455/auth/callback`
- Reason: the Codex-compatible OAuth client flow appears to expect a pinned localhost callback, so a random callback port would be less reliable
- Status: active

### OpenAI model catalog ordering

- Decision: OpenAI lists Codex-compatible models first, then public API-oriented OpenAI models
- Reason: `/models` is not yet auth-aware; browser-auth users need a working default model instead of defaulting to an API-key-oriented model id
- Status: active

### Slash command delivery order

- Decision: bootstrap reusable command handlers before the full REPL slash-command shell exists
- Reason: `/provider` and `/models` need durable domain logic independent of the terminal UI layer
- Status: active

### Session persistence shape

- Decision: store a single active session id in `state.json` and persist each transcript as `sessions/<session-id>.json`
- Reason: this keeps the Java port simple while still giving us a `QueryEngine`-style durable transcript layer
- Status: active

### Transcript normalization

- Decision: provider-visible history is currently text-first and only forwards `system`, `user`, and `assistant` messages into provider requests
- Reason: tool results, attachments, and richer content blocks need a proper cross-provider normalization layer that does not exist yet
- Status: active

### Model catalog authority

- Decision: current model lists are curated bootstrap catalogs, not yet a fully authoritative discovery system
- Reason: provider model availability is unstable and provider APIs for model discovery differ; the Java port still needs a proper verification or discovery layer
- Status: active

### Command registry shape

- Decision: the stdio command contract currently exposes an explicit `execution` mode (`overlay`, `frontend`, `backend`) instead of mirroring Claude Code's full `prompt` / `local` / `local-jsx` command type system end to end
- Reason: this is enough to drive the current Ink client cleanly while the Java backend is still missing prompt-style command expansion and the richer command metadata used by Claude's full command runtime
- Status: active

### Context visualization fidelity

- Decision: `/context` in the Ink client is currently estimated from persisted visible transcript messages, not from Claude Code's transformed context accounting pipeline
- Reason: the Java port does not yet have Claude's full context-collapse, attachment, hidden-meta-message, and provider-token-accounting machinery
- Status: active

### Keybindings customization

- Decision: `/keybindings` currently opens or creates `~/.openclaude/keybindings.json`, but the customization file is not yet loaded back into the Ink keybinding resolver
- Reason: the first parity slice focused on command discovery and runtime shortcuts before implementing a full configurable keybinding system
- Status: active

### Prompt input engine

- Decision: the Ink client now uses a local custom editor for prompt composition, but it is still a reduced editor model rather than a full port of Claude Code's `useTextInput` / `TextInput` stack
- Reason: this closes the biggest parity gap around multiline entry and cursor movement, but it still does not cover Claude's kill ring, yank-pop, ghost text, paste normalization, history search, or keybinding-context integration
- Status: active

### Transcript normalization and tool rendering

- Decision: the Ink client now has a dedicated normalization layer plus a `Messages -> MessageRow -> Message` render pipeline, but it still derives `tool_use` / `tool_result` blocks from simplified backend session messages instead of a true block-level history graph
- Reason: this lands the architectural split Claude uses on the render side, and the Ink layer now has lookup-backed sibling tool ordering plus active collapsed read/search projection, but the Java backend still lacks raw multi-block history, hook-rich block relationships, and the full Claude renderer family
- Status: active

### Command and prompt overlays

- Decision: slash commands and trailing `@` file paths now use an inline openclaude-specific overlay, but `/provider` / `/models` still fall back to local picker dialogs instead of Claude Code's full prompt-overlay/typeahead system
- Reason: this lands the local inline suggestion model, but MCP/Slack/bash-history suggestions, history-search overlays, richer mention resolution, and configurable keybinding priority still need the rest of Claude's prompt-input stack to be ported
- Status: active

### Transcript row pipeline

- Decision: the Ink transcript now has grouped tool rows, lookup-backed sibling ordering, and active collapsed read/search rows, but it still does not mirror Claude Code's full lookup-driven pipeline (`buildMessageLookups` -> grouping -> collapse), virtualization, or row-staticness heuristics
- Reason: this slice landed ordered sibling grouping and active collapse projection, but OpenClaude still lacks Claude's broader hook/progress lookups, static-row decisions, and the full collapsed-group renderer contract
- Status: active

### Lookup layer scope

- Decision: OpenClaude now has an expanded `buildMessageLookups` layer for assistant-message mapping, ordered sibling tool lookup, resolution/error state, and grouped/collapsed tool projection, but it still stops short of Claude's broader hook/progress/static-row lookups
- Reason: the immediate need was to port sibling ordering and active collapsed-group projection without first porting Claude's entire block/history graph; broader lookup consumers remain deferred
- Status: active

### Tool permission model

- Decision: the first real permission-gated tool slice currently applies only to the local bash runtime over stdio, with one-shot `allow` / `deny` decisions and a single interactive picker in Ink
- Reason: this was the minimum viable way to replace the earlier fake/non-interactive tool story with a real request/response approval path before porting Claude Code's richer permission modes, persistence, remember-this-decision flows, and non-stdio surfaces
- Status: active

### Tool-use prompting depth

- Decision: provider-visible tool usage is currently reinforced by one default OpenClaude system prompt injected in `QueryEngine`, not by Claude Code's much larger tool/runtime instruction stack
- Reason: the immediate production issue was that OpenAI/Codex could answer as a plain chat assistant even though local tools were available; the lightweight system prompt fixes that gap without waiting for full Claude prompt parity
- Status: active

### Stateful Claude tools beyond permissions

- Decision: `AskUserQuestion` now rides the same permission/interaction channel over stdio and Ink with preview selection, multi-select answers, preview-question notes, and Claude's `Chat about this` / `Skip interview and plan immediately` footer feedback branches, but it still stops short of Claude's image-attachment path
- Reason: the critical v0 parity cut is now the structured question/answer round-trip plus preview notes and plan-interview footer flows; pasted-image attachments still need a deeper cross-provider image-content path before they can be called exact
- Status: active

### AskUserQuestion image attachments

- Decision: treat `AskUserQuestionTool` as closed for `v0-code` while leaving Claude's per-question image-attachment path as accepted drift
- Reason: the coding-agent cut is satisfied by the structured question/answer flow, preview modes, notes, multi-select, and plan-interview footer behavior; image attachments depend on the broader image-paste and permission-response attachment pipeline that is still separate work
- Status: active

### File mutation pre-read enforcement

- Decision: `Read`, `Edit`, and `Write` now share a real session-backed file snapshot layer with Claude-style read-before-mutate and stale-file guards, but the broader file-edit stack is still shallower than Claude
- Reason: the v0-critical safety semantics are now landed, while Claude's fuller implementation still includes quote/style normalization, patch generation, encoding and line-ending preservation, notebook-specific routing, file-history backups, and more nuanced file-state handling than OpenClaude currently has
- Status: active

### Todo and plan-mode scope

- Decision: `TodoWrite`, `EnterPlanMode`, and `ExitPlanMode` are now real tools with durable session effects, but they currently stop at session-scoped todo/plan-mode state and do not yet include Claude's full task engine, plan-file workflow, or `AskUserQuestion`-driven interview flow
- Reason: this lands the first exact tool names and durable state transitions from Claude's next base-tool batch without pretending that the larger tasks/plan runtime already exists
- Status: active

### Tasks command surface

- Decision: `/tasks` and `/bashes` currently toggle OpenClaude's simplified task panel instead of rendering Claude's full `BackgroundTasksDialog`
- Reason: OpenClaude already has session todos / plan-mode state and a lightweight task panel, but it does not yet have Claude's richer background-task engine or dialog flows
- Status: active

### Clear command lifecycle

- Decision: `/clear` currently starts a fresh scoped session and swaps the active session id, but it does not run Claude's full conversation-clear lifecycle
- Reason: Claude's implementation also executes session-end hooks, clears caches and metadata, rotates worktree/session state, and preserves or tears down task state in a much richer way than OpenClaude v0 currently does
- Status: active

### Permissions command surface

- Decision: `/permissions` is currently a read-only runtime summary panel with approval-sensitive tools and recent permission outcomes instead of Claude's interactive permission-rule manager
- Reason: the first v0 step is exposing the live permission model and session outcomes without pretending that persistent allow/deny rule editing, retry-denials, or managed-settings integration already exists
- Status: active

### Usage command surface

- Decision: `/usage` is currently a backend usage-summary panel with provider/auth/model/session context instead of Claude's full usage/settings-tab experience with account quota data
- Reason: OpenClaude does not yet have portable provider account-plan/quota APIs across its multi-provider runtime, so v0 exposes honest local/session state without pretending those provider-side numbers exist
- Status: active

### Stats command surface

- Decision: `/stats` is currently a local aggregate activity panel over stored session history instead of Claude's fuller stats UI and source calculations
- Reason: OpenClaude can compute local history summaries today, but it does not yet have Claude's full stats presentation, filters, or exact upstream calculation pipeline
- Status: active

### Plan command surface

- Decision: `/plan` currently enables session plan mode and can forward an inline planning prompt, but it does not yet implement Claude's plan-file storage or `/plan open` editor workflow
- Reason: OpenClaude v0 already has durable session `planMode` and todos, so the command can honestly land the plan-mode entry path first while plan-file persistence and editor flows remain separate parity work
- Status: active

### Rewind command surface

- Decision: `/rewind` currently rewinds only the conversation transcript and restored prompt text, not Claude's broader code/file-history rewind flow
- Reason: OpenClaude can safely land the message-selector and session-truncate behavior first using its current session store, while Claude's file-history restoration and confirmation workflow require a deeper workspace snapshot system that is not yet ported
- Status: active

### Login / logout command surface

- Decision: `/login` and `/logout` are currently adapted as multi-provider auth commands, not Claude's Anthropic-account-specific flows
- Reason: Claude's source behavior assumes one Anthropic auth plane, while OpenClaude v0 must route auth through provider selection and disconnect stored credentials per active provider
- Status: active
