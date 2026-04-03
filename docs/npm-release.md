# NPM Release Flow

OpenClaude can be published as an npm CLI package that bundles:

- the packaged Java backend
- the Ink UI source
- the shared stdio type contract

The npm package installs Node runtime dependencies from the npm registry and exposes:

- `openclaude`
- `openclaude-backend`

## Build The Package

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
./scripts/package-npm-release.sh 0.1.0
```

Outputs:

- `dist/npm/package`
- `dist/npm/package/hemang11-openclaude-0.1.0.tgz`

## Publish

```bash
cd /Users/hshrimali-mbp/Desktop/claude-code-java/openclaude
./scripts/publish-npm-release.sh 0.1.0
```

## Install

```bash
npm install -g @hemang_123/openclaude
```

## Runtime Requirements

- Node.js 20+
- Java 21+
