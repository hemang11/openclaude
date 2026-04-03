const modules = [
  {
    title: "app-cli",
    body: "Java entrypoint, service layer, stdio server, command execution, and session/provider/model operators.",
    tags: ["entrypoint", "stdio", "commands"],
  },
  {
    title: "core",
    body: "Query engine, sessions, tools, compaction, diagnostics, state stores, instruction loading, and provider routing.",
    tags: ["runtime", "sessions", "tools"],
  },
  {
    title: "provider-spi",
    body: "Provider contract layer: prompt requests/results/events, model descriptors, tool blocks, and plugin API.",
    tags: ["spi", "contracts"],
  },
  {
    title: "provider-openai",
    body: "OpenAI API-key and browser-auth implementations, including ChatGPT Codex responses support.",
    tags: ["provider", "openai"],
  },
  {
    title: "provider-anthropic",
    body: "Anthropic provider implementation with streaming and tool integration.",
    tags: ["provider", "anthropic"],
  },
  {
    title: "provider-gemini / provider-mistral / provider-kimi / provider-bedrock",
    body: "Additional provider plugins loaded through ServiceLoader and exposed through the same ProviderPlugin contract.",
    tags: ["provider", "extensible"],
  },
  {
    title: "auth",
    body: "Browser SSO helpers such as callback handling, PKCE generation, and browser launching.",
    tags: ["auth", "oauth"],
  },
  {
    title: "ui-ink",
    body: "React + Ink terminal UI: prompt input, overlays, transcript normalization, and status shell.",
    tags: ["frontend", "ink"],
  },
  {
    title: "types/stdio",
    body: "Shared protocol types used by both the backend and the Ink client.",
    tags: ["ipc", "protocol"],
  },
]

const flows = [
  {
    title: "Prompt submit",
    body: "Ink sends prompt.submit -> stdio server -> PromptRouter -> QueryEngine -> provider plugin -> events and final snapshot.",
    tags: ["prompt", "query engine"],
  },
  {
    title: "Tool loop",
    body: "Provider emits tool use -> QueryEngine dispatches runtime -> permissions and hooks -> tool result reinjected into the next model pass.",
    tags: ["tools", "orchestration"],
  },
  {
    title: "Permission approval",
    body: "ToolPermissionRequest -> stdio PermissionRequestEvent -> modal Ink overlay -> permission.respond -> pending future resolves in backend.",
    tags: ["permissions", "stdio"],
  },
  {
    title: "Compaction",
    body: "CommandService routes /compact -> CompactConversationService -> compact hooks, no-tools summary, context-benefit check, save-or-skip decision.",
    tags: ["compact", "sessions"],
  },
  {
    title: "Transcript render",
    body: "BackendSnapshot.messages -> reorder -> lookup building -> grouping -> read/search collapsing -> terminal render tree.",
    tags: ["ui", "messages"],
  },
  {
    title: "Provider selection",
    body: "ProviderService and ModelService mutate durable global state; the frontend reflects the new snapshot and status line.",
    tags: ["providers", "state"],
  },
]

const persistence = [
  {
    title: "state.json",
    body: "Global provider/model/session selection plus settings and connection state.",
    tags: ["global", "json"],
  },
  {
    title: "sessions/<id>.json",
    body: "Per-session transcript, todos, plan mode, file-read state, and session-memory state.",
    tags: ["session", "json"],
  },
  {
    title: "session-memory/sessions/<id>.md",
    body: "Session-memory sidecar used by the compaction and summarization subsystem.",
    tags: ["session memory", "markdown"],
  },
  {
    title: "OPENCLAUDE_HOME",
    body: "Optional root override; otherwise the runtime uses ~/.openclaude.",
    tags: ["paths", "config"],
  },
]

const commands = [
  { title: "/status", family: "backend", body: "Backend panel for runtime and provider readiness." },
  { title: "/context", family: "backend", body: "Backend panel for projected context usage." },
  { title: "/tools", family: "backend", body: "Backend panel listing tool definitions available to the active model." },
  { title: "/usage", family: "backend", body: "Backend panel for plan/runtime limits." },
  { title: "/stats", family: "backend", body: "Backend panel for usage statistics." },
  { title: "/cost", family: "backend", body: "Backend panel summarizing session timing and text volume." },
  { title: "/compact", family: "backend", body: "Backend command that executes the compaction subsystem." },
  { title: "/diff", family: "backend", body: "Backend panel for workspace git changes." },
  { title: "/doctor", family: "backend", body: "Backend diagnostics panel." },
  { title: "/session", family: "backend", body: "Backend panel for current session details." },
  { title: "/permissions", family: "hybrid", body: "Backend snapshot plus frontend editor for permission rules." },
  { title: "/provider", family: "frontend", body: "Frontend picker and connect flow for providers." },
  { title: "/models", family: "frontend", body: "Frontend picker with backend model selection mutation." },
  { title: "/config", family: "hybrid", body: "Frontend config panel backed by settings.update mutations." },
  { title: "/resume", family: "hybrid", body: "Frontend session picker backed by sessions.resume." },
  { title: "/rewind", family: "hybrid", body: "Frontend checkpoint chooser backed by sessions.rewind." },
  { title: "/clear", family: "hybrid", body: "Frontend action that triggers sessions.clear." },
  { title: "/rename", family: "hybrid", body: "Frontend rename flow backed by sessions.rename." },
  { title: "/memory", family: "frontend", body: "Frontend memory-file opener/editor surface." },
  { title: "/tasks", family: "frontend", body: "Frontend-only task/task-list surface." },
  { title: "/copy", family: "frontend", body: "Frontend clipboard action." },
  { title: "/exit", family: "frontend", body: "Frontend exit action." },
]

