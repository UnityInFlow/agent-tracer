---
phase: 04-query-api-demo-emitter-compose-stack
plan: 05
subsystem: distribution
tags: [docker, compose, dockerfile, healthcheck, cold-start, installdist, temurin-jre, clickhouse, sc5, dist-01]
requires:
  - "Plan 04-01: backend `application` packaging plugin + mainClass — `:backend:installDist` produces build/install/backend/bin/backend"
  - "Plan 04-03: demo-emitter `application` plugin + mainClass — `:demo-emitter:installDist` produces build/install/demo-emitter/bin/demo-emitter; OTLP_ENDPOINT / DEMO_SEED / DEMO_RUNS env contract"
  - "Plan 04-04: /healthz on :8080 (the container healthcheck target) + GET /api/runs (the SC5 served-queries proof endpoint)"
  - "Phase 3 (03-04): TracerApplication migrate→serve→drain lifecycle — only valid against a live ClickHouse, hence the service_healthy gate"
  - "Phase 2 (02-02..02-05): ClickHouseConfig.fromEnv CLICKHOUSE_URL/DATABASE/USER/PASSWORD env contract (empty-password default, no secret in code)"
provides:
  - "docker/compose.yaml — 3-service cold-start all-in-one: clickhouse (:26.3 LTS) → tracer (health-gated) → one-shot demo-emitter seeder (health-gated, restart: no). DIST-01/SC5/D-07"
  - "docker/tracer.Dockerfile + docker/emitter.Dockerfile — multi-stage eclipse-temurin:21-jdk builder over installDist → eclipse-temurin:21-jre runtime; no secret baked in"
  - "docker/healthcheck.sh — curl/wget-free bash /dev/tcp liveness probe for :8080/healthz (temurin-jre has neither curl nor wget; Pitfall 4)"
  - "scripts/compose-smoke.sh — cold up --build → poll /api/runs until non-empty → SC5 assertion → down -v teardown trap"
affects:
  - "Closes the Phase-2/3 deferred ClickHouse Docker-gated IT carry-forward: a plain `docker compose up` bypasses Testcontainers/Ryuk, so this run observed the real-Docker migrate→serve→ingest→served-query path GREEN against real ClickHouse 26.3 (the observation the INGEST/STOR ITs were waiting for)"
tech-stack:
  added: []
  patterns:
    - "Multi-stage Docker: eclipse-temurin:21-jdk builder runs `./gradlew :<module>:installDist --no-daemon` → eclipse-temurin:21-jre runtime copies just build/install/<module>/ + runs the application-plugin launcher (bin/<module>). Repo-root build context (compose `context: ..`) so Gradle resolves all modules"
    - "temurin-jre-safe healthcheck: bash built-in /dev/tcp pseudo-device opens a TCP connection to 127.0.0.1:8080, issues a minimal HTTP/1.0 GET /healthz, asserts a `200` status line — no curl/wget binary required (Pitfall 4)"
    - "Cold-start lifecycle gating: depends_on { condition: service_healthy } chains clickhouse → tracer → demo-emitter so migrate-then-serve (Phase-3 D-04) only runs against a live CH and the seeder fires only once the tracer serves; one-shot seeder is `restart: \"no\"`"
    - "Secret-free cold-start auth: CLICKHOUSE_SKIP_USER_SETUP=1 keeps the permissive empty-password `default` user (modern CH images otherwise mark it REQUIRED_PASSWORD → Code 194/HTTP 401), so ClickHouseConfig.fromEnv's empty-password default stays valid with NO password literal committed (T-04-16)"
    - "Host + in-network smoke fallback: poll curl on the published host port first, fall back to `compose exec clickhouse wget http://tracer:8080/api/runs` when a host-side localhost→https HSTS/TLS-upgrade proxy 307-redirects the plaintext port — a PASS on either transport proves the runs are served"
key-files:
  created:
    - "docker/compose.yaml"
    - "docker/tracer.Dockerfile"
    - "docker/emitter.Dockerfile"
    - "docker/healthcheck.sh"
    - "scripts/compose-smoke.sh"
  modified: []
