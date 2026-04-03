#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
PACKAGE_NAME="${2:-@hemang_123/openclaude}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version> [package-name]" >&2
  exit 1
fi

DIST_DIR="$ROOT_DIR/dist/npm"
STAGE_DIR="$DIST_DIR/package"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/bin" "$STAGE_DIR/vendor"

export OPENCLAUDE_VERSION="$VERSION"

echo "==> Building release binaries"
(cd "$ROOT_DIR" && ./build.sh)

echo "==> Staging backend"
cp -R "$ROOT_DIR/app-cli/build/install/openclaude" "$STAGE_DIR/vendor/backend"

echo "==> Staging Ink UI sources"
mkdir -p "$STAGE_DIR/vendor/ui-ink" "$STAGE_DIR/vendor/types"
cp "$ROOT_DIR/ui-ink/tsconfig.json" "$STAGE_DIR/vendor/ui-ink/"
cp -R "$ROOT_DIR/ui-ink/src" "$STAGE_DIR/vendor/ui-ink/src"
cp -R "$ROOT_DIR/types/stdio" "$STAGE_DIR/vendor/types/stdio"
find "$STAGE_DIR/vendor/ui-ink/src" -type f \( -name '*.test.ts' -o -name '*.test.tsx' \) -delete
rm -rf "$STAGE_DIR/vendor/ui-ink/src/test"

cat > "$STAGE_DIR/package.json" <<EOF
{
  "name": "$PACKAGE_NAME",
  "version": "$VERSION",
  "private": false,
  "type": "module",
  "description": "Java + Ink terminal coding agent with a typed stdio backend",
  "bin": {
    "openclaude": "./bin/openclaude.js",
    "openclaude-backend": "./bin/openclaude-backend.js"
  },
  "files": [
    "bin",
    "vendor",
    "README.md"
  ],
  "publishConfig": {
    "access": "public"
  },
  "repository": {
    "type": "git",
    "url": "git+https://github.com/hemang11/openclaude.git"
  },
  "homepage": "https://github.com/hemang11/openclaude",
  "bugs": {
    "url": "https://github.com/hemang11/openclaude/issues"
  },
  "license": "UNLICENSED",
  "engines": {
    "node": ">=20"
  },
  "dependencies": {
    "ink": "^5.1.0",
    "marked": "^17.0.5",
    "react": "^18.3.1",
    "strip-ansi": "^7.2.0",
    "tsx": "^4.19.3"
  },
  "keywords": [
    "ai",
    "cli",
    "terminal",
    "coding-agent",
    "ink",
    "java"
  ]
}
EOF

cat > "$STAGE_DIR/README.md" <<'EOF'
# OpenClaude

OpenClaude is a Java-backed terminal coding agent with an Ink UI and a typed stdio backend.

## Install

```bash
npm install -g PACKAGE_NAME_PLACEHOLDER
```

## Requirements

- Node.js 20+
- Java 21+

## Commands

- `openclaude`: launch the Ink UI
- `openclaude-backend`: run the packaged Java backend directly

## State

OpenClaude stores state under `~/.openclaude` by default.
Set `OPENCLAUDE_HOME` to isolate local state if needed.
EOF

python3 - <<'PY' "$STAGE_DIR/README.md" "$PACKAGE_NAME"
from pathlib import Path
import sys

readme = Path(sys.argv[1])
package_name = sys.argv[2]
readme.write_text(readme.read_text().replace("PACKAGE_NAME_PLACEHOLDER", package_name))
PY

cat > "$STAGE_DIR/bin/openclaude.js" <<'EOF'
#!/usr/bin/env node
import { existsSync } from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const backendBin = resolveBackendBinary(packageRoot)
const uiEntry = path.join(packageRoot, 'vendor', 'ui-ink', 'src', 'index.tsx')
const require = createRequire(import.meta.url)
const tsxLoader = pathToFileURL(require.resolve('tsx')).href

const env = buildEnvironment(process.env, backendBin)
const child = spawn(process.execPath, ['--import', tsxLoader, uiEntry, ...process.argv.slice(2)], {
  stdio: 'inherit',
  env,
})

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal)
    return
  }
  process.exit(code ?? 0)
})

function resolveBackendBinary(root) {
  const win = path.join(root, 'vendor', 'backend', 'bin', 'openclaude.bat')
  const unix = path.join(root, 'vendor', 'backend', 'bin', 'openclaude')
  return process.platform === 'win32' ? win : unix
}