const tools = [
  { title: "bash", body: "Shell command tool with permission policy and cancellable runtime.", tags: ["shell", "mutating"] },
  { title: "Glob", body: "Pattern-based filesystem discovery.", tags: ["read-only", "filesystem"] },
  { title: "Grep", body: "Text search across local files.", tags: ["read-only", "filesystem"] },
  { title: "Read", body: "Reads files and updates file-read state.", tags: ["read-only", "filesystem"] },
  { title: "Edit", body: "Structured file edit tool guarded by mutation checks.", tags: ["write", "filesystem"] },
  { title: "Write", body: "Whole-file write tool.", tags: ["write", "filesystem"] },
  { title: "WebFetch", body: "Fetches pages and extracts readable content.", tags: ["network", "fetch"] },
  { title: "WebSearch", body: "Provider-native or local web search path.", tags: ["network", "search"] },
  { title: "TodoWrite", body: "Updates session todo state.", tags: ["session", "planner"] },
  { title: "AskUserQuestion", body: "Interactive structured question/answer tool.", tags: ["interactive", "clarification"] },
  { title: "EnterPlanMode", body: "Enables plan mode in the active session.", tags: ["session", "control"] },
  { title: "ExitPlanMode", body: "Disables plan mode in the active session.", tags: ["session", "control"] },
]

const uiPipeline = [
  {
    title: "App shell",
    body: "app.tsx owns backend connectivity, prompt state, overlays, status feed, busy state, and mutation routing.",
    tags: ["app", "state"],
  },
  {
    title: "Prompt stack",
    body: "editor -> useTextInput -> BaseTextInput -> TextInput -> PromptInputPanel.",
    tags: ["input", "editing"],
  },
  {
    title: "Suggestions",
    body: "Slash commands, model picks, resume suggestions, and @ file references are computed in the frontend.",
    tags: ["suggestions", "commands"],
  },
  {
    title: "Message lookup layer",
    body: "Tool ownership, sibling groups, hook state, and unresolved hook counts are derived in buildMessageLookups.",
    tags: ["messages", "grouping"],
  },
  {
    title: "Normalization layer",
    body: "reorderMessagesForUI, groupToolUses, and collapseReadSearchGroups create terminal-friendly transcript shapes.",
    tags: ["messages", "rendering"],
  },
  {
    title: "Shell layout",
    body: "ReplShell composes header, transcript, live rows, overlay, prompt, status line, and status feed.",
    tags: ["layout", "terminal"],
  },
]

function createCard(item, extraClass = "") {
  const card = document.createElement("article")
  card.className = `card ${extraClass}`.trim()
  card.dataset.search = `${item.title} ${item.body} ${(item.tags || []).join(" ")} ${(item.family || "")}`.toLowerCase()

  const title = document.createElement("h3")
  title.textContent = item.title

  const body = document.createElement("p")
  body.textContent = item.body

  const tags = document.createElement("div")
  tags.className = "tag-row"

  const tagValues = item.tags || (item.family ? [item.family] : [])
  tagValues.forEach((tagValue) => {
    const tag = document.createElement("span")
    tag.className = "tag"
    tag.textContent = tagValue
    tags.appendChild(tag)
  })

  card.append(title, body, tags)
  return card
}

function renderCards(targetId, items, mapper = (item) => item) {
  const target = document.getElementById(targetId)
  items.map(mapper).forEach((item) => target.appendChild(createCard(item)))
}

renderCards("module-grid", modules)
renderCards("flow-grid", flows)
renderCards("persistence-grid", persistence)
renderCards("tool-grid", tools)
renderCards("ui-grid", uiPipeline)
renderCards("command-grid", commands, (item) => ({
  ...item,
  tags: [item.family],
}))

const searchInput = document.getElementById("search")
const commandFilterButtons = [...document.querySelectorAll("[data-command-filter]")]

function applyFilters() {
  const query = searchInput.value.trim().toLowerCase()
  const activeCommandFilter =
    commandFilterButtons.find((button) => button.classList.contains("active"))?.dataset.commandFilter || "all"

  document.querySelectorAll(".card").forEach((card) => {
    const haystack = card.dataset.search || ""
    const commandFamilyTag = card.querySelector(".tag")?.textContent?.toLowerCase() || ""
    const matchesQuery = !query || haystack.includes(query)
    const matchesCommandFamily =
      !card.parentElement?.id.includes("command") ||
      activeCommandFilter === "all" ||
      commandFamilyTag === activeCommandFilter

    card.classList.toggle("hidden", !(matchesQuery && matchesCommandFamily))
  })
}

searchInput.addEventListener("input", applyFilters)

commandFilterButtons.forEach((button) => {
  button.addEventListener("click", () => {
    commandFilterButtons.forEach((candidate) => candidate.classList.remove("active"))
    button.classList.add("active")
    applyFilters()
  })
})

applyFilters()
