#!/usr/bin/env bash
# One-shot setup for macOS/Linux: builds ContextCompresso, starts it, and
# prints the config needed to point Claude Code or GitHub Copilot at it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

PORT=8137
BASE_URL="http://localhost:${PORT}"
JAR="target/contextcompresso.jar"

echo "==> Building ContextCompresso (mvn clean package)..."
mvn -q clean package -DskipTests

echo
echo "Which client are you setting up ContextCompresso for?"
select CHOICE in "Claude Code" "GitHub Copilot"; do
  case "$REPLY" in
    1) CLIENT="claude"; break ;;
    2) CLIENT="copilot"; break ;;
    *) echo "Enter 1 or 2." ;;
  esac
done

echo
echo "==> Starting ContextCompresso on port ${PORT}..."
nohup java -jar "$JAR" > contextcompresso.log 2>&1 &
CC_PID=$!
echo "$CC_PID" > contextcompresso.pid

echo -n "==> Waiting for health check"
UP=0
for i in $(seq 1 30); do
  if curl -sf "${BASE_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    UP=1
    break
  fi
  echo -n "."
  sleep 1
done
echo

if [ "$UP" -ne 1 ]; then
  echo "!! Timed out waiting for ${BASE_URL}/actuator/health — check contextcompresso.log"
  exit 1
fi

echo "==> ContextCompresso is UP (pid ${CC_PID}, log: contextcompresso.log)"
echo

if [ "$CLIENT" = "claude" ]; then
  echo "Point Claude Code at it for this shell session:"
  echo
  echo "  export ANTHROPIC_BASE_URL=${BASE_URL}"
  echo
  echo "To make it permanent, add that line to ~/.zshrc or ~/.bashrc, or scope it to"
  echo "one VS Code workspace via .vscode/settings.json:"
  echo '  "terminal.integrated.env.osx": { "ANTHROPIC_BASE_URL": "'"${BASE_URL}"'" }'
else
  echo "Point GitHub Copilot at it by adding this to your VS Code settings.json:"
  echo
  cat <<EOF
  "github.copilot.advanced": {
    "debug.overrideProxyUrl": "${BASE_URL}"
  }
EOF
fi

echo
echo "To stop ContextCompresso later: kill \$(cat contextcompresso.pid)"
