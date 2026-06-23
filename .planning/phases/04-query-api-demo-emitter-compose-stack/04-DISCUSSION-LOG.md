# Phase 4: Query API, Demo Emitter & Compose Stack - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-23
**Phase:** 4-Query API, Demo Emitter & Compose Stack
**Areas discussed:** DAG response shape, Run-list filtering & paging, Demo emitter form & content, Compose stack topology

---

## DAG response shape (API-01 / API-02)

| Option | Description | Selected |
|--------|-------------|----------|
| Flat `nodes[]` + `edges[]` | Assembled at query time, returned flat with subagents as compound/group nodes; elkjs-ready; saturation as its own endpoint | ✓ |
| Nested parent-embeds-children tree | Recursive JSON tree, saturation embedded; more curl-readable but frontend must flatten for elkjs | |

**User's choice:** Flat `nodes[]` + `edges[]` (recommended).
**Notes:** Chosen because `elkjs` (the locked Phase-5 layout engine) consumes flat nodes+edges with compound nodes directly. Saturation timeline split to its own endpoint.

---

## Run-list filtering & paging (API-03)

| Option | Description | Selected |
|--------|-------------|----------|
| Offset/limit, newest-first | limit+offset, default newest-first, outcome derived from root span status | ✓ |
| Cursor/keyset paging | Scales better, more complex; heavier than v1 needs | |
| No paging for v1 | Return all matching capped at N; degrades on large datasets | |

**User's choice:** Offset/limit, newest-first (recommended).
**Notes:** Maps to the existing Phase-2 `RunFilter`. Simple and curl-friendly for v1.

---

## Demo emitter form & content (TOOL-01)

| Option | Description | Selected |
|--------|-------------|----------|
| Separate module, one-shot, seeded-random | Own Gradle module, runnable main, emits N seedable-random runs then exits; full convention | ✓ |
| Fixed canned scenarios | Hand-authored deterministic runs; most reproducible, less variety | |
| Continuous generator | Streams traces until stopped; more than v1 needs, awkward for deterministic tests | |

**User's choice:** Separate module, one-shot, seeded-random (recommended).
**Notes:** Seedable randomness keeps demos varied but reproducible for e2e tests; clean to run as a Compose seeder.

---

## Compose stack topology (DIST-01)

| Option | Description | Selected |
|--------|-------------|----------|
| One-shot seeder service on `up` | tracer + ClickHouse always; emitter fires after tracer healthy so cold `up` ingests demo traces end-to-end (SC5); ClickHouse healthcheck gates tracer | ✓ |
| Seeder behind a `demo` profile | Plain `up` is tracer + ClickHouse only; `up --profile demo` seeds | |
| Emitter run manually, outside Compose | Simplest stack; bare `up` would not meet SC5 | |

**User's choice:** One-shot seeder service on `up` (recommended).
**Notes:** Chosen to satisfy SC5 literally — a cold `docker compose up` must ingest demo traces end-to-end. ClickHouse healthcheck preserves the Phase-3 migrate-then-serve lifecycle.

---

## Claude's Discretion

- Query API served by the same JVM/Ktor app on :8080 (separate connector vs second embeddedServer), exact route paths, JSON field names, error shapes, pagination defaults/caps, `kind` enumeration, orphaned-span synthetic-root strategy, demo-emitter run-count/seed defaults, Compose service names / healthcheck intervals / Dockerfile specifics.
- Optional `--profile demo` to allow a demo-free `up` for real deployments.

## Deferred Ideas

- Phase 5 — the D3/elkjs interactive frontend (this phase only delivers the API).
- Auth/token validation on the query + OTLP surfaces.
- DIST-02 — Helm chart.
- INGEST-04 / INGEST-05 — OpenInference mapping, span-link subagent linkage.
- Carry-forward: Phase 3's Docker-gated ITs (and Phase 2's storage ITs) close naturally on the Compose ClickHouse + a Linux Docker host via `/gsd-verify-work 3` on CI — not a Phase-4 deliverable.
