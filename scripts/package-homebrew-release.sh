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

DIST_DIR="$ROOT_DIR/dist"
ARTIFACT_BASENAME="openclaude-${VERSION}-${PLATFORM}"
STAGE_DIR="$DIST_DIR/$ARTIFACT_BASENAME"
TARBALL="$DIST_DIR/${ARTIFACT_BASENAME}.tar.gz"
FORMULA_DIR="$ROOT_DIR/Formula"
FORMULA_PATH="$FORMULA_DIR/openclaude.rb"
RELEASE_URL="https://github.com/${REPO_SLUG}/releases/download/v${VERSION}/${ARTIFACT_BASENAME}.tar.gz"

rm -rf "$STAGE_DIR" "$TARBALL"
mkdir -p "$STAGE_DIR/bin" "$STAGE_DIR/libexec" "$FORMULA_DIR"

export OPENCLAUDE_VERSION="$VERSION"

echo "==> Building release binaries"
(cd "$ROOT_DIR" && ./build.sh)

echo "==> Staging backend"
cp -R "$ROOT_DIR/app-cli/build/install/openclaude" "$STAGE_DIR/libexec/backend"

echo "==> Staging Ink UI runtime"
mkdir -p "$STAGE_DIR/libexec/ui-ink" "$STAGE_DIR/libexec/types"
cp "$ROOT_DIR/ui-ink/package.json" "$ROOT_DIR/ui-ink/package-lock.json" "$ROOT_DIR/ui-ink/tsconfig.json" "$STAGE_DIR/libexec/ui-ink/"
cp -R "$ROOT_DIR/ui-ink/src" "$STAGE_DIR/libexec/ui-ink/src"
cp -R "$ROOT_DIR/ui-ink/node_modules" "$STAGE_DIR/libexec/ui-ink/node_modules"
cp -R "$ROOT_DIR/types/stdio" "$STAGE_DIR/libexec/types/stdio"
find "$STAGE_DIR/libexec/ui-ink/src" -type f \( -name '*.test.ts' -o -name '*.test.tsx' \) -delete
rm -rf "$STAGE_DIR/libexec/ui-ink/src/test"

(
  cd "$STAGE_DIR/libexec/ui-ink"
  npm prune --omit=dev --ignore-scripts
)

cat > "$STAGE_DIR/bin/openclaude" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export OPENCLAUDE_BACKEND_BIN="${OPENCLAUDE_BACKEND_BIN:-$ROOT_DIR/libexec/backend/bin/openclaude}"
export OPENCLAUDE_BACKEND_ARGS="${OPENCLAUDE_BACKEND_ARGS:-stdio}"

cd "$ROOT_DIR/libexec/ui-ink"
exec node --import tsx src/index.tsx "$@"
EOF

cat > "$STAGE_DIR/bin/openclaude-backend" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$ROOT_DIR/libexec/backend/bin/openclaude" "$@"
EOF

chmod +x "$STAGE_DIR/bin/openclaude" "$STAGE_DIR/bin/openclaude-backend"

echo "==> Creating tarball"
(
  cd "$DIST_DIR"
  tar -czf "$(basename "$TARBALL")" "$(basename "$STAGE_DIR")"
)

SHA256="$(shasum -a 256 "$TARBALL" | awk '{print $1}')"

echo "==> Generating Homebrew formula"
"$ROOT_DIR/scripts/generate-homebrew-formula.sh" \
  --version "$VERSION" \
  --sha256 "$SHA256" \
  --url "$RELEASE_URL" \
  > "$FORMULA_PATH"

cat > "$DIST_DIR/RELEASE.txt" <<EOF
Artifact: $TARBALL
SHA256:   $SHA256
Formula:  $FORMULA_PATH
Release URL:
  $RELEASE_URL
EOF

echo
cat "$DIST_DIR/RELEASE.txt"
