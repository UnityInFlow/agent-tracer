#!/usr/bin/env bash
# Liveness probe for the tracer's :8080 /healthz route.
#
# eclipse-temurin:21-jre ships NO curl and NO wget (RESEARCH Pitfall 4), so this
# probe uses bash's built-in /dev/tcp pseudo-device — no extra binary required.
# It opens a TCP connection to 127.0.0.1:8080, issues a minimal HTTP/1.0
# GET /healthz, and asserts a "200" status line. Exit 0 = healthy, 1 = not.
#
# Used by docker/tracer.Dockerfile's HEALTHCHECK / compose `test: ["CMD","/app/healthcheck.sh"]`.
set -euo pipefail

HOST="127.0.0.1"
PORT="${HEALTHCHECK_PORT:-8080}"
PATH_="/healthz"

# Open a bidirectional TCP connection on fd 3 (bash /dev/tcp); fail closed if it cannot connect.
exec 3<>"/dev/tcp/${HOST}/${PORT}" || exit 1

# Minimal HTTP/1.0 request — Connection: close so the server ends the response.
printf 'GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "${PATH_}" "${HOST}" >&3

# Read the status line and assert a 2xx (the route returns 200 "ok").
read -r status_line <&3 || exit 1
exec 3<&- 3>&-

case "${status_line}" in
  *" 200 "*) exit 0 ;;
  *) exit 1 ;;
esac
