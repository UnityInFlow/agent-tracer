# Phase 4: Query API, Demo Emitter & Compose Stack - Context

**Gathered:** 2026-06-23
**Status:** Ready for planning

<domain>
## Phase Boundary

The backend-completing milestone. A developer runs `docker compose up`, the demo emitter generates realistic synthetic agent-run traces over OTLP, and a REST query API answers every agent-debugging question with `curl` — **before any frontend exists**. Three deliverables in one phase:

- **Query API** (API-01..04): assembled run DAG, subagent hierarchy with cost roll-ups, filtered run list, context-saturation timeline — all served over HTTP.
- **Demo emitter** (TOOL-01): a runnable that emits realistic synthetic runs via OTLP exercising the full attribute convention.
- **Compose stack** (DIST-01): `docker compose up` brings up tracer + ClickHouse and ingests demo traces end-to-end on a cold start.

**In scope (from ROADMAP Phase 4 / API-01..04, TOOL-01, DIST-01):**
- Read-side REST API over the existing Phase-2 ClickHouse repository, served by the existing JVM/Ktor app on the query/UI port (**:8080** per CLAUDE.md port map).
- DAG assembled at query time from raw spans; tolerant of late, out-of-order, and orphaned spans (SC1).
- Demo emitter as a new Gradle module.
- Docker Compose stack (tracer image + ClickHouse + one-shot demo seeder).

**Out of scope (later phases / deferred):**
- The actual D3/elkjs visual frontend → Phase 5.
- Auth/token validation on the query API → not a Phase-4 deliverable.
- Helm chart → DIST-02 (deferred).
- JSON-encoded OTLP, OpenInference mapping, span-link subagent linkage → INGEST-04/05 (still deferred).

</domain>

<decisions>
## Implementation Decisions

### DAG response shape (API-01 / API-02) — SC1, SC2
- **D-01:** **Flat `nodes[]` + `edges[]`.** `GET /api/runs/{id}/dag` assembles the parent-child span tree at query time (from the Phase-2 `getTraceById` raw spans) but returns it as a **flat node list + edge list**, not a nested tree. Each node carries id, parentId, kind (agent/skill/etc.), token + `costUsd` metrics, timing, and a `group` marker; subagents are represented as **compound/group nodes** so the locked Phase-5 layout engine (`elkjs`, which natively does compound-node layout) consumes the payload directly without re-flattening. Edges are `{from, to}`.
- **D-02:** **DAG assembly is query-time and fault-tolerant.** The tree is built on read, never stored as a tree. It must tolerate late, out-of-order, and **orphaned** spans (a span whose parent id is absent) — orphans are still returned (e.g. attached to a synthetic root / flagged), never dropped (SC1).
- **D-03:** **Subagent hierarchy = same payload, with subtree cost roll-ups (API-02 / SC2).** Nested parent-child agent relationships are expressed via the compound `group` grouping in the same `nodes[]/edges[]`; agent/group nodes carry a **rolled-up subtree cost** (sum of descendant costs), computed at query time.

### Context-saturation timeline (API-04) — SC3
- **D-04:** **Saturation is its own endpoint.** `GET /api/runs/{id}/saturation` returns the context-saturation timeline series for a run. It is **not** embedded in the DAG payload (keeps the DAG response lean; the frontend fetches the timeline separately for the saturation view).

### Filtered run list (API-03) — SC3
- **D-05:** **`RunFilter` + offset/limit, newest-first.** The list endpoint maps to the **existing Phase-2 `RunFilter`** (agent, model, cost range, outcome, date range). Pagination is **offset/limit**, default sort **newest-first**. `outcome` (ok/error) is **derived from the run's root span status** at query time. (Cursor/keyset paging and unbounded responses were considered and rejected as over/under-built for v1.)

### Demo emitter form (TOOL-01) — SC4
- **D-06:** **Separate Gradle module, runnable main, one-shot, seeded-random.** The emitter is its **own Gradle module** with a runnable `main` that emits **N realistic, randomized-but-seedable** runs over OTLP and then exits. Runs exercise the **full attribute convention** (skills, subagents, costs, saturation). Seedable randomness keeps demos varied but reproducible for e2e tests.

### Compose stack (DIST-01) — SC5
- **D-07:** **tracer + ClickHouse always; demo emitter as a one-shot seeder on `up`.** `docker compose up` brings up tracer + ClickHouse unconditionally; the demo emitter runs as a **one-shot seeder service that fires after the tracer is healthy**, so a **cold `up` ingests demo traces end-to-end** and serves API queries (SC5). The **ClickHouse healthcheck gates tracer startup** so the Phase-3 **migrate-then-serve** lifecycle (D-04 from Phase 3) holds. Tracer image built from `eclipse-temurin:21-jre`; ClickHouse pinned to `:26.3` LTS (never `:latest`).

