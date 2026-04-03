# OpenClaude Docs

This folder is the architecture map for the active `openclaude` runtime in this repository.

It is written against the code that is actually wired today, not against the broader Claude Code feature set or the inactive scaffolding directories that also exist in the repo.

## Recommended Reading Order

1. [architecture.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/architecture.md)
2. [subsystems.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/subsystems.md)
3. [execution-flows.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/execution-flows.md)
4. [commands.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/commands.md)
5. [tools.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/tools.md)
6. [homebrew-release.md](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/homebrew-release.md)

## Interactive Explorer

For a browsable architecture companion, open:

- [docs/site/index.html](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/docs/site/index.html)

It is a static page with:

- module and runtime maps
- prompt and tool-loop execution flows
- command and tool catalogs
- persistence and IPC summaries

## Scope

These docs focus on the modules included by [settings.gradle](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/settings.gradle):

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

There are also parallel directories such as `openclaude-app`, `openclaude-core`, `openclaude-provider-*`, `openclaude-tools`, and `openclaude-terminal`. Those are not part of the active Gradle build defined by `settings.gradle`, so they are treated here as parallel scaffolding, experiments, or namespace placeholders rather than the live runtime.

## Repo Truth Sources

When this docs set and the code disagree, the code wins. The most important source files are:

- [OpenClaudeApplication.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/OpenClaudeApplication.java)
- [QueryEngine.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/query/QueryEngine.java)
- [OpenClaudeStdioServer.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/stdio/OpenClaudeStdioServer.java)
- [CommandService.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/app-cli/src/main/java/com/openclaude/cli/service/CommandService.java)
- [DefaultToolRuntime.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/core/src/main/java/com/openclaude/core/tools/DefaultToolRuntime.java)
- [ProviderPlugin.java](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/provider-spi/src/main/java/com/openclaude/provider/spi/ProviderPlugin.java)
- [app.tsx](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/app.tsx)
- [normalizeMessages.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/normalizeMessages.ts)
- [buildMessageLookups.ts](/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink/src/messages/buildMessageLookups.ts)
