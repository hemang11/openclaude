# Homebrew Release Flow

This repo can act as its own Homebrew tap by committing `Formula/openclaude.rb` and uploading the matching release tarball to GitHub Releases.

## What Gets Shipped

The release tarball contains:

- the packaged Java backend from `app-cli/build/install/openclaude`
- the Ink UI source under `libexec/ui-ink`
- the shared typed stdio contract under `libexec/types/stdio`
- vendored runtime `node_modules`
- two launchers:
  - `openclaude`
  - `openclaude-backend`

## Build The Artifact

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
./scripts/package-homebrew-release.sh 0.1.0
```

Outputs:

- `dist/openclaude-0.1.0-macos-universal.tar.gz`
- `dist/RELEASE.txt`
- `Formula/openclaude.rb`

## Publish To GitHub

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
./scripts/publish-homebrew-release.sh 0.1.0
```

That uploads the tarball to `v0.1.0` on GitHub using `gh`.

## Push The Tap Formula

Commit and push the generated formula on the default branch:

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
git add Formula/openclaude.rb README.md docs/homebrew-release.md scripts
git commit -m "Add Homebrew release packaging"
git push origin main
```

## Install

```bash
brew tap hemang11/openclaude https://github.com/hemang11/openclaude
brew install openclaude
```

If you want `brew install openclaude` to work without a prior tap, the formula has to live in `homebrew/core`. This repo-based flow is a custom tap, so the first `brew tap` step is expected.

## Runtime Requirements

- `node`
- `openjdk@21`

The generated formula declares both as Homebrew dependencies.
