import assert from 'node:assert/strict'
import test from 'node:test'

import { resolveBackendLaunchConfig } from './backendLaunch.ts'

test('resolveBackendLaunchConfig prefers explicit env override', () => {
  const config = resolveBackendLaunchConfig(
    {
      OPENCLAUDE_BACKEND_BIN: '/tmp/custom-openclaude',
      OPENCLAUDE_BACKEND_ARGS: 'stdio --verbose',
      OPENCLAUDE_JAVA_HOME: '/tmp/jdk-24',
    },
    'file:///Users/test/openclaude/ui-ink/src/ipc/backendLaunch.ts',
    () => true,
    () => '/ignored/jdk',
    () => true,
  )

  assert.equal(config.command, '/tmp/custom-openclaude')
  assert.deepEqual(config.args, ['stdio', '--verbose'])
  assert.equal(config.env.JAVA_HOME, '/tmp/jdk-24')
  assert.equal(config.env.PATH?.startsWith('/tmp/jdk-24/bin'), true)
  assert.equal(config.env.OPENCLAUDE_HOME, undefined)
})

test('resolveBackendLaunchConfig prefers local packaged backend in repo dev mode', () => {
  const expected = '/Users/test/openclaude/app-cli/build/install/openclaude/bin/openclaude'
  const config = resolveBackendLaunchConfig(
    {},
    'file:///Users/test/openclaude/ui-ink/src/ipc/backendLaunch.ts',
    (candidate) => candidate === expected,
    () => '/opt/jdk-24',
    () => true,
  )

  assert.equal(config.command, expected)
  assert.deepEqual(config.args, ['stdio'])
  assert.equal(config.env.JAVA_HOME, '/opt/jdk-24')
  assert.equal(config.env.OPENCLAUDE_HOME, '/Users/test/openclaude/.tmp-openclaude-home')
})

test('resolveBackendLaunchConfig falls back to openclaude on PATH', () => {
  const config = resolveBackendLaunchConfig(
    {},
    'file:///Users/test/openclaude/ui-ink/src/ipc/backendLaunch.ts',
    () => false,
    () => undefined,
    () => false,
  )

  assert.equal(config.command, 'openclaude')
  assert.deepEqual(config.args, ['stdio'])
  assert.deepEqual(config.env, {})
})

test('resolveBackendLaunchConfig preserves existing JAVA_HOME when present', () => {
  const config = resolveBackendLaunchConfig(
    {
      JAVA_HOME: '/already/set/jdk',
      PATH: '/usr/bin',
    },
    'file:///Users/test/openclaude/ui-ink/src/ipc/backendLaunch.ts',
    () => false,
    () => '/ignored/jdk',
    () => true,
  )

  assert.equal(config.env.JAVA_HOME, '/already/set/jdk')
  assert.equal(config.env.PATH, '/already/set/jdk/bin:/usr/bin')
  assert.equal(config.env.OPENCLAUDE_HOME, undefined)
})

test('resolveBackendLaunchConfig replaces incompatible JAVA_HOME with a detected Java 21+ runtime', () => {
  const config = resolveBackendLaunchConfig(
    {
      JAVA_HOME: '/old/jdk-8',
      PATH: '/usr/bin',
    },
    'file:///Users/test/openclaude/ui-ink/src/ipc/backendLaunch.ts',
    () => false,
    () => '/detected/jdk-24',
    () => false,
  )

  assert.equal(config.env.JAVA_HOME, '/detected/jdk-24')
  assert.equal(config.env.PATH, '/detected/jdk-24/bin:/usr/bin')
  assert.equal(config.env.OPENCLAUDE_HOME, undefined)
})

test('resolveBackendLaunchConfig preserves explicit OPENCLAUDE_HOME when present', () => {
  const expected = '/Users/test/openclaude/app-cli/build/install/openclaude/bin/openclaude'
  const config = resolveBackendLaunchConfig(
    {
      OPENCLAUDE_HOME: '/tmp/custom-openclaude-home',
    },
    'file:///Users/test/openclaude/ui-ink/src/ipc/backendLaunch.ts',
    (candidate) => candidate === expected,
    () => '/opt/jdk-24',
    () => true,
  )

  assert.equal(config.env.OPENCLAUDE_HOME, '/tmp/custom-openclaude-home')
})
