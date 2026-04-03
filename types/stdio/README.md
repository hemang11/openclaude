# OpenClaude Stdio Types

This folder is the source-of-truth contract for the `stdio` IPC layer between:

- the Java backend in `app-cli/src/main/java/com/openclaude/cli/stdio`
- the React/Ink frontend in `ui-ink/`

The transport is newline-delimited JSON over standard input and standard output.

Envelope kinds:

- `request`
- `response`
- `event`

The frontend should send only `request` envelopes. The Java backend responds with:

- exactly one `response` envelope per request
- zero or more `event` envelopes before the final response for streaming flows such as prompt generation or browser-auth status updates

See `protocol.ts` for the typed contract that the JS frontend imports directly.