decisions:
  - "CLICKHOUSE_SKIP_USER_SETUP=1 chosen as the SECRET-FREE cold-start auth fix: it preserves the permissive empty-password `default` user without introducing any password literal, so the tracer's ClickHouseConfig.fromEnv empty-password default works end-to-end (T-04-16). The alternative — setting CLICKHOUSE_PASSWORD — would have baked a secret into compose and violated the threat model."
  - "Demo-emitter is seed-on-up by default (D-07): no `profiles:` gate, so a plain `docker compose up` proves the full ingest→served-query pipeline. DEMO_SEED/DEMO_RUNS fall through to the emitter's built-in defaults (42 / 20); overridable via compose env."
  - "Smoke script polls /api/runs over TWO transports (host curl + in-network wget) because Docker Desktop's host-side localhost interception can 307-redirect the plaintext :8080 to https; the in-network probe reflects what a real OTel/UI client on the Docker network sees. PASS on either is sufficient."
metrics:
  duration: ~50 min
  tasks: 3
  files: 5
  completed: 2026-06-23
---

# Phase 4 Plan 05: Docker Compose all-in-one cold-start stack (DIST-01 / SC5) Summary

Shipped the Docker Compose all-in-one that proves cold-start end-to-end ingest with curl — the literal SC5 / DIST-01 deliverable and the backend-milestone capstone before any React exists. `docker compose -f docker/compose.yaml up` cold-starts ClickHouse (`:26.3` LTS) → the tracer (`eclipse-temurin:21-jre` over the Plan-01 `installDist` distribution, gated on ClickHouse healthy so the Phase-3 migrate-then-serve lifecycle only runs against a live CH) → a one-shot demo-emitter seeder (gated on the tracer healthy, `restart: "no"`) that exports seeded runs over OTLP/HTTP and exits, after which `GET :8080/api/runs` serves the seeded runs. The `:8080` healthcheck is a curl/wget-free bash `/dev/tcp` probe because `eclipse-temurin:21-jre` ships neither curl nor wget (RESEARCH Pitfall 4). The cold-start smoke was **observed GREEN on a real Docker host** (Docker Desktop 28.5.1) — see Checkpoint Result below.

## What Was Built

### Task 1 — Dockerfiles + temurin-jre-safe healthcheck (`6924876`)
- `docker/tracer.Dockerfile`: multi-stage. Stage 1 `eclipse-temurin:21-jdk` copies the whole repo and runs `./gradlew :backend:installDist --no-daemon` (Gradle needs settings + all modules to resolve `:backend`). Stage 2 `eclipse-temurin:21-jre` copies `build/install/backend/` to `/app`, copies + `chmod +x` the healthcheck, `EXPOSE 4317 4318 8080`, an image-level `HEALTHCHECK` running the probe, and `CMD ["/app/bin/backend"]`. No `CLICKHOUSE_*` secret baked in (T-04-16).
- `docker/emitter.Dockerfile`: the same multi-stage shape for `:demo-emitter:installDist` → `CMD ["/app/bin/demo-emitter"]` (one-shot launcher; `restart: "no"` in compose makes it run-once).
- `docker/healthcheck.sh`: curl/wget-free liveness probe — opens `/dev/tcp/127.0.0.1/8080` on fd 3 (bash built-in pseudo-device), writes a minimal `GET /healthz HTTP/1.0` with `Connection: close`, reads the status line, and `exit 0` only on a `" 200 "` match (Pitfall 4). Port overridable via `HEALTHCHECK_PORT`.

### Task 2 — compose.yaml cold-start stack + smoke script (`e1b413e`)
- `docker/compose.yaml`, three services per RESEARCH Pattern 6 / D-07:
  1. `clickhouse` image `clickhouse/clickhouse-server:26.3` (LTS pin, never `:latest`, T-04-17), `nofile` ulimits, healthcheck `wget --spider http://127.0.0.1:8123/ping` (the CH image HAS wget) at 5s interval / 20 retries.
  2. `tracer` built from `docker/tracer.Dockerfile` (`context: ..`), `CLICKHOUSE_URL: http://clickhouse:8123/` (no password literal), `depends_on: { clickhouse: { condition: service_healthy } }` so migrate→serve only runs against a live CH (Phase-3 D-04), `ports: 4317/4318/8080`, healthcheck `["CMD","/app/healthcheck.sh"]` with `start_period: 20s`.
  3. `demo-emitter` built from `docker/emitter.Dockerfile`, `OTLP_ENDPOINT: http://tracer:4318/v1/traces`, `depends_on: { tracer: { condition: service_healthy } }`, `restart: "no"` (one-shot seeder, SC5). Un-profiled — seed-on-up is the D-07 default.
