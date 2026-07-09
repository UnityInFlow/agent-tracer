#!/usr/bin/env bash
# pre-bash.sh — PreToolUse(Bash) guard for agent-tracer (Tool 09).
# Blocks irreversible / dangerous shell commands before they run.
#
# Input:  JSON on stdin, command at `.tool_input.command` (Claude Code convention).
# Output: exit 0 = allow (silent); exit 2 = block, stderr is shown to the agent.
#
# Ecosystem-wide guards from claude-code-harness-engineering-guide-v2.md §6:
# force-push, prod deploy, and destructive DB/file commands. Stack-agnostic —
# identical body across all UnityInFlow tool harnesses; only this header names
# the tool.

if ! command -v jq &>/dev/null; then
  # Cannot parse input without jq — fail open (don't block legitimate work).
  exit 0
fi

INPUT=$(cat)
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
[ -z "$CMD" ] && exit 0

block() {
  echo "BLOCKED by pre-bash guard: $1" >&2
  exit 2
}

# Force-push — use --force-with-lease and confirm with the user instead.
if echo "$CMD" | grep -qE 'git push +(-f|--force)([^-]|$)'; then
  block "force-push is not allowed. Use 'git push --force-with-lease' and confirm with the user."
fi

# Production deployments require explicit human approval.
if echo "$CMD" | grep -qiE 'deploy.*(prod|production)|helm +upgrade.*prod|kubectl.*--context[= ].*prod'; then
  block "production deployment detected. This requires explicit human approval."
fi

# Dropping databases / schemas.
if echo "$CMD" | grep -qiE 'drop +(database|schema)'; then
  block "dropping a database/schema requires human confirmation."
fi

# Recursive force-delete of root-ish paths.
if echo "$CMD" | grep -qE 'rm +-[a-zA-Z]*r[a-zA-Z]*f? +(/|~|\$HOME)([^a-zA-Z0-9._/-]|$)'; then
  block "recursive delete of a root/home path detected. Confirm with the user first."
fi

# Hard reset that discards committed work without review.
if echo "$CMD" | grep -qE 'git +reset +--hard +origin/'; then
  block "'git reset --hard origin/...' discards local commits. Confirm with the user first."
fi

# SUCCESS: silent allow.
exit 0