function buildEnvironment(env, backendPath) {
  const explicitJavaHome = env.OPENCLAUDE_JAVA_HOME?.trim()
  const inheritedJavaHome = env.JAVA_HOME?.trim()
  const javaHome = explicitJavaHome
    || (inheritedJavaHome && isCompatibleJavaHome(inheritedJavaHome) ? inheritedJavaHome : undefined)
    || detectBackendJavaHome()

  const nextEnv = { ...env }
  if (!nextEnv.OPENCLAUDE_BACKEND_BIN && existsSync(backendPath)) {
    nextEnv.OPENCLAUDE_BACKEND_BIN = backendPath
  }
  if (!nextEnv.OPENCLAUDE_BACKEND_ARGS) {
    nextEnv.OPENCLAUDE_BACKEND_ARGS = 'stdio'
  }
  return attachJavaHome(nextEnv, javaHome)
}

function detectBackendJavaHome() {
  if (process.platform !== 'darwin') {
    return undefined
  }
  for (const version of ['24', '23', '22', '21']) {
    const result = spawnSync('/usr/libexec/java_home', ['-v', version], { encoding: 'utf8' })
    if (result.status === 0) {
      const candidate = result.stdout.trim()
      if (candidate) {
        return candidate
      }
    }
  }
  return undefined
}

function isCompatibleJavaHome(javaHome) {
  const javaBinary = path.join(javaHome, 'bin', 'java')
  const result = spawnSync(javaBinary, ['-version'], { encoding: 'utf8' })
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

function attachJavaHome(env, javaHome) {
  if (!javaHome) {
    return env
  }
  const javaBin = path.join(javaHome, 'bin')
  const existingPath = env.PATH || ''
  return {
    ...env,
    JAVA_HOME: javaHome,
    PATH: existingPath.startsWith(`${javaBin}:`) || existingPath === javaBin
      ? existingPath
      : existingPath
        ? `${javaBin}:${existingPath}`
        : javaBin,
  }
}
EOF

cat > "$STAGE_DIR/bin/openclaude-backend.js" <<'EOF'
#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const backendBin = process.platform === 'win32'
  ? path.join(packageRoot, 'vendor', 'backend', 'bin', 'openclaude.bat')
  : path.join(packageRoot, 'vendor', 'backend', 'bin', 'openclaude')

const env = attachJavaHome(process.env, detectJavaHome(process.env))
const child = spawn(backendBin, process.argv.slice(2), {
  stdio: 'inherit',
  env,
})

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal)
    return
  }
  process.exit(code ?? 0)
})

function detectJavaHome(env) {
  const explicitJavaHome = env.OPENCLAUDE_JAVA_HOME?.trim()
  const inheritedJavaHome = env.JAVA_HOME?.trim()
  if (explicitJavaHome && isCompatibleJavaHome(explicitJavaHome)) {
    return explicitJavaHome
  }
  if (inheritedJavaHome && isCompatibleJavaHome(inheritedJavaHome)) {
    return inheritedJavaHome
  }
  if (process.platform !== 'darwin') {
    return undefined
  }
  for (const version of ['24', '23', '22', '21']) {
    const result = spawnSync('/usr/libexec/java_home', ['-v', version], { encoding: 'utf8' })
    if (result.status === 0) {
      const candidate = result.stdout.trim()
      if (candidate) {
        return candidate
      }
    }
  }
  return undefined
}

function isCompatibleJavaHome(javaHome) {
  const javaBinary = path.join(javaHome, 'bin', 'java')
  const result = spawnSync(javaBinary, ['-version'], { encoding: 'utf8' })
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

function attachJavaHome(env, javaHome) {
  if (!javaHome) {
    return { ...env }
  }
  const javaBin = path.join(javaHome, 'bin')
  const existingPath = env.PATH || ''
  return {
    ...env,
    JAVA_HOME: javaHome,
    PATH: existingPath.startsWith(`${javaBin}:`) || existingPath === javaBin
      ? existingPath
      : existingPath
        ? `${javaBin}:${existingPath}`
        : javaBin,
  }
}
EOF

chmod +x "$STAGE_DIR/bin/openclaude.js" "$STAGE_DIR/bin/openclaude-backend.js"

echo "==> Creating npm tarball"
(
  cd "$STAGE_DIR"
  npm pack
)

TARBALL_NAME="$(basename "$(find "$STAGE_DIR" -maxdepth 1 -type f -name '*.tgz' | head -n 1)")"

echo
echo "NPM package directory:"
echo "  $STAGE_DIR"
echo "NPM tarball:"
echo "  $STAGE_DIR/$TARBALL_NAME"
