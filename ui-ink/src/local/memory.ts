import { access, readdir } from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import path from 'node:path'

import { resolveOpenClaudeHome } from './system.ts'

export type MemoryFileEntry = {
  path: string
  displayPath: string
  label: string
  detail: string
  exists: boolean
}

export async function listMemoryFiles(cwd = process.cwd()): Promise<MemoryFileEntry[]> {
  const home = resolveOpenClaudeHome()
  const workingDirectory = path.resolve(cwd)
  const workspaceRoot = await resolveWorkspaceRoot(workingDirectory)
  const seen = new Set<string>()
  const entries: MemoryFileEntry[] = []

  const userAgentsPath = path.join(home, 'AGENTS.md')
  await pushEntry(entries, seen, userAgentsPath, 'User memory', `Saved in ${displayPath(userAgentsPath, workingDirectory)}`, false, workingDirectory)

  const userRules = await listRuleFiles(path.join(home, 'rules'))
  for (const ruleFile of userRules) {
    await pushEntry(entries, seen, ruleFile, displayPath(ruleFile, workingDirectory), 'User memory rule', true, workingDirectory)
  }

  const ancestorDirectories = directoriesFromWorkspaceToCwd(workspaceRoot, workingDirectory)
  for (const directory of ancestorDirectories) {
    const isWorkspaceRoot = path.resolve(directory) === path.resolve(workspaceRoot)
    const isWorkingDirectory = path.resolve(directory) === workingDirectory

    const projectAgentsPath = path.join(directory, 'AGENTS.md')
    await pushEntry(
      entries,
      seen,
      projectAgentsPath,
      isWorkspaceRoot ? 'Project memory' : displayPath(projectAgentsPath, workingDirectory),
      isWorkspaceRoot ? 'Checked in at ./AGENTS.md' : 'Project instruction file',
      isWorkspaceRoot ? false : true,
      workingDirectory,
    )

    const hiddenAgentsPath = path.join(directory, '.openclaude', 'AGENTS.md')
    await pushEntry(
      entries,
      seen,
      hiddenAgentsPath,
      displayPath(hiddenAgentsPath, workingDirectory),
      '.openclaude instruction file',
      true,
      workingDirectory,
    )

    const localAgentsPath = path.join(directory, 'AGENTS.local.md')
    await pushEntry(
      entries,
      seen,
      localAgentsPath,
      isWorkingDirectory ? 'Local memory' : displayPath(localAgentsPath, workingDirectory),
      isWorkingDirectory ? 'Local overrides for this workspace' : 'Local overrides',
      isWorkingDirectory ? false : true,
      workingDirectory,
    )

    const ruleFiles = await listRuleFiles(path.join(directory, '.openclaude', 'rules'))
    for (const ruleFile of ruleFiles) {
      await pushEntry(entries, seen, ruleFile, displayPath(ruleFile, workingDirectory), '.openclaude rule', true, workingDirectory)
    }
  }

  return entries
}

async function resolveWorkspaceRoot(cwd: string): Promise<string> {
  let probe = cwd
  for (;;) {
    if (await exists(path.join(probe, '.git'))) {
      return probe
    }
    const parent = path.dirname(probe)
    if (parent === probe) {
      return cwd
    }
    probe = parent
  }
}

function directoriesFromWorkspaceToCwd(workspaceRoot: string, cwd: string): string[] {
  const directories: string[] = []
  let probe = cwd
  for (;;) {
    directories.push(probe)
    if (path.resolve(probe) === path.resolve(workspaceRoot)) {
      break
    }
    const parent = path.dirname(probe)
    if (parent === probe) {
      break
    }
    probe = parent
  }
  return directories.reverse()
}

async function listRuleFiles(rulesDirectory: string): Promise<string[]> {
  if (!(await exists(rulesDirectory))) {
    return []
  }

  const entries = await readdir(rulesDirectory, { withFileTypes: true })
  return entries
    .filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.md'))
    .map((entry) => path.join(rulesDirectory, entry.name))
    .sort((left, right) => left.localeCompare(right))
}

async function exists(targetPath: string): Promise<boolean> {
  try {
    await access(targetPath, fsConstants.F_OK)
    return true
  } catch {
    return false
  }
}

async function pushEntry(
  entries: MemoryFileEntry[],
  seen: Set<string>,
  filePath: string,
  label: string,
  detail: string,
  onlyIfExists: boolean,
  cwd: string,
): Promise<void> {
  const normalizedPath = path.resolve(filePath)
  if (seen.has(normalizedPath)) {
    return
  }
  const fileExists = await exists(normalizedPath)
  if (onlyIfExists && !fileExists) {
    return
  }
  seen.add(normalizedPath)
  entries.push({
    path: normalizedPath,
    displayPath: displayPath(normalizedPath, cwd),
    label,
    detail,
    exists: fileExists,
  })
}

function displayPath(targetPath: string, cwd: string): string {
  const normalized = path.resolve(targetPath)
  const homeDir = process.env.HOME
  if (homeDir && normalized.startsWith(homeDir)) {
    return `~${normalized.slice(homeDir.length)}`
  }
  const relativeToCwd = path.relative(cwd, normalized)
  if (relativeToCwd && !relativeToCwd.startsWith('..')) {
    return `./${relativeToCwd}`
  }
  return normalized
}
