#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$ROOT_DIR/ui-ink"

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is required but was not found on PATH." >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required but was not found on PATH." >&2
  exit 1
fi

detect_java17_home() {
  if [[ -n "${JAVA_HOME:-}" ]] && java_home_is_17 "$JAVA_HOME"; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local detected
    detected="$("/usr/libexec/java_home" -v 17 2>/dev/null || true)"
    if [[ -n "$detected" ]] && java_home_is_17 "$detected"; then
      printf '%s\n' "$detected"
      return 0
    fi
  fi

  return 1
}

java_home_is_17() {
  local home="$1"
  local java_bin="$home/bin/java"
  local spec_version

  if [[ ! -x "$java_bin" ]]; then
    return 1
  fi

  spec_version="$("$java_bin" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version = / {print $2; exit}')"
  [[ "$spec_version" == "17" ]]
}

GRADLE_JAVA_HOME="$(detect_java17_home || true)"
if [[ -z "$GRADLE_JAVA_HOME" ]]; then
  echo "Java 17 is required for the Gradle build. Set JAVA_HOME to a JDK 17 installation and retry." >&2
  exit 1
fi

export JAVA_HOME="$GRADLE_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Building Java backend with Gradle"
(cd "$ROOT_DIR" && ./gradlew :app-cli:installDist)

echo "==> Preparing ui-ink dependencies"
if [[ ! -d "$UI_DIR/node_modules" ]]; then
  (cd "$UI_DIR" && npm ci)
fi

echo "==> Typechecking ui-ink"
(cd "$UI_DIR" && npm run typecheck)

echo
echo "Build complete."
echo "Packaged backend launcher:"
echo "  $ROOT_DIR/app-cli/build/install/openclaude/bin/openclaude"
echo
echo "Run the backend launcher with a Java 21+ runtime, for example:"
echo "  JAVA_HOME=\$(/usr/libexec/java_home -v 24) $ROOT_DIR/app-cli/build/install/openclaude/bin/openclaude"
