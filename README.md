# OpenClaude

`openclaude` is a Java-backed, provider-neutral terminal coding agent modeled after the Claude Code interaction pattern, with a separate React/Ink UI speaking to the backend over a typed stdio protocol.

It is designed as a local coding assistant runtime, not just a thin API client:

- Java multi-module backend for sessions, tools, compaction, diagnostics, and provider routing
- React/Ink terminal UI for transcript rendering, prompt input, overlays, and command UX
- typed stdio contract between backend and frontend
- multiple provider backends behind one provider SPI
- persistent sessions, permission rules, settings, and session-memory state under `~/.openclaude`

Suggested GitHub repo description:

> Java + Ink reimplementation of a Claude Code-style terminal coding agent with multi-provider support and a typed stdio backend.

The live Claude-parity checklist is tracked in `PARITY.md`. `PARITY.md` is the implementation tracker that should be checked off as parity work lands.

This repo is being built as a provider-neutral CLI with the long-term goal of matching Claude Code's command surface and interaction model while supporting multiple model providers:

- Anthropic / Claude
- OpenAI
- Gemini
- Mistral
- Kimi
- Bedrock

## Current State

The repo currently includes:

- a Java 21-targeted Gradle multi-module repo
- a separate React/Ink UI workspace that talks to the Java backend over stdio
- a shared typed stdio contract under `types/stdio`
- a command-aware stdio contract with slash-command metadata
- a provider SPI and registry
- persisted provider connection state
- persisted conversation sessions under `~/.openclaude/sessions`
- initial `provider` and `models` command handlers
- a minimal interactive shell that routes `/provider` and `/models`
- a dedicated `openclaude stdio` backend mode for the Ink UI
- a `QueryEngine`-style turn runner that appends user and assistant messages to a session transcript
- provider-neutral prompt requests with streaming event support
- OpenAI prompt execution over the Responses API with API-key auth
- first-party OpenAI browser OAuth with persisted tokens and refresh support
- direct OpenAI account-backed prompt execution through `https://chatgpt.com/backend-api/codex/responses`
- OpenAI text streaming in the interactive shell
- explicit implementation and drift logs

The v0 coding-agent slice is complete. The remaining backlog is post-v0 work such as broader command parity, extra tool surfaces, MCP/plugins/skills, richer specialized renderers, and other non-core Claude-adjacent features.

The Ink UI is now bootable over stdio with:

- `/` command palette
- interactive `/provider` and `/models` pickers
- `/config`, `/context`, `/copy`, `/cost`, `/diff`, `/doctor`, and `/keybindings`
- wired shortcuts including `meta+p`, `meta+o`, `ctrl+o`, `ctrl+t`, `ctrl+s`, and `ctrl+g`
- structured transcript blocks for user, assistant, thinking, and status output
- grouped persisted/live tool blocks in the transcript
- streamed reasoning/tool activity rendering
- inline prompt suggestion overlays for slash commands and `@` file references
- a custom prompt composer with multiline cursor movement, `Shift+Enter` / `Meta+Enter` newline insertion, and `\` + `Enter` newline insertion
- `Tab` autocomplete plus session-local prompt history with `Up` and `Down`
- a dedicated transcript normalization layer with structured assistant `thinking`, `text`, `tool_use`, and `tool_result` blocks
- a `Messages -> MessageRow -> Message` render pipeline instead of a monolithic transcript component

The current UI is already structured around:

- typed backend snapshots and streamed prompt events
- transcript normalization and grouped tool rendering
- prompt, bash, overlay, history-search, and picker flows
- permission modals, command panels, and compaction/progress status

## Build

The project targets Java 21 bytecode.

In this environment:

- Gradle itself can be launched with Java 17
- the packaged `openclaude` launcher must be run with Java 21+

Single-command build for the current repo:

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
./build.sh
```

That script:

- uses Java 17 for Gradle
- builds the packaged backend with `:app-cli:installDist`
- installs `ui-ink` dependencies with `npm ci` if `node_modules` is missing
- runs `npm run typecheck` for the Ink frontend

## Quick Start

Build everything:

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
./build.sh
```

Run the Ink UI against the packaged backend:

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink
npm run dev
```

For isolated local state during development:

```bash
export OPENCLAUDE_HOME=/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/.tmp-openclaude-home
```

Example:

Use Java 17 as the Gradle launcher in this environment:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run --args="provider list"
```

OpenAI API-key flow:

```bash
export OPENAI_API_KEY=...
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run --args="provider connect openai --api-key-env OPENAI_API_KEY"
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run --args="provider use openai"
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run
```

Then in the shell:

```text
/models
Explain this repository structure.
```

The active conversation transcript is persisted under `~/.openclaude/sessions/<session-id>.json`, and the active session id is tracked in `~/.openclaude/state.json`.

OpenAI browser-auth flow:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run --args="provider connect openai --browser"
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run --args="provider use openai"
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run
```

This path currently:

- opens a browser to `https://auth.openai.com/oauth/authorize`
- listens on `http://localhost:1455/auth/callback`
- stores the resulting tokens under `~/.openclaude/auth/openai/default.json`
- sends account-backed model requests to `https://chatgpt.com/backend-api/codex/responses`

Browser-auth OpenAI works best with the Codex-compatible models that appear first in `/models`. API-key-oriented OpenAI models are still listed, but `/models` is not auth-aware yet.

Bedrock uses AWS credentials instead of API-key auth:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run --args="provider connect bedrock --aws-profile default"
```

To launch the Java backend shell directly:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:run
```

To build the packaged backend launcher:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:clean :app-cli:installDist
```

To run the backend in stdio mode:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 24) app-cli/build/install/openclaude/bin/openclaude stdio
```

To run the React/Ink UI against that backend:

```bash
cd ui-ink
export OPENCLAUDE_BACKEND_BIN=../app-cli/build/install/openclaude/bin/openclaude
export OPENCLAUDE_BACKEND_ARGS=stdio
export JAVA_HOME=$(/usr/libexec/java_home -v 24)
npm install
npm run dev
```

If `OPENCLAUDE_BACKEND_BIN` is omitted, the Ink UI prefers the repo-local packaged backend at `../app-cli/build/install/openclaude/bin/openclaude` and only falls back to `openclaude` on `PATH` if that local binary is missing.

For sandboxed development, set:

```bash
export OPENCLAUDE_HOME=/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/.tmp-openclaude-home
```

## Structure

- `app-cli`: Java CLI entrypoint, command wiring, and stdio backend server
- `core`: persisted state, sessions, query engine, provider registry
- `auth`: browser-auth abstractions
- `provider-spi`: provider contract and model metadata
- `provider-*`: provider catalog modules discovered via `ServiceLoader`
- `ui-ink`: React/Ink terminal UI
- `types/stdio`: shared frontend/backend IPC contract

## Docs

- `docs/README.md`: architecture docs index
- `docs/architecture.md`: end-to-end active runtime architecture
- `docs/subsystems.md`: subsystem-level deep dive
- `docs/execution-flows.md`: startup, prompt, tool, compaction, and render flows
- `docs/commands.md`: slash-command surface map
- `docs/tools.md`: built-in tool runtime catalog
- `docs/site/index.html`: static interactive architecture explorer
- `IMPLEMENTATION.md`: live architecture and implementation notes
- `DRIFT.md`: deliberate deviations from the TypeScript reference implementation
- `PARITY.md`: live Claude Code parity ledger and implementation checklist