- `scripts/compose-smoke.sh`: `set -euo pipefail`; `compose up -d --build`; polls `/api/runs` (host curl, with an in-network `compose exec clickhouse wget http://tracer:8080/api/runs` fallback) until the JSON `runs` array is non-empty or a `SMOKE_TIMEOUT` (default 120s) deadline; prints PASS/FAIL; `down -v` via an EXIT trap. Header documents that it needs a real Linux Docker host and that a plain `compose up` bypasses Ryuk (so it is where the deferred Phase-2/3 ClickHouse ITs finally observe a green real-Docker round-trip).

### Task 3 — fix: cold-start auth + smoke in-network fallback (`327e3d8`)
Two real-Docker-host fixes discovered while standing up the actual cold start (both already committed by the original executor):
- **Cold-start auth (`CLICKHOUSE_SKIP_USER_SETUP: "1"`):** modern clickhouse-server images mark the `default` user `REQUIRED_PASSWORD` and reject the tracer's empty-password migrate-then-serve with Code 194 / HTTP 401. Skipping user setup keeps the classic permissive empty-password `default` — the SECRET-FREE fix (no password literal introduced, T-04-16 intact).
- **Smoke in-network fallback:** Docker Desktop / a host-side HSTS or TLS-upgrade proxy can transparently 307-redirect `localhost:8080` to `https://`, which curl cannot follow against a plaintext server. Added a container→container `compose exec clickhouse wget http://tracer:8080/api/runs` fallback so a PASS on either transport proves the runs are served.

## Checkpoint Result — SC5 cold-start smoke: VERIFIED PASS (observed green on real Docker)

The Task-3 blocking `checkpoint:human-verify` (SC5 cold-start on a real Docker host) was **run and PASSED with real evidence on Docker Desktop 28.5.1** via `bash scripts/compose-smoke.sh` (SMOKE_TIMEOUT=180). This is recorded honestly as **observed-green on real Docker**, not Docker-gated/deferred.

Evidence:
- Cold `up --build` succeeded; both images built from `eclipse-temurin:21-jre` over `installDist` (backend BUILD SUCCESSFUL 11m35s, demo-emitter 10m55s — first cold build, cache-cold).
- Lifecycle gating held end-to-end: `clickhouse` Started→Healthy → `tracer` Started→Healthy (`/healthz`) → `demo-emitter` Started (one-shot, `restart: "no"`) → exited.
- `GET :8080/api/runs` returned a **NON-EMPTY** runs list. Sample run: traceId `58fef3ed4bd00f11c9b861eaf4d38d68`, agentName `planner-agent`, model `gemini-2.5-pro`, outcome `PARTIAL`, costUsd `46.513`, spanCount `19`, maxSaturation `100.0`; another: `orchestrator` / `claude-opus-4` / `FAILURE`.
- Clean teardown via `down -v`; no leftover containers. Smoke exit `0`.

## Cross-phase carry-forward closure — Phase-2/3 ClickHouse Docker-gated ITs

Because a plain `docker compose up` bypasses Testcontainers/Ryuk, this cold-start run **also observed the real-Docker ClickHouse round-trip GREEN** — the migrate-then-serve (Phase-3 D-04) + OTLP ingest + served-query path worked against real ClickHouse 26.3. That is precisely the real-Docker observation the deferred Phase-2/3 ITs (OtlpHttpReceiverIT / OtlpGrpcReceiverIT / TracerApplicationIT + the Phase-2 storage ITs) were carried forward waiting on (failing in-sandbox only with `ContainerLaunchException: testcontainers/ryuk:0.12.0`, an infra limit, never a code defect).

This SC5 run therefore:
- Satisfies **DIST-01 / SC5** (cold-start ingest + served queries), and
- Provides the real-Docker green observation that **closes the INGEST carry-forward** (the deferred-items row from 03-04): the full ingest→store→served-query pipeline is proven against real ClickHouse on Docker.

The operation-level Testcontainers/Ryuk `*IT` classes themselves remain sandbox-blocked under Colima/QEMU (unchanged); their real-Docker behavior is now observed green via this end-to-end cold start rather than via Ryuk.

