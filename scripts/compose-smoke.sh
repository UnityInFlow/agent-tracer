#!/usr/bin/env bash
# SC5 cold-start smoke test for the agent-tracer Compose all-in-one (DIST-01).
#
# Cold-starts the stack (ClickHouse -> tracer -> one-shot demo-emitter seeder),
# then polls GET :8080/api/runs until the JSON `runs` array is non-empty — proving
# the demo traces ingested end-to-end AND are served back over the query API.
# Tears the stack down (`down -v`) on any exit via a trap.
#
# REQUIRES a real Linux Docker host (local Docker Desktop or a GitHub-hosted ubuntu-24.04 runner).
# The sandbox Testcontainers/Ryuk path is blocked (carry-forward debt from Phases 2-3),
# but a plain `docker compose up` does NOT go through Ryuk, so this script is the
# canonical place the deferred Phase-2/3 ClickHouse ITs finally observe a green
# real-Docker round-trip. curl is used on the HOST (which has it), not in-container.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/compose.yaml"
RUNS_URL="http://localhost:8080/api/runs"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT:-120}"

compose() { docker compose -f "${COMPOSE_FILE}" "$@"; }

cleanup() {
  echo "--- tearing down (down -v) ---"
  compose down -v || true
}
trap cleanup EXIT

echo "=== agent-tracer SC5 cold-start smoke ==="
echo "Compose file: ${COMPOSE_FILE}"

# Two up modes (D-07/D-13, existing env-override convention like SMOKE_TIMEOUT):
#   SMOKE_BUILD=1 -> dev mode: build from source via the compose.build.yaml override
#   default (0)   -> release mode: pull released GHCR images only, never build
if [ "${SMOKE_BUILD:-0}" = "1" ]; then
  echo "--- cold up (dev: build + start, detached) ---"
  docker compose -f "${COMPOSE_FILE}" -f "${SCRIPT_DIR}/../docker/compose.build.yaml" up -d --build
else
  echo "--- cold up (release: pull + start, detached) ---"
  compose pull
  compose up -d
fi

# Fetch /api/runs and echo a non-empty-runs match (empty otherwise).
#
# Two transports are tried, in order:
#   1. HOST  — curl http://localhost:8080/api/runs (the published port).
#   2. IN-NETWORK fallback — `compose exec clickhouse wget http://tracer:8080/api/runs`.
#      Some hosts (Docker Desktop with HSTS / a local TLS-upgrade proxy) transparently
#      307-redirect `localhost:8080` to `https://`, which curl cannot follow against a
#      plaintext server. The in-network probe goes container->container and bypasses any
#      host-side localhost interception — it reflects what a real OTel/UI client on the
#      Docker network sees. A PASS on EITHER transport proves the tracer serves the runs.
fetch_runs_body() {
  local body
  body="$(curl -fsS "${RUNS_URL}" 2>/dev/null || true)"
  if ! printf '%s' "${body}" | grep -Eq '"runs"[[:space:]]*:[[:space:]]*\[[[:space:]]*\{'; then
    # Host transport gave nothing usable (e.g. localhost->https 307); try in-network.
    body="$(compose exec -T clickhouse wget -qO- "http://tracer:8080/api/runs" 2>/dev/null || true)"
  fi
  printf '%s' "${body}"
}

echo "--- polling ${RUNS_URL} (host) + http://tracer:8080/api/runs (in-network) for a non-empty runs list (timeout ${TIMEOUT_SECONDS}s) ---"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  body="$(fetch_runs_body)"

  # PASS condition: a "runs" array with at least one element. Match a non-empty array
  # without assuming jq is installed (jq is not guaranteed on every runner).
  if printf '%s' "${body}" | grep -Eq '"runs"[[:space:]]*:[[:space:]]*\[[[:space:]]*\{'; then
    echo "PASS: /api/runs returned a non-empty runs list — SC5 end-to-end ingest + served query confirmed."
    echo "Response (head): $(printf '%s' "${body}" | head -c 400)"
    exit 0
  fi

  if [ "$(date +%s)" -ge "${deadline}" ]; then
    echo "FAIL: timed out after ${TIMEOUT_SECONDS}s without a non-empty runs list." >&2
    echo "Last /api/runs body: ${body:-<empty/unreachable>}" >&2
    echo "--- service status ---" >&2
    compose ps >&2 || true
    echo "--- tracer logs (tail) ---" >&2
    compose logs --tail=50 tracer >&2 || true
    exit 1
  fi

  sleep 3
done