### Claude's Discretion
- The query API is served by the **same JVM/Ktor app**, exposed on **:8080** (separate connector or a second `embeddedServer`) — distinct from :4318 (OTLP/HTTP) and :4317 (gRPC). Exact route paths, JSON field names (`kotlinx-serialization`), error shapes, pagination defaults (default `limit`, max cap), the `kind` enumeration, the orphaned-span synthetic-root strategy, demo-emitter default run count + seed, Compose service names / healthcheck intervals / `depends_on` conditions, and the Dockerfile specifics are all Claude's discretion within the decisions above.
- Whether to additionally offer a `--profile demo` so a demo-free `up` is possible for real deployments (nice-to-have; default per D-07 is seed-on-`up`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope & requirements
- `.planning/ROADMAP.md` §"Phase 4: Query API, Demo Emitter & Compose Stack" — goal, 5 success criteria, API-01..04 / TOOL-01 / DIST-01 mapping
- `.planning/REQUIREMENTS.md` §API / Tooling / Distribution — API-01 (DAG), API-02 (subagent hierarchy), API-03 (filtered run list), API-04 (saturation series), TOOL-01 (demo emitter), DIST-01 (compose all-in-one)

### Locked technology stack (do not re-decide)
- `CLAUDE.md` §"Technology Stack" / §"Key Architecture-Affecting Decisions" — Ktor Netty server, `com.clickhouse:client-v2`, kotlinx-serialization-json for API payloads, port map (4317 gRPC / 4318 OTLP-HTTP / **8080 UI+API**), `eclipse-temurin:21-jre` app image, ClickHouse `:26.3` LTS pinned in Compose
- `CLAUDE.md` §"Technology Stack — Frontend" / §"Alternatives" — `elkjs` is the locked Phase-5 layout engine and the reason the DAG API returns flat `nodes[]+edges[]` with compound group nodes (D-01)

### Phase-2 query seams (reuse, don't rebuild)
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt` — `suspend getTraceById(traceId): Result<List<SpanRow>>` (DAG source, API-01/02) and `suspend listRuns(filter: RunFilter): Result<List<AgentRunRow>>` (API-03)
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/RunFilter.kt` — the filter params for API-03 (agent, model, cost range, outcome, date range)
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/{AgentRunRow,SpanRow,PayloadRow}.kt` — row types the API serializes from

### Phase-3 app seam (extend, don't replace)
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt` — `fun main()` with the Ktor `embeddedServer(Netty) { routing { ... } }` block (add the query API here / alongside) and the migrate-then-serve + drain lifecycle the Compose healthcheck ordering must respect

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ClickHouseSpanRepository.getTraceById` / `listRuns` already exist — Phase 4 is read-side API + assembly + serialization over these, not new storage queries (extend the repository only if a new query shape is genuinely needed, e.g. the saturation series).
- `RunFilter` already models the API-03 filter dimensions — the list endpoint maps query params → `RunFilter`.
- `AgentRunRow` / `SpanRow` / `PayloadRow` are the serialization sources for the API.

### Established Patterns
- Ktor Netty `embeddedServer` + `routing {}` is already wired in `TracerApplication.kt` (Phase 3) — the query API extends this app (new :8080 connector / second server), reusing kotlinx-serialization + content-negotiation conventions.
- Repo conventions: coroutines + `Result<T>` + `.await()`, no `var`, no `!!` without a safety comment, JUnit 5 + Kotest, Testcontainers for ClickHouse-backed proofs (same Docker-gated caveat as Phases 2–3).

### Integration Points
- Query API routes connect new HTTP handlers → existing `ClickHouseSpanRepository` (read path) with a query-time DAG-assembly layer in between.
- Demo emitter (new Gradle module) connects to the running receiver via an OTLP exporter — it is a *client* of the Phase-3 ingest path, exercising the full convention end-to-end.
- Compose wraps the built tracer image + ClickHouse + the one-shot seeder; the seeder is the OTLP client that proves the cold-start ingest path (SC5).

</code_context>

<specifics>
## Specific Ideas

- DAG payload shape the user approved (locked): `GET /api/runs/{id}/dag` → `{ nodes: [{id, parentId, kind, costUsd, tokens, group, ...}], edges: [{from, to}] }`; subagents as compound `group` nodes; `GET /api/runs/{id}/saturation` as a separate timeline endpoint.
- DAG assembly must be **query-time and tolerant of late/out-of-order/orphaned spans** (SC1) — this is an explicit success-criterion assertion, not a nice-to-have.
- `docker compose up` on a **cold start** must ingest demo traces **end-to-end** and serve queries (SC5) — the seeder-on-`up` design (D-07) exists specifically to satisfy this literally.

</specifics>

<deferred>
## Deferred Ideas

- **Phase 5** — the actual D3/elkjs interactive DAG frontend (this phase only delivers the API it consumes).
- **Auth/token validation** on the query + OTLP surfaces — the transport-agnostic validator idea (STACK.md) is a later concern, not Phase 4.
- **DIST-02** — Helm chart (deferred; Compose only for this milestone).
- **INGEST-04 / INGEST-05** — OpenInference attribute mapping and span-link subagent linkage remain deferred.
- **Carry-forward (not Phase-4 scope):** Phase 3's Docker-gated integration tests (and Phase 2's storage ITs) are still pending observation on a real Linux Docker host. Phase 4's Compose ClickHouse + a Linux runner is the natural place that debt finally closes — track via `/gsd-verify-work 3` on CI, not as a Phase-4 deliverable.

</deferred>

---

*Phase: 4-Query API, Demo Emitter & Compose Stack*
*Context gathered: 2026-06-23*
