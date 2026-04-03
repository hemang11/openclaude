import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

import { listMemoryFiles } from './memory.ts'

test('listMemoryFiles includes user, project, local, and rule-backed AGENTS paths', async () => {
  const tempRoot = await mkdtemp(path.join(os.tmpdir(), 'openclaude-memory-'))
  const home = path.join(tempRoot, 'home')
  const workspace = path.join(tempRoot, 'workspace')
  const nested = path.join(workspace, 'packages', 'ui')

  await mkdir(path.join(home, 'rules'), { recursive: true })
  await mkdir(path.join(workspace, '.git'), { recursive: true })
  await mkdir(path.join(workspace, '.openclaude', 'rules'), { recursive: true })
  await mkdir(nested, { recursive: true })

  await writeFile(path.join(home, 'AGENTS.md'), 'user memory', 'utf8')
  await writeFile(path.join(home, 'rules', 'global.md'), 'global rule', 'utf8')
  await writeFile(path.join(workspace, 'AGENTS.md'), 'project memory', 'utf8')
  await writeFile(path.join(workspace, 'AGENTS.local.md'), 'local memory', 'utf8')
  await writeFile(path.join(workspace, '.openclaude', 'rules', 'repo.md'), 'repo rule', 'utf8')

  const originalHome = process.env.OPENCLAUDE_HOME
  process.env.OPENCLAUDE_HOME = home

  try {
    const files = await listMemoryFiles(nested)
    const labels = files.map((file) => file.label)
    const paths = files.map((file) => file.displayPath)

    assert.equal(labels.includes('User memory'), true)
    assert.equal(labels.includes('Project memory'), true)
    assert.equal(labels.includes('Local memory'), true)
    assert.equal(paths.some((displayPath) => displayPath.endsWith('.openclaude/rules/repo.md')), true)
  } finally {
    if (originalHome === undefined) {
      delete process.env.OPENCLAUDE_HOME
    } else {
      process.env.OPENCLAUDE_HOME = originalHome
    }
  }
})
