#!/usr/bin/env bash
# stop-check.sh — Stop-hook back-pressure for agent-tracer (Tool 09).
# Runs the Gradle test suite when the agent tries to finish. Silent on success;
# on failure exits 2 so the agent is re-engaged before stopping.
#
# SELF-GUARDING: until ./gradlew exists this hook no-ops silently, then
# activates automatically once the project is scaffolded — no edit to this
# file required. Works both under Claude Code and when invoked manually.

# Honor stop_hook_active to prevent Stop-hook re-engagement loops (Claude Code
# convention). Fail open if jq is missing.
if command -v jq &>/dev/null; then
  INPUT=$(cat)
  ACTIVE=$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false' 2>/dev/null)
  [ "$ACTIVE" = "true" ] && exit 0
fi

# Resolve the tool's own directory (.../.claude/hooks -> tool root is two up).
HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$HOOK_DIR/../.." && pwd)"
cd "$PROJECT_DIR" || exit 0

# No build scaffolded yet -> nothing to verify. Stay silent.
[ -x "./gradlew" ] || exit 0

OUTPUT=$(./gradlew test --quiet 2>&1)
STATUS=$?

if [ $STATUS -ne 0 ]; then
  echo "tests failed — fix before finishing:" >&2
  echo "$OUTPUT" | tail -n 40 >&2
  exit 2 # re-engage the agent
fi

# SUCCESS: completely silent — nothing added to context.
exit 0
