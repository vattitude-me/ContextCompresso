#!/usr/bin/env bash
# One-shot setup for macOS/Linux: builds ContextCompresso, starts it, and
# prints the config needed to point Claude Code or GitHub Copilot at it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

PORT=8137
BASE_URL="http://localhost:${PORT}"
JAR="target/contextcompresso.jar"

echo "==> Checking prerequisites..."

if ! command -v mvn >/dev/null 2>&1; then
  echo "!! 'mvn' not found on PATH. Install Maven (e.g. 'brew install maven') and try again."
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "!! 'java' not found on PATH. Install a JDK 17+ (e.g. 'brew install openjdk@17') and try again."
  exit 1
fi

if ! java -version >/dev/null 2>&1; then
  echo "!! 'java' is on PATH but doesn't run - this is usually the macOS stub launcher"
  echo "   shown when no JDK is registered with /usr/libexec/java_home, even if one is"
  echo "   installed via Homebrew and visible to 'mvn -version'."
  echo
  echo "   Fix by symlinking your Homebrew JDK, e.g.:"
  echo "     sudo ln -sfn \"\$(brew --prefix openjdk)/libexec/openjdk.jdk\" /Library/Java/JavaVirtualMachines/openjdk.jdk"
  echo "   Then verify with: java -version"
  exit 1
fi

JAVA_VERSION="$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
if [ -n "$JAVA_VERSION" ] && [ "$JAVA_VERSION" -lt 17 ]; then
  echo "!! Detected Java ${JAVA_VERSION}, but ContextCompresso requires Java 17+."
  exit 1
fi

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
  echo "!! Timed out waiting for ${BASE_URL}/actuator/health - check contextcompresso.log"
  exit 1
fi

echo "==> ContextCompresso is UP (pid ${CC_PID}, log: contextcompresso.log)"
echo

if [ "$CLIENT" = "claude" ]; then
  export ANTHROPIC_BASE_URL="${BASE_URL}"

  VSCODE_SETTINGS=".vscode/settings.json"
  mkdir -p .vscode
  if [ ! -f "$VSCODE_SETTINGS" ]; then
    echo "{}" > "$VSCODE_SETTINGS"
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 "${SCRIPT_DIR}/scripts/update_vscode_settings.py" "$VSCODE_SETTINGS" "$BASE_URL"
    echo "==> Wrote ANTHROPIC_BASE_URL to ${VSCODE_SETTINGS}"
  else
    echo "!! python3 not found, couldn't auto-update ${VSCODE_SETTINGS}."
    echo "   Add this manually:"
    echo '   "terminal.integrated.env.osx": { "ANTHROPIC_BASE_URL": "'"${BASE_URL}"'" }'
  fi

  echo
  echo "ANTHROPIC_BASE_URL=${BASE_URL} is set for this shell session."
  echo "To make it permanent, add this line to ~/.zshrc or ~/.bashrc:"
  echo "  export ANTHROPIC_BASE_URL=${BASE_URL}"
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
