# Commands

This is the current slash-command surface advertised by the backend command catalog in [CommandService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CommandService.java).

The important architectural detail is that not all commands execute the same way.

## Command Families

### Backend commands

These round-trip through `command.execute` and are handled in `CommandService.run(...)`.

### Frontend commands

These are handled directly by the Ink UI, usually as overlays, pickers, or local actions.

### Hybrid commands

These begin in the frontend and then send follow-up mutations such as `settings.update`, `sessions.resume`, `models.select`, or permission editor mutations.

## Current Catalog

| Command | Handler | Primary owner | What it does |
|---|---|---|---|
| `/resume [session]` | frontend overlay | Ink UI + `sessions.resume` | Resume another session from current scope |
| `/session` | backend | `CommandService` | Show current session details |
| `/rename <name>` | frontend overlay | Ink UI + `sessions.rename` | Rename the active session |
| `/login` | frontend overlay | Ink UI + provider mutations | Start provider sign-in flow |
| `/logout` | frontend overlay | Ink UI + provider mutations | Disconnect the active provider |
| `/provider` | frontend overlay | Ink UI + provider mutations | Connect or switch providers |
| `/models [model]` | frontend overlay | Ink UI + `models.select` | Select the active model |
| `/config` | frontend overlay | Ink UI + `settings.update` | Open configuration panel |
| `/status` | backend | `CommandService` | Show runtime/provider/tool readiness |
| `/context` | backend | `CommandService` | Show projected context usage |
| `/tools` | backend | `CommandService` | List tools exposed to the active model |
| `/permissions` | backend plus UI editor | `CommandService` + Ink UI | Manage permission rules |
| `/usage` | backend | `CommandService` | Show provider/runtime limits |
| `/stats` | backend | `CommandService` | Show session and usage statistics |
| `/copy [N]` | frontend action | Ink UI | Copy last response |
| `/cost` | backend | `CommandService` | Show session timing/text-volume summary |
| `/clear` | frontend action + backend mutation | Ink UI + `sessions.clear` | Start a fresh session |
| `/compact` | backend | `CompactConversationService` | Compact conversation history |
| `/plan [description|open]` | frontend overlay | Ink UI + session mutations | Plan-mode UI |
| `/rewind` | frontend overlay | Ink UI + `sessions.rewind` | Restore to a prior checkpoint |
| `/memory` | frontend overlay | Ink UI + local editor | Open memory files |
| `/diff` | backend | `CommandService` | Show git diff panel |
| `/doctor` | backend | `CommandService` | Run diagnostics panel |
| `/tasks` | frontend overlay | Ink UI | Background task/task list surface |
| `/fast [on|off]` | frontend overlay | Ink UI + settings | Toggle fast mode |
| `/effort [low|medium|high|max|auto]` | backend | `CommandService` | Set effort level |
| `/keybindings` | frontend overlay | Ink UI | Show shortcuts help |
| `/help` | frontend overlay | Ink UI | Show help panel |
| `/exit` | frontend action | Ink UI | Exit the UI |

## Backend Command Panels

Backend commands typically return either:

- plain text `message`
- or a `PanelView`

Important panel-producing commands:

- `/status`
- `/context`
- `/tools`
- `/usage`
- `/stats`
- `/cost`
- `/diff`
- `/doctor`
- `/session`

These are surfaced over stdio and rendered by the Ink UI panel overlay path.

## Command Metadata Exposure

The backend command catalog is part of the `BackendSnapshot`, so the frontend does not hard-code the public command list in one isolated place.

That allows the UI to:

- show `/` suggestions
- show argument hints
- decide whether a command is backend, frontend, or overlay-backed
- hide disabled or non-public commands

The shared shape is defined in [protocol.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/types/stdio/protocol.ts).

## Architectural Notes

### `/compact`

`/compact` is backend-owned. The UI now only adds:

- working indicator
- submit guards while compaction is in flight
- status/error messaging for blocked submissions

The actual compaction logic lives entirely in Java.

### `/permissions`

`/permissions` is a hybrid subsystem:

- backend owns rule persistence and snapshots
- frontend owns the interactive editor UI

### `/provider` and `/models`

These are frontend-led because the UX is picker-based, but they still rely on backend mutations to change active provider/model state.

## What To Read Next

- [architecture.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/architecture.md)
- [execution-flows.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/execution-flows.md)
- [tools.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/tools.md)
