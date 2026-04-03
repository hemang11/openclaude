import { readdirSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

import type { BackendSnapshot, CommandView, ModelView, SessionListItemView } from '../../../types/stdio/protocol.ts'
import type { PromptInputMode } from './inputModes.ts'

export type PromptSuggestionItem = {
  key: string
  label: string
  detail?: string
  replacement: string
  cursorOffset: number
}

export type PromptSuggestionState = {
  kind: 'command' | 'command-argument' | 'file' | 'directory' | null
  queryKey: string
  items: PromptSuggestionItem[]
  acceptsOnSubmit: boolean
  autocompleteValue?: string | null
  autocompleteCursorOffset?: number | null
  tabAutocompleteValue?: string | null
  tabAutocompleteCursorOffset?: number | null
  commandArgumentHint?: string | null
}

type PromptSuggestionContext = {
  sessionSuggestions?: SessionListItemView[]
}

type CompletionToken = {
  token: string
  startPos: number
  isQuoted?: boolean
}

type FileReferenceQuery = {
  query: string
  token: string
  start: number
  end: number
  isQuoted: boolean
  hasAtPrefix: boolean
}

type CompletionReplacement = {
  value: string
  cursorOffset: number
}

type PathCompletion = {
  id: string
  displayText: string
  type: 'directory' | 'file'
}

// Unicode-aware character classes copied from Claude's typeahead helpers.
const AT_TOKEN_HEAD_RE = /^@[\p{L}\p{N}\p{M}_\-./\\()[\]~:]*/u
const PATH_CHAR_HEAD_RE = /^[\p{L}\p{N}\p{M}_\-./\\()[\]~:]+/u
const TOKEN_WITH_AT_RE = /(@[\p{L}\p{N}\p{M}_\-./\\()[\]~:]*|[\p{L}\p{N}\p{M}_\-./\\()[\]~:]+)$/u

export function buildPromptSuggestionState(
  mode: PromptInputMode,
  input: string,
  snapshot: BackendSnapshot | null,
  workspaceFiles: string[],
  cursorOffset = input.length,
  context: PromptSuggestionContext = {},
): PromptSuggestionState {
  if (mode !== 'prompt') {
    return {
      kind: null,
      queryKey: `mode:${mode}`,
      items: [],
      acceptsOnSubmit: false,
      autocompleteValue: null,
      autocompleteCursorOffset: null,
      tabAutocompleteValue: null,
      tabAutocompleteCursorOffset: null,
      commandArgumentHint: null,
    }
  }

  const trimmed = input.trimStart()
  if (trimmed.startsWith('/')) {
    const commandToken = trimmed.slice(1).split(/\s+/)[0] ?? ''
    const commandArgumentHint = resolveCommandArgumentHint(trimmed, snapshot?.commands ?? [])
    const firstSpaceIndex = trimmed.indexOf(' ')
    const argsText = firstSpaceIndex === -1 ? '' : trimmed.slice(firstSpaceIndex + 1)
    const hasRealArguments = argsText.trim().length > 0
    const exactCommand = findExactCommand(snapshot?.commands ?? [], commandToken)
    if (firstSpaceIndex !== -1 && exactCommand) {
      const argumentSuggestions = buildCommandArgumentSuggestions({
        exactCommand,
        commandPrefix: trimmed.slice(0, firstSpaceIndex + 1),
        argsText,
        snapshot,
        context,
      })
      if (argumentSuggestions) {
        return {
          ...argumentSuggestions,
          commandArgumentHint,
        }
      }
    }
    if (firstSpaceIndex !== -1 && (exactCommand || hasRealArguments)) {
      return {
        kind: null,
        queryKey: `command:${commandToken.toLowerCase()}:args`,
        items: [],
        acceptsOnSubmit: false,
        autocompleteValue: null,
        autocompleteCursorOffset: null,
        tabAutocompleteValue: null,
        tabAutocompleteCursorOffset: null,
        commandArgumentHint,
      }
    }

    const commands = rankCommands(snapshot?.commands ?? [], commandToken)
    const items = commands.slice(0, 8).map((command) => {
      const replacement = `/${command.name}${command.argumentHint ? ' ' : ''}`
      return {
        key: command.name,
        label: `/${command.name}${command.argumentHint ? ` ${command.argumentHint}` : ''}`,
        detail: command.description,
        replacement,
        cursorOffset: replacement.length,
      }
    })
    const autocompleteValue =
      commands.length > 0 ? `/${commands[0]!.name}${commands[0]!.argumentHint ? ' ' : ''}` : null

    return {
      kind: 'command',
      queryKey: `command:${commandToken.toLowerCase()}`,
      items,
      acceptsOnSubmit: commandToken.length > 0 && !hasExactCommand(snapshot?.commands ?? [], commandToken),
      autocompleteValue,
      autocompleteCursorOffset: autocompleteValue?.length ?? null,
      tabAutocompleteValue: null,
      tabAutocompleteCursorOffset: null,
      commandArgumentHint,
    }
  }

  const fileQuery = extractFileReferenceQuery(input, cursorOffset)
  if (!fileQuery) {
    return {
      kind: null,
      queryKey: 'none',
      items: [],
      acceptsOnSubmit: false,
      autocompleteValue: null,
      autocompleteCursorOffset: null,
      tabAutocompleteValue: null,
      tabAutocompleteCursorOffset: null,
      commandArgumentHint: null,
    }
  }

  if (isPathLikeToken(fileQuery.query)) {
    const pathItems = getPathCompletions(fileQuery.query).map((completion) => {
      const replacement = replacePathReference(input, fileQuery, completion)
      return {
        key: `path:${completion.id}`,
        label: `@${completion.displayText}`,
        replacement: replacement.value,
        cursorOffset: replacement.cursorOffset,
      }
    })
    if (pathItems.length > 0) {
      return {
        kind: 'directory',
        queryKey: `directory:${fileQuery.query}:${fileQuery.start}:${fileQuery.end}`,
        items: pathItems,
        acceptsOnSubmit: false,
        autocompleteValue: pathItems[0]?.replacement ?? null,
        autocompleteCursorOffset: pathItems[0]?.cursorOffset ?? null,
        tabAutocompleteValue: null,
        tabAutocompleteCursorOffset: null,
        commandArgumentHint: null,
      }
    }
  }

  const matchingFiles = workspaceFiles
    .filter((filePath) => fileQuery.query.length === 0 || filePath.toLowerCase().includes(fileQuery.query.toLowerCase()))
    .slice(0, 8)

  const items = matchingFiles.map((filePath) => {
    const replacement = replaceFileReference(input, fileQuery, filePath, true)
    return {
      key: filePath,
      label: formatFileReferenceValue(filePath, fileQuery, true).trimEnd(),
      replacement: replacement.value,
      cursorOffset: replacement.cursorOffset,
    }
  })

  const commonPrefix = findLongestCommonPrefix(matchingFiles)
  const effectiveTokenLength = getEffectiveTokenLength(fileQuery)
  const commonPrefixReplacement =
    commonPrefix.length > effectiveTokenLength
      ? replaceFileReference(input, fileQuery, commonPrefix, false)
      : null
  const fallbackAutocomplete = items[0]

  return {
    kind: 'file',
    queryKey: `file:${fileQuery.query.toLowerCase()}:${fileQuery.start}:${fileQuery.end}`,
    items,
    acceptsOnSubmit: false,
    autocompleteValue: commonPrefixReplacement?.value ?? fallbackAutocomplete?.replacement ?? null,
    autocompleteCursorOffset: commonPrefixReplacement?.cursorOffset ?? fallbackAutocomplete?.cursorOffset ?? null,
    tabAutocompleteValue: commonPrefixReplacement?.value ?? null,
    tabAutocompleteCursorOffset: commonPrefixReplacement?.cursorOffset ?? null,
    commandArgumentHint: null,
  }
}

function buildCommandArgumentSuggestions(args: {
  exactCommand: CommandView
  commandPrefix: string
  argsText: string
  snapshot: BackendSnapshot | null
  context: PromptSuggestionContext
}): PromptSuggestionState | null {
  const { exactCommand, commandPrefix, argsText, snapshot, context } = args
  const normalizedArgs = argsText.trim()
  const normalizedQuery = normalizedArgs.toLowerCase()

  if (exactCommand.name === 'resume') {
    const sessions = (context.sessionSuggestions ?? [])
      .filter((session) =>
        normalizedQuery.length === 0
          || session.sessionId.toLowerCase().includes(normalizedQuery)
          || session.title.toLowerCase().includes(normalizedQuery)
          || session.preview.toLowerCase().includes(normalizedQuery),
      )
      .slice(0, 8)
    if (sessions.length === 0) {
      return null
    }

    const items = sessions.map((session) => {
      const replacement = `${commandPrefix}${session.sessionId}`
      return {
        key: `resume:${session.sessionId}`,
        label: session.title,
        detail: `${session.sessionId} · ${session.preview}`,
        replacement,
        cursorOffset: replacement.length,
      }
    })
    return {
      kind: 'command-argument',
      queryKey: `resume:${normalizedQuery}`,
      items,
      acceptsOnSubmit: true,
      autocompleteValue: items[0]?.replacement ?? null,
      autocompleteCursorOffset: items[0]?.cursorOffset ?? null,
      tabAutocompleteValue: items[0]?.replacement ?? null,
      tabAutocompleteCursorOffset: items[0]?.cursorOffset ?? null,
      commandArgumentHint: null,
    }
  }

  if (exactCommand.name === 'model' || exactCommand.name === 'models') {
    const models = rankModels(snapshot?.models ?? [], normalizedQuery).slice(0, 8)
    if (models.length === 0) {
      return null
    }

    const items = models.map((model) => {
      const token = `${model.providerId}:${model.id}`
      const replacement = `${commandPrefix}${token}`
      return {
        key: `model:${token}`,
        label: model.displayName,
        detail: `${model.providerDisplayName} · ${token}`,
        replacement,
        cursorOffset: replacement.length,
      }
    })
    return {
      kind: 'command-argument',
      queryKey: `model:${normalizedQuery}`,
      items,
      acceptsOnSubmit: true,
      autocompleteValue: items[0]?.replacement ?? null,
      autocompleteCursorOffset: items[0]?.cursorOffset ?? null,
      tabAutocompleteValue: items[0]?.replacement ?? null,
      tabAutocompleteCursorOffset: items[0]?.cursorOffset ?? null,
      commandArgumentHint: null,
    }
  }

  return null
}

function resolveCommandArgumentHint(input: string, commands: CommandView[]): string | null {
  if (!input.startsWith('/')) {
    return null
  }

  const firstSpaceIndex = input.indexOf(' ')
  if (firstSpaceIndex === -1) {
    return null
  }

  const commandName = input.slice(1, firstSpaceIndex).toLowerCase()
  const argsText = input.slice(firstSpaceIndex + 1)
  if (argsText.trim().length > 0) {
    return null
  }

  const command = commands.find(
    (entry) => entry.name === commandName || entry.aliases.includes(commandName),
  )
  return command?.argumentHint ?? null
}

function rankCommands(commands: CommandView[], query: string): CommandView[] {
  const normalized = query.toLowerCase()
  return commands
    .filter((command) => command.enabled && !command.hidden)
    .map((command) => ({ command, score: scoreCommand(command, normalized) }))
    .filter((entry) => normalized.length === 0 || entry.score > 0)
    .sort((left, right) => {
      if (right.score !== left.score) {
        return right.score - left.score
      }
      return left.command.name.localeCompare(right.command.name)
    })
    .map((entry) => entry.command)
}

function scoreCommand(command: CommandView, query: string): number {
  if (query.length === 0) {
    return 1
  }
  if (command.name === query) {
    return 100
  }
  if (command.name.startsWith(query)) {
    return 80
  }
  if (command.aliases.includes(query)) {
    return 75
  }
  if (command.aliases.some((alias) => alias.startsWith(query))) {
    return 60
  }
  if (command.name.includes(query)) {
    return 40
  }
  if (command.aliases.some((alias) => alias.includes(query))) {
    return 20
  }
  return 0
}

function rankModels(models: ModelView[], query: string): ModelView[] {
  return models
    .map((model) => ({ model, score: scoreModel(model, query) }))
    .filter((entry) => query.length === 0 || entry.score > 0)
    .sort((left, right) => {
      if (right.score !== left.score) {
        return right.score - left.score
      }
      return left.model.displayName.localeCompare(right.model.displayName)
    })
    .map((entry) => entry.model)
}

function scoreModel(model: ModelView, query: string): number {
  if (query.length === 0) {
    return model.active ? 3 : 1
  }
  const providerScopedId = `${model.providerId}:${model.id}`.toLowerCase()
  const displayName = model.displayName.toLowerCase()
  const providerName = model.providerDisplayName.toLowerCase()
  if (providerScopedId === query) {
    return 100
  }
  if (model.id.toLowerCase() === query) {
    return 90
  }
  if (providerScopedId.startsWith(query)) {
    return 80
  }
  if (displayName.startsWith(query)) {
    return 70
  }
  if (displayName.includes(query)) {
    return 50
  }
  if (providerName.includes(query) || providerScopedId.includes(query)) {
    return 30
  }
  return 0
}

function hasExactCommand(commands: CommandView[], token: string): boolean {
  return findExactCommand(commands, token) != null
}

function findExactCommand(commands: CommandView[], token: string): CommandView | null {
  const normalized = token.toLowerCase()
  return commands.find((command) => command.name === normalized || command.aliases.includes(normalized)) ?? null
}

function extractFileReferenceQuery(input: string, cursorOffset: number): FileReferenceQuery | null {
  const completionToken = extractCompletionToken(input, cursorOffset, true)
  if (!completionToken || !completionToken.token.startsWith('@')) {
    return null
  }

  return {
    query: extractSearchToken(completionToken),
    token: completionToken.token,
    start: completionToken.startPos,
    end: completionToken.startPos + completionToken.token.length,
    isQuoted: Boolean(completionToken.isQuoted),
    hasAtPrefix: completionToken.token.startsWith('@'),
  }
}

function replaceFileReference(
  input: string,
  query: FileReferenceQuery,
  filePath: string,
  isComplete: boolean,
): CompletionReplacement {
  const replacement = formatFileReferenceValue(filePath, query, isComplete)
  return {
    value: `${input.slice(0, query.start)}${replacement}${input.slice(query.end)}`,
    cursorOffset: query.start + replacement.length,
  }
}

function replacePathReference(
  input: string,
  query: FileReferenceQuery,
  completion: PathCompletion,
): CompletionReplacement {
  const replacement = `@${completion.id}${completion.type === 'directory' ? '/' : ' '}`
  return {
    value: `${input.slice(0, query.start)}${replacement}${input.slice(query.end)}`,
    cursorOffset: query.start + replacement.length,
  }
}

function extractCompletionToken(
  text: string,
  cursorPos: number,
  includeAtSymbol = false,
): CompletionToken | null {
  if (!text) {
    return null
  }

  const textBeforeCursor = text.substring(0, cursorPos)

  if (includeAtSymbol) {
    const quotedMatch = textBeforeCursor.match(/@"([^"]*)"?$/)
    if (quotedMatch && quotedMatch.index !== undefined) {
      const textAfterCursor = text.substring(cursorPos)
      const afterQuotedMatch = textAfterCursor.match(/^[^"]*"?/)
      const quotedSuffix = afterQuotedMatch ? afterQuotedMatch[0] : ''
      return {
        token: quotedMatch[0] + quotedSuffix,
        startPos: quotedMatch.index,
        isQuoted: true,
      }
    }
  }

  if (includeAtSymbol) {
    const atIndex = textBeforeCursor.lastIndexOf('@')
    if (atIndex >= 0 && (atIndex === 0 || /\s/.test(textBeforeCursor[atIndex - 1]!))) {
      const fromAt = textBeforeCursor.substring(atIndex)
      const atHeadMatch = fromAt.match(AT_TOKEN_HEAD_RE)
      if (atHeadMatch && atHeadMatch[0].length === fromAt.length) {
        const textAfterCursor = text.substring(cursorPos)
        const afterMatch = textAfterCursor.match(PATH_CHAR_HEAD_RE)
        const tokenSuffix = afterMatch ? afterMatch[0] : ''
        return {
          token: atHeadMatch[0] + tokenSuffix,
          startPos: atIndex,
        }
      }
    }
  }

  const match = textBeforeCursor.match(TOKEN_WITH_AT_RE)
  if (!match || match.index === undefined) {
    return null
  }

  const textAfterCursor = text.substring(cursorPos)
  const afterMatch = textAfterCursor.match(PATH_CHAR_HEAD_RE)
  const tokenSuffix = afterMatch ? afterMatch[0] : ''
  return {
    token: match[0] + tokenSuffix,
    startPos: match.index,
  }
}

function extractSearchToken(completionToken: CompletionToken): string {
  if (completionToken.isQuoted) {
    return completionToken.token.slice(2).replace(/"$/, '')
  }
  if (completionToken.token.startsWith('@')) {
    return completionToken.token.substring(1)
  }
  return completionToken.token
}

function formatFileReferenceValue(
  displayText: string,
  query: Pick<FileReferenceQuery, 'hasAtPrefix' | 'isQuoted'>,
  isComplete: boolean,
): string {
  const space = isComplete ? ' ' : ''
  const needsQuotes = displayText.includes(' ')
  if (query.isQuoted || needsQuotes) {
    return `@"${displayText}"${space}`
  }
  if (query.hasAtPrefix) {
    return `@${displayText}${space}`
  }
  return `${displayText}${space}`
}

function getEffectiveTokenLength(query: Pick<FileReferenceQuery, 'token' | 'isQuoted' | 'hasAtPrefix'>): number {
  if (query.isQuoted) {
    return query.token.slice(2).replace(/"$/, '').length
  }
  if (query.hasAtPrefix) {
    return query.token.length - 1
  }
  return query.token.length
}

function findLongestCommonPrefix(values: string[]): string {
  if (values.length === 0) {
    return ''
  }

  let prefix = values[0]!
  for (let index = 1; index < values.length; index += 1) {
    const current = values[index]!
    let offset = 0
    while (offset < prefix.length && offset < current.length && prefix[offset] === current[offset]) {
      offset += 1
    }
    prefix = prefix.slice(0, offset)
    if (prefix.length === 0) {
      return ''
    }
  }
  return prefix
}

function isPathLikeToken(token: string): boolean {
  return (
    token.startsWith('~/')
    || token.startsWith('/')
    || token.startsWith('./')
    || token.startsWith('../')
    || token === '~'
    || token === '.'
    || token === '..'
  )
}

function getPathCompletions(partialPath: string, maxResults = 10): PathCompletion[] {
  const { directory, prefix } = parsePartialPath(partialPath)
  const entries = scanDirectoryForPaths(directory)
  const prefixLower = prefix.toLowerCase()
  const matches = entries
    .filter((entry) => entry.name.toLowerCase().startsWith(prefixLower))
    .slice(0, maxResults)

  let dirPortion = ''
  if (partialPath.includes('/') || partialPath.includes(path.sep)) {
    const lastSlash = partialPath.lastIndexOf('/')
    const lastSep = partialPath.lastIndexOf(path.sep)
    const lastSeparator = Math.max(lastSlash, lastSep)
    dirPortion = partialPath.slice(0, lastSeparator + 1)
  }
  if (dirPortion.startsWith('./') || dirPortion.startsWith(`.${path.sep}`)) {
    dirPortion = dirPortion.slice(2)
  }

  return matches.map((entry) => {
    const fullPath = dirPortion + entry.name
    return {
      id: fullPath,
      displayText: entry.type === 'directory' ? `${fullPath}/` : fullPath,
      type: entry.type,
    }
  })
}

function parsePartialPath(partialPath: string): { directory: string; prefix: string } {
  if (!partialPath) {
    return { directory: process.cwd(), prefix: '' }
  }

  const resolved = expandPath(partialPath)
  if (partialPath.endsWith('/') || partialPath.endsWith(path.sep)) {
    return { directory: resolved, prefix: '' }
  }

  return {
    directory: path.dirname(resolved),
    prefix: path.basename(partialPath),
  }
}

function expandPath(partialPath: string): string {
  if (partialPath === '~') {
    return os.homedir()
  }
  if (partialPath.startsWith('~/')) {
    return path.join(os.homedir(), partialPath.slice(2))
  }
  if (path.isAbsolute(partialPath)) {
    return partialPath
  }
  return path.resolve(process.cwd(), partialPath)
}

function scanDirectoryForPaths(directory: string): Array<{ name: string; type: 'directory' | 'file' }> {
  try {
    return readdirSync(directory, { withFileTypes: true })
      .filter((entry) => !entry.name.startsWith('.'))
      .map((entry) => ({
        name: entry.name,
        type: entry.isDirectory() ? ('directory' as const) : ('file' as const),
      }))
      .sort((left, right) => {
        if (left.type === 'directory' && right.type !== 'directory') {
          return -1
        }
        if (left.type !== 'directory' && right.type === 'directory') {
          return 1
        }
        return left.name.localeCompare(right.name)
      })
      .slice(0, 100)
  } catch {
    return []
  }
}
