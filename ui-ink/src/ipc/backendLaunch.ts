import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export type BackendLaunchConfig = {
  command: string
  args: string[]
  env: NodeJS.ProcessEnv
}

export function resolveBackendLaunchConfig(
  env: NodeJS.ProcessEnv = process.env,
  moduleUrl: string = import.meta.url,
  fileExists: (candidate: string) => boolean = existsSync,
  resolveJavaHome: (env: NodeJS.ProcessEnv) => string | undefined = detectBackendJavaHome,
  isCompatibleJavaHome: (candidate: string) => boolean = hasSupportedJavaRuntime,
): BackendLaunchConfig {
  const repoRoot = path.resolve(
    path.dirname(fileURLToPath(moduleUrl)),
    '../../..',
  )
  const packagedBackend = path.resolve(
    repoRoot,
    'app-cli/build/install/openclaude/bin/openclaude',
  )
  const explicitJavaHome = env.OPENCLAUDE_JAVA_HOME?.trim()
  const inheritedJavaHome = env.JAVA_HOME?.trim()
  const javaHome = explicitJavaHome
    || (inheritedJavaHome && isCompatibleJavaHome(inheritedJavaHome) ? inheritedJavaHome : undefined)
    || resolveJavaHome(env)
  const usingLocalPackagedBackend = !env.OPENCLAUDE_BACKEND_BIN && fileExists(packagedBackend)

  return {
    command: env.OPENCLAUDE_BACKEND_BIN || (usingLocalPackagedBackend ? packagedBackend : 'openclaude'),
    args: (env.OPENCLAUDE_BACKEND_ARGS || 'stdio').split(/\s+/).filter(Boolean),
    env: buildBackendEnvironment(
      env,
      javaHome,
      usingLocalPackagedBackend ? path.join(repoRoot, '.tmp-openclaude-home') : undefined,
    ),
  }
}

function buildBackendEnvironment(
  env: NodeJS.ProcessEnv,
  javaHome?: string,
  defaultOpenClaudeHome?: string,
): NodeJS.ProcessEnv {
  let nextEnv = env

  if (defaultOpenClaudeHome && !env.OPENCLAUDE_HOME?.trim()) {
    nextEnv = {
      ...nextEnv,
      OPENCLAUDE_HOME: defaultOpenClaudeHome,
    }
  }

  if (!javaHome) {
    return nextEnv
  }

  const existingPath = nextEnv.PATH || ''
  const javaBin = path.join(javaHome, 'bin')
  const nextPath = existingPath.startsWith(`${javaBin}:`) || existingPath === javaBin
    ? existingPath
    : existingPath
      ? `${javaBin}:${existingPath}`
      : javaBin

  return {
    ...nextEnv,
    JAVA_HOME: javaHome,
    PATH: nextPath,
  }
}

function detectBackendJavaHome(env: NodeJS.ProcessEnv): string | undefined {
  if (process.platform !== 'darwin') {
    return undefined
  }

  for (const version of ['24', '23', '22', '21']) {
    const result = spawnSync('/usr/libexec/java_home', ['-v', version], {
      encoding: 'utf8',
    })
    if (result.status === 0) {
      const candidate = result.stdout.trim()
      if (candidate) {
        return candidate
      }
    }
  }

  return undefined
}

function hasSupportedJavaRuntime(javaHome: string): boolean {
  const javaBinary = path.join(javaHome, 'bin', 'java')
  const result = spawnSync(javaBinary, ['-version'], {
    encoding: 'utf8',
  })
  if (result.status !== 0) {
    return false
  }

  const combined = `${result.stdout}\n${result.stderr}`
  const match = combined.match(/version "(.*?)"/)
  if (!match) {
    return false
  }

  const version = match[1]
  const major = version.startsWith('1.')
    ? Number(version.split('.')[1])
    : Number(version.split(/[.+-]/)[0])

  return Number.isFinite(major) && major >= 21
}
