import { mkdtemp, readFile, writeFile, mkdir, access } from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

export function resolveOpenClaudeHome(): string {
  return process.env.OPENCLAUDE_HOME || path.join(os.homedir(), '.openclaude')
}

export async function copyToClipboard(text: string): Promise<void> {
  const platform = process.platform
  if (platform === 'darwin') {
    runSync('pbcopy', [], text)
    return
  }
  if (platform === 'win32') {
    runSync('clip', [], text)
    return
  }

  const candidates: Array<[string, string[]]> = [
    ['wl-copy', []],
    ['xclip', ['-selection', 'clipboard']],
    ['xsel', ['--clipboard', '--input']],
  ]
  for (const [command, args] of candidates) {
    const result = spawnSync(command, args, {
      input: text,
      encoding: 'utf8',
    })
    if (result.status === 0) {
      return
    }
  }
  throw new Error('No clipboard command found. Install wl-copy, xclip, or xsel.')
}

export async function editTextInExternalEditor(initialValue: string): Promise<string> {
  const tempDir = await mkdtemp(path.join(os.tmpdir(), 'openclaude-editor-'))
  const tempFile = path.join(tempDir, 'prompt.txt')
  await writeFile(tempFile, initialValue, 'utf8')

  const editorSpec = process.env.OPENCLAUDE_EDITOR || process.env.VISUAL || process.env.EDITOR || 'vi'
  const [editor, ...editorArgs] = editorSpec.split(' ').filter(Boolean)
  const result = spawnSync(editor, [...editorArgs, tempFile], {
    stdio: 'inherit',
  })
  if (result.status !== 0) {
    throw new Error(`Editor exited with status ${result.status ?? 'unknown'}.`)
  }
  return readFile(tempFile, 'utf8')
}

export async function editFileInExternalEditor(filePath: string): Promise<void> {
  await mkdir(path.dirname(filePath), { recursive: true })
  const exists = await fileExists(filePath)
  if (!exists) {
    await writeFile(filePath, '', 'utf8')
  }

  const editorSpec = process.env.OPENCLAUDE_EDITOR || process.env.VISUAL || process.env.EDITOR || 'vi'
  const [editor, ...editorArgs] = editorSpec.split(' ').filter(Boolean)
  const result = spawnSync(editor, [...editorArgs, filePath], {
    stdio: 'inherit',
  })
  if (result.status !== 0) {
    throw new Error(`Editor exited with status ${result.status ?? 'unknown'}.`)
  }
}

export async function openOrCreateKeybindingsFile(content: string): Promise<string> {
  const home = resolveOpenClaudeHome()
  const keybindingsFile = path.join(home, 'keybindings.json')
  await mkdir(path.dirname(keybindingsFile), { recursive: true })

  const exists = await fileExists(keybindingsFile)
  if (!exists) {
    await writeFile(keybindingsFile, content, 'utf8')
  }

  openFileInSystemEditor(keybindingsFile)
  return keybindingsFile
}

export async function listWorkspaceFiles(limit = 4000): Promise<string[]> {
  const rgResult = spawnSync('rg', ['--files'], {
    cwd: process.cwd(),
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
  })
  if (rgResult.status === 0) {
    return rgResult.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .slice(0, limit)
  }

  const findResult = spawnSync('find', ['.', '-type', 'f'], {
    cwd: process.cwd(),
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
  })
  if (findResult.status === 0) {
    return findResult.stdout
      .split(/\r?\n/)
      .map((line) => line.trim().replace(/^\.\//, ''))
      .filter(Boolean)
      .slice(0, limit)
  }

  return []
}

function openFileInSystemEditor(filePath: string): void {
  const platform = process.platform
  if (platform === 'darwin') {
    spawnSync('open', ['-t', filePath], { stdio: 'ignore' })
    return
  }
  if (platform === 'win32') {
    spawnSync('cmd', ['/c', 'start', '', filePath], { stdio: 'ignore' })
    return
  }
  spawnSync('xdg-open', [filePath], { stdio: 'ignore' })
}

function runSync(command: string, args: string[], input: string): void {
  const result = spawnSync(command, args, {
    input,
    encoding: 'utf8',
  })
  if (result.status !== 0) {
    const message = result.stderr || result.stdout || `Command failed: ${command}`
    throw new Error(message.trim())
  }
}

async function fileExists(filePath: string): Promise<boolean> {
  try {
    await access(filePath, fsConstants.F_OK)
    return true
  } catch {
    return false
  }
}
