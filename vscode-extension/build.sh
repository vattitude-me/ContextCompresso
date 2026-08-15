#!/usr/bin/env bash
# Builds the proxy jar, copies it into resources/ so it ships inside the .vsix, compiles the
# extension's TypeScript, and (if vsce is available) packages the final .vsix.
set -euo pipefail

EXT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$EXT_DIR/.." && pwd)"

echo "==> Building contextcompresso.jar..."
(cd "$REPO_ROOT" && mvn -q clean package -DskipTests)

mkdir -p "$EXT_DIR/resources"
cp "$REPO_ROOT/target/contextcompresso.jar" "$EXT_DIR/resources/contextcompresso.jar"
echo "==> Bundled jar into vscode-extension/resources/contextcompresso.jar"

echo "==> Installing extension dependencies..."
(cd "$EXT_DIR" && npm install --no-audit --no-fund)

echo "==> Compiling extension..."
(cd "$EXT_DIR" && npm run compile)

if command -v vsce >/dev/null 2>&1; then
  echo "==> Packaging .vsix..."
  (cd "$EXT_DIR" && vsce package)
else
  echo "==> 'vsce' not found — skipping .vsix packaging."
  echo "    Install with: npm install -g @vscode/vsce"
fi
