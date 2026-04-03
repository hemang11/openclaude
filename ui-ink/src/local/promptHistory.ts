import { appendFile, mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'

import { resolveOpenClaudeHome } from './system.ts'
import type { PromptHistoryEntry } from '../ui/HistorySearchOverlay.tsx'

const MAX_HISTORY_ITEMS = 100

type PromptHistoryLogEntry = {
  value: string
  createdAt: number
  project: string
  sessionId?: string
}

type PromptHistoryScope = {
  workspaceRoot?: string | null
  sessionId?: string | null
}

let pendingEntries: PromptHistoryLogEntry[] = []
let flushPromise: Promise<void> | null = null

export function resetPromptHistoryForTests(): void {
  pendingEntries = []
  flushPromise = null
}

export async function appendPromptHistoryEntry(
  value: string,
  scope: PromptHistoryScope = {},
): Promise<PromptHistoryEntry> {
  const targetPath = historyPath()
  const entry: PromptHistoryLogEntry = {
    value,
    createdAt: Date.now(),
    project: resolveProject(scope.workspaceRoot),
    sessionId: normalizeSessionId(scope.sessionId),
  }

  pendingEntries.push(entry)
  if (!flushPromise) {
    flushPromise = flushPromptHistory(targetPath).finally(() => {
      flushPromise = null
    })
  }
  await flushPromise

  return toPromptHistoryEntry(entry)
}

export async function loadPromptHistory(
  scope: PromptHistoryScope = {},
): Promise<PromptHistoryEntry[]> {
  const project = resolveProject(scope.workspaceRoot)
  const sessionId = normalizeSessionId(scope.sessionId)
  const currentSessionEntries: PromptHistoryEntry[] = []
  const otherSessionEntries: PromptHistoryEntry[] = []

  for (const entry of await readPromptHistoryLog()) {
    if (entry.project !== project) {
      continue
    }

    const promptEntry = toPromptHistoryEntry(entry)
    if (entry.sessionId === sessionId && sessionId) {
      currentSessionEntries.push(promptEntry)
    } else {
      otherSessionEntries.push(promptEntry)
    }

    if (currentSessionEntries.length + otherSessionEntries.length >= MAX_HISTORY_ITEMS) {
      break
    }
  }

  return currentSessionEntries.concat(otherSessionEntries)
}

async function readPromptHistoryLog(): Promise<PromptHistoryLogEntry[]> {
  const entries: PromptHistoryLogEntry[] = []
  const seenPending = new Set<string>()

  for (let index = pendingEntries.length - 1; index >= 0; index -= 1) {
    const pending = pendingEntries[index]
    if (!pending) {
      continue
    }
    entries.push(pending)
    seenPending.add(historyEntryKey(pending))
  }

  try {
    const contents = await readFile(historyPath(), 'utf8')
    const lines = contents.split(/\r?\n/).filter(Boolean)
    for (let index = lines.length - 1; index >= 0; index -= 1) {
      const line = lines[index]
      if (!line) {
        continue
      }
      try {
        const parsed = JSON.parse(line) as Partial<PromptHistoryLogEntry>
        if (!isPromptHistoryLogEntry(parsed)) {
          continue
        }
        const entry: PromptHistoryLogEntry = {
          value: parsed.value,
          createdAt: parsed.createdAt,
          project: path.resolve(parsed.project),
          ...(parsed.sessionId ? { sessionId: parsed.sessionId } : {}),
        }
        const key = historyEntryKey(entry)
        if (seenPending.has(key)) {
          continue
        }
        entries.push(entry)
      } catch {
        continue
      }
    }
  } catch {
    return entries
  }

  return entries
}

async function flushPromptHistory(targetPath: string): Promise<void> {
  while (pendingEntries.length > 0) {
    const batch = pendingEntries
    pendingEntries = []

    await mkdir(path.dirname(targetPath), { recursive: true })
    const payload = batch.map((entry) => `${JSON.stringify(entry)}\n`).join('')
    await appendFile(targetPath, payload, 'utf8')
  }
}

function historyPath(): string {
  return path.join(resolveOpenClaudeHome(), 'history.jsonl')
}

function resolveProject(workspaceRoot?: string | null): string {
  return path.resolve(workspaceRoot || process.cwd())
}

function normalizeSessionId(sessionId?: string | null): string | undefined {
  return sessionId?.trim() ? sessionId : undefined
}

function toPromptHistoryEntry(entry: PromptHistoryLogEntry): PromptHistoryEntry {
  return {
    value: entry.value,
    createdAt: entry.createdAt,
  }
}

function historyEntryKey(entry: PromptHistoryLogEntry): string {
  return `${entry.createdAt}:${entry.project}:${entry.sessionId ?? ''}:${entry.value}`
}

function isPromptHistoryLogEntry(entry: Partial<PromptHistoryLogEntry>): entry is PromptHistoryLogEntry {
  return typeof entry.value === 'string'
    && typeof entry.createdAt === 'number'
    && Number.isFinite(entry.createdAt)
    && typeof entry.project === 'string'
}