## Verification Evidence

Automated verify gates (re-confirmed at close-out):
- `docker compose -f docker/compose.yaml config -q` — VALID (well-formed).
- `grep "clickhouse/clickhouse-server:26.3"` present; `grep ":latest"` ABSENT.
- `grep "condition: service_healthy"` ×2 (tracer→clickhouse, demo-emitter→tracer) present; `grep 'restart: "no"'` present.
- `grep "eclipse-temurin:21-jre"` + `grep "installDist"` present in tracer.Dockerfile; tracer EXPOSEs 4317/4318/8080.
- `docker/healthcheck.sh` is curl/wget-free (`/dev/tcp` only).
- No `CLICKHOUSE_PASSWORD=` literal anywhere under `docker/`.

End-to-end (Checkpoint Result above): cold `up --build` → lifecycle-gated start → non-empty `/api/runs` → clean `down -v`, smoke exit 0 on Docker Desktop 28.5.1.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Cold-start ClickHouse auth rejected the tracer's empty-password migrate-then-serve (Code 194 / HTTP 401)**
- **Found during:** Task 3 (real cold-start on Docker Desktop).
- **Issue:** Modern `clickhouse-server:26.3` marks the `default` user `REQUIRED_PASSWORD`, so the tracer's empty-password `ClickHouseConfig.fromEnv` connection was rejected before any migration ran.
- **Fix:** `CLICKHOUSE_SKIP_USER_SETUP: "1"` on the clickhouse service — keeps the classic permissive empty-password `default`. Deliberately the SECRET-FREE fix: no password literal introduced, so T-04-16 stays intact.
- **Files modified:** `docker/compose.yaml`.
- **Commit:** `327e3d8`.

**2. [Rule 1 - Bug] Smoke poll failed on Docker Desktop localhost→https 307 redirect of the plaintext :8080**
- **Found during:** Task 3 (smoke run).
- **Issue:** A host-side HSTS / TLS-upgrade proxy transparently 307-redirects `localhost:8080` to `https://`; curl cannot follow that against a plaintext server, so the host-transport poll never matched a non-empty runs list despite the tracer serving correctly.
- **Fix:** Added an in-network `compose exec clickhouse wget http://tracer:8080/api/runs` fallback that goes container→container and bypasses host-side localhost interception. A PASS on either transport now proves the runs are served.
- **Files modified:** `scripts/compose-smoke.sh`.
- **Commit:** `327e3d8`.

No architectural deviations (no Rule 4 checkpoints). No package-install checkpoints (only pinned official base images: `eclipse-temurin:21-jdk/jre`, `clickhouse/clickhouse-server:26.3`). The blocking `checkpoint:human-verify` was reached, run on a real Docker host, and resolved PASS.

## Threat Surface

No new security surface beyond the plan's `<threat_model>`. The assigned `mitigate` dispositions are implemented:
- **T-04-16** (ClickHouse creds in compose/image): creds via `CLICKHOUSE_*` runtime env only; no password literal in any committed `docker/` file (grep-gated). The CLICKHOUSE_SKIP_USER_SETUP cold-start fix is itself secret-free.
- **T-04-17** (base-image / package provenance): `eclipse-temurin:21-jdk/jre` and `clickhouse/clickhouse-server:26.3` are pinned official images (no `:latest`); no third-party packages installed in the Dockerfiles.
- **T-04-18** (published unauthenticated ports on a shared host): `accept (descoped)` — auth is OUT of scope for Phase 4 (CONTEXT); documented, not changed here.
- **T-04-19** (seeder flood on cold up): seeder is one-shot (`restart: "no"`) with operator-bounded DEMO_RUNS; the Phase-3 backpressure protects the pipeline.
- **T-04-SC** (image/package installs): only pinned official base images; no npm/pip/cargo/apt third-party installs.

## Self-Check: PASSED

- Created files: `docker/compose.yaml`, `docker/tracer.Dockerfile`, `docker/emitter.Dockerfile`, `docker/healthcheck.sh`, `scripts/compose-smoke.sh` — all FOUND.
- Commits `6924876`, `e1b413e`, `327e3d8` — all FOUND in git log (`git log --oneline --grep=04-05`).
