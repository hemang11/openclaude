#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
PACKAGE_NAME="${2:-@hemang_123/openclaude}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version> [package-name]" >&2
  exit 1
fi

PACKAGE_DIR="$ROOT_DIR/dist/npm/package"

if [[ ! -f "$PACKAGE_DIR/package.json" ]]; then
  echo "Missing staged npm package. Run ./scripts/package-npm-release.sh $VERSION first." >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required to publish the package." >&2
  exit 1
fi

echo "==> npm whoami"
(cd "$PACKAGE_DIR" && npm whoami)

echo "==> Publishing $PACKAGE_NAME@$VERSION"
(cd "$PACKAGE_DIR" && npm publish --access public)
