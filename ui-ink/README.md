# OpenClaude UI (Ink)

This workspace contains the terminal UI layer for OpenClaude.

It talks to the Java backend over `stdio` using the contract in `../types/stdio/`.

## Development shape

1. Build the Java backend:

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=.gradle ./gradlew :app-cli:installDist
```

2. Point the UI at the backend binary and run the Ink app:

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/ui-ink
export OPENCLAUDE_BACKEND_BIN=../app-cli/build/install/openclaude/bin/openclaude
export OPENCLAUDE_BACKEND_ARGS=stdio
export JAVA_HOME=$(/usr/libexec/java_home -v 24)
npm install
npm run dev
```

If `OPENCLAUDE_BACKEND_BIN` is omitted, the UI now prefers the local packaged backend at `../app-cli/build/install/openclaude/bin/openclaude` and falls back to `openclaude` on `PATH` only if that repo-local binary is missing.

For sandboxed or isolated development, point the backend state into the repo instead of `~/.openclaude`:

```bash
export OPENCLAUDE_HOME=/Users/hshrimali-mbp/Desktop/claude-code-java/openclaude/.tmp-openclaude-home
```
