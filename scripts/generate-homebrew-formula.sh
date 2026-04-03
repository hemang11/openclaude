#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage: generate-homebrew-formula.sh --version <version> --sha256 <sha256> --url <asset-url> [--homepage <homepage>]
EOF
  exit 1
}

version=""
sha256=""
url=""
homepage="https://github.com/hemang11/openclaude"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      version="${2:-}"
      shift 2
      ;;
    --sha256)
      sha256="${2:-}"
      shift 2
      ;;
    --url)
      url="${2:-}"
      shift 2
      ;;
    --homepage)
      homepage="${2:-}"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

[[ -n "$version" && -n "$sha256" && -n "$url" ]] || usage

cat <<EOF
class Openclaude < Formula
  desc "Java + Ink terminal coding agent with a typed stdio backend"
  homepage "$homepage"
  url "$url"
  sha256 "$sha256"
  version "$version"
  license :cannot_represent

  depends_on "node"
  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    env = Language::Java.overridable_java_home_env("21")
    bin.write_env_script libexec/"bin/openclaude", env
    bin.write_env_script libexec/"bin/openclaude-backend", env
  end

  def caveats
    <<~EOS
      OpenClaude stores state under ~/.openclaude by default.
      Set OPENCLAUDE_HOME to isolate local state if needed.

      The \`openclaude\` command launches the Ink UI.
      The \`openclaude-backend\` command runs the packaged Java backend directly.
    EOS
  end

  test do
    assert_match "OpenClaude Java CLI bootstrap", shell_output("#{bin}/openclaude-backend --help")
  end
end
EOF
