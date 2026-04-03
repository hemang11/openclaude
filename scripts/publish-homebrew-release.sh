#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-}"
REPO_SLUG="${2:-hemang11/openclaude}"
PLATFORM="${3:-macos-universal}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version> [repo-slug] [platform]" >&2
  exit 1
fi

ARTIFACT="$ROOT_DIR/dist/openclaude-${VERSION}-${PLATFORM}.tar.gz"
FORMULA="$ROOT_DIR/Formula/openclaude.rb"
TAG="v${VERSION}"

if [[ ! -f "$ARTIFACT" ]]; then
  echo "Missing artifact: $ARTIFACT" >&2
  exit 1
fi

if [[ ! -f "$FORMULA" ]]; then
  echo "Missing formula: $FORMULA" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required to publish the release." >&2
  exit 1
fi

if gh release view "$TAG" -R "$REPO_SLUG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$ARTIFACT" -R "$REPO_SLUG" --clobber
else
  gh release create "$TAG" "$ARTIFACT" -R "$REPO_SLUG" --title "$TAG" --generate-notes
fi

echo "Release asset uploaded:"
echo "  $ARTIFACT"
echo
echo "Next:"
echo "  1. Commit Formula/openclaude.rb"
echo "  2. Push the default branch"
echo "  3. Install with:"
echo "     brew tap ${REPO_SLUG} https://github.com/${REPO_SLUG}"
echo "     brew install openclaude"
