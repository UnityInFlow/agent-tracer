#!/usr/bin/env bash
# post-edit.sh — PostToolUse(Write|Edit) back-pressure for agent-tracer (Tool 09).
# Runs ktlint + Kotlin compile after file edits. Silent on success; on failure
# exits 2 so the agent is re-engaged to fix the problem before finishing.
#
# SELF-GUARDING: until ./gradlew exists this hook no-ops, so it never blocks
# work on a not-yet-scaffolded project. Once a Gradle wrapper is present the
# checks activate automatically — no edit to this file required. Works both
# under Claude Code and when invoked manually.

# Resolve the tool's own directory (.../.claude/hooks -> tool root is two up).
HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$HOOK_DIR/../.." && pwd)"
cd "$PROJECT_DIR" || exit 0

# No build scaffolded yet -> nothing to verify. Stay silent.
[ -x "./gradlew" ] || exit 0

# Fast, change-only verification: ktlint + compile. Keep success silent.
OUTPUT=$(./gradlew ktlintCheck compileKotlin --quiet 2>&1)
STATUS=$?

if [ $STATUS -ne 0 ]; then
  echo "ktlint/compile errors — fix before finishing:" >&2
  echo "$OUTPUT" >&2
  exit 2 # re-engage the agent
fi

# SUCCESS: completely silent — nothing added to context.
exit 0
