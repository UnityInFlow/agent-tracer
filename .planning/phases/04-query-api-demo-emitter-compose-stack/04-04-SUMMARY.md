---
phase: 04-query-api-demo-emitter-compose-stack
plan: 04
subsystem: backend
tags: [query, rest-api, ktor, routing, healthz, two-server, lifecycle, json]
requires:
  - "Plan 04-01: kotlinx-serialization JSON stack + ContentNegotiation/ktor-serialization on backend classpath; SpanRepository.listRuns(filter, limit, offset) paginated signature; QueryApiTest Wave-0 RED stub"
  - "Plan 04-02: DagAssembler.assemble / SaturationAssembler.assemble / RunListMapper (toFilter/limitOf/offsetOf/toResponse) + the @Serializable DTOs (DagResponse, SaturationResponse, RunListResponse)"
  - "Phase 3: TracerApplication.bootReceivers/RunningReceivers/shutdown lifecycle seam; ingest/OtlpHttpRoutes.otlpTraces extension-function + early-exit error style"
provides:
  - "query.Route.queryApi(repository) — mounts GET /api/runs, GET /api/runs/{id}/dag, GET /api/runs/{id}/saturation with 200/400/404/500 status mapping + leak-free error bodies (API-01..04)"
  - "TracerApplication: a SECOND embeddedServer(Netty, 8080) with ContentNegotiation { json() } isolated to it (Pitfall 1), hosting /healthz + queryApi; wired into RunningReceivers + drained in shutdown() (DIST-01 prerequisite)"
  - "Full :backend:test source set compiles again — the last Wave-0 stub (QueryApiTest) flipped RED→GREEN"
affects:
  - "Plan 04-05 (Compose tracer image): /healthz on :8080 is the container healthcheck target that gates the demo seeder"
tech-stack:
  added: []
  patterns:
    - "Two embeddedServers in one JVM: protobuf OTLP (:4318/:4317) + JSON query (:8080); ContentNegotiation scoped to the JSON server ONLY so it never intercepts application/x-protobuf"
    - "Route ext-fn (fun Route.queryApi) mirroring otlpTraces: suspend handlers, Result unwrapped via .getOrElse, guard-then-return@get early exit, generic error messages only (no internals echoed)"
    - "Mapper-throws→400 boundary: RunListMapper.toFilter IllegalArgumentException (NaN/Inf cost, bad ISO-8601) caught at the route edge"
key-files:
  created:
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/QueryApi.kt"
  modified:
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/QueryApiTest.kt"
decisions:
  - "QueryApiTest Wave-0 stub's `install(ContentNegotiation)` calls needed an explicit `this@application.install(...)` receiver (+ import io.ktor.server.application.install) to compile — Kotlin could not resolve the implicit receiver inside testApplication's application {} block. Assertions kept byte-identical (Rule 3 blocking fix; the plan's Task 1 action explicitly says 'Fill in QueryApiTest.kt')."
  - "SaturationAssembler/DagAssembler method is `assemble` (Plan 02 contract), not `timeline`/`assemble` as the plan prose alternately named it; RunListMapper exposes toFilter/limitOf/offsetOf/toResponse (not a single paging()). Used the real Plan-02 symbols."
metrics:
  duration: ~6 min
  tasks: 2
  files: 3
  completed: 2026-06-23
---

# Phase 4 Plan 04: Query REST API (:8080) + /healthz two-server wiring Summary

Exposed the Plan-02 query transforms over HTTP as the curl-able backend milestone: a `fun Route.queryApi(repository)` mounting `GET /api/runs`, `GET /api/runs/{id}/dag`, `GET /api/runs/{id}/saturation` (API-01..04), served by a NEW isolated `embeddedServer(Netty, 8080)` with `ContentNegotiation { json() }` installed on that server only (kept off the protobuf OTLP receiver, Pitfall 1), plus `GET /healthz` (the DIST-01 healthcheck target Plan 05 gates on). The :8080 server is wired into `RunningReceivers` and drained in `shutdown()` alongside the OTLP server (no port leak). The last Wave-0 RED stub (`QueryApiTest`) flipped GREEN, so the full `:backend:test` source set compiles again.

## What Was Built

### Task 1 — `Route.queryApi`: the 3 query routes with status/error handling (`c73ee15`)
- `query/QueryApi.kt`: `fun Route.queryApi(repository: SpanRepository)` mirroring `OtlpHttpRoutes.otlpTraces` — suspend handlers, `Result` unwrapped with `.getOrElse { respond(500); return@get }`, guard-then-`return@get` early exit.
  - `GET /api/runs`: query params → `RunListMapper.toFilter` (wrapped in try/catch → **400** on a malformed cost/date, T-04-11) + `limitOf`/`offsetOf` (bounded paging) → `repository.listRuns(filter, limit, offset)` → `RunListMapper.toResponse(rows, limit, offset)` (`RunListResponse`, **200**). Newest-first + paging come from the repository (Plan 01) + mapper (Plan 02).
  - `GET /api/runs/{id}/dag`: blank/missing id → **400**; `repository.getTraceById(id)`; empty span list → **404** (unknown run); else `DagAssembler.assemble(spans)` → **200**.
  - `GET /api/runs/{id}/saturation`: same id handling; empty → **404**; else `SaturationAssembler.assemble(spans)` → **200**.
  - Error hygiene (T-04-12): 400/404/500 carry a short generic message only ("Invalid query parameter" / "Run not found" / "Query failed") — never a stack trace, SQL, or DB internal. The repository already logs read failures server-side.
- `QueryApiTest` flipped RED→GREEN (the explicit-receiver fix, see Deviations). All 4 route tests pass over a `mockk<SpanRepository>()`.

### Task 2 — Wire the :8080 query server + /healthz into bootReceivers/RunningReceivers/shutdown (`9fd1b02`)
- `TracerApplication.kt`: added `private const val QUERY_PORT = 8080`.
- Inside `bootReceivers`, AFTER the existing :4318 `ktor` server starts: a second `embeddedServer(Netty, port = QUERY_PORT) { install(ContentNegotiation) { json() }; routing { get("/healthz") { call.respondText("ok") }; queryApi(repository) } }.start(wait = false)`. `ContentNegotiation { json() }` is installed on THIS server only — never on the OTLP `Application` (Pitfall 1).
- `queryServer` added as a `RunningReceivers` property (+ KDoc) and threaded through the `bootReceivers` return.
- In `shutdown()`, `queryServer.stop(gracePeriodMillis = KTOR_GRACE_MILLIS, timeoutMillis = KTOR_SHUTDOWN_TIMEOUT_MILLIS)` runs immediately after `ktor.stop(...)` — ports-first, before the writer drain; the query server has no writer/intake so ordering is simple (Pitfall 3, T-04-15: `docker compose down` releases :8080 cleanly).
- The repository is already constructed in `bootReceivers` (line ~128) — passed straight into `queryApi`.

## Verification Evidence

- `./gradlew :backend:test --tests '*QueryApi*'` — **BUILD SUCCESSFUL** (all 4 QueryApiTest cases GREEN: 200 list, 200 known-run dag, 404 unknown run, 400 bad cost param — the last Wave-0 stub flipped).
- `./gradlew :backend:test` — 98 pure-JVM unit tests GREEN (DagAssembler/Saturation/RunListMapper/QueryApi/OtlpHttpRoutes all pass). The only 6 failures are the Testcontainers ITs (ClickHouseSchemaIT, PartCountIT, SpanRepositoryIT, OtlpHttpReceiverIT, OtlpGrpcReceiverIT, TracerApplicationIT) failing with `ContainerLaunchException` / Ryuk `initializationError` — the documented Colima/QEMU sandbox infra limit, NOT a code defect (see Integration-Test note).
- `./gradlew :backend:compileKotlin :backend:lintKotlin` — **BUILD SUCCESSFUL**.
- `grep "QUERY_PORT = 8080"` and `grep "queryServer.stop"` in TracerApplication.kt — both match.
- `grep -rn "install(ContentNegotiation)" backend/src/main` → exactly ONE hit, on the :8080 query server inside `bootReceivers`; NOT in `installOtlp` / the OTLP Application (Pitfall 1 confirmed).
- `grep -rn 'var '` / `'!!'` in QueryApi.kt → none (val-only, no double-bang).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] QueryApiTest `install(ContentNegotiation)` needed an explicit receiver to compile**
- **Found during:** Task 1 GREEN verification (`:backend:compileTestKotlin` failed with "install(...) cannot be called in this context with an implicit receiver" at the 4 `install` sites).
- **Issue:** The Plan-01 Wave-0 stub called `install(ContentNegotiation) { json() }` bare inside `testApplication { application { ... } }`. Kotlin could not resolve the implicit `Application` receiver in that nested lambda context, and `install` was not even imported.
- **Fix:** Added `import io.ktor.server.application.install` and changed each of the 4 calls to `this@application.install(ContentNegotiation) { json() }`. All test assertions (status codes, body shapes, mock setup) kept byte-identical. The plan's Task 1 `<action>` explicitly directs "Fill in `QueryApiTest.kt`", so completing the stub to compile-and-pass is in scope.
- **Files modified:** `backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/QueryApiTest.kt`.
- **Commit:** `c73ee15` (with the QueryApi.kt implementation).

**2. [Rule 3 - Naming] Used the real Plan-02 transform symbol names over the plan prose**
- The plan body alternately referenced `SaturationAssembler.timeline(spans)` and `RunListMapper.toFilter+paging`. The actual Plan-02 contract (and its passing tests) exposes `SaturationAssembler.assemble(spans)`, `DagAssembler.assemble(spans)`, and `RunListMapper.toFilter`/`limitOf`/`offsetOf`/`toResponse`. The route calls the real symbols. No user decision required — these are the existing, tested Plan-02 API.

### ktlint auto-format
- `:backend:formatKotlin` applied two purely-mechanical fixes during the loop: a chain-method-continuation newline in `QueryApi.kt` (`.entries().associate {...}`) and import-ordering in `TracerApplication.kt`. No logic change.

## Integration-Test / Sandbox Note

The 6 `*IT` failures are 100% `ContainerLaunchException` / Testcontainers Ryuk `initializationError` under the local Colima/QEMU environment — the SAME documented infra limit carried forward from Phases 2/3 ([02-02..02-05], [03-04]), NOT a code defect introduced here. This plan added NO integration tests and touched NO IT code; `QueryApiTest` uses `ktor-server-test-host` (in-process, no containers) and genuinely runs and passes, exactly the Phase-3 `Route.otlpTraces` conformance-test precedent [03-02]. No fake pass, no `@Disabled`, no IT weakening. Operation-level IT proof remains deferred to a native-Docker/CI environment per the established precedent (carried forward unchanged in Deferred Items).

## Threat Surface

No new security surface beyond the plan's `<threat_model>`. The mitigations it assigned are implemented:
- T-04-10 (`{id}` tampering): blank/missing id → 400 before any read; the id is passed only as a repository placeholder, the route builds no SQL.
- T-04-11 (filter/cost/date params): validated + bounded in `RunListMapper` (Plan 02), `IllegalArgumentException` caught at the route → 400.
- T-04-12 (error info disclosure): 400/404/500 return short generic messages only; no stack trace / SQL / DB internals.
- T-04-13 (DoS / large limit): `RunListMapper.limitOf` clamps ≤ 500 (Plan 02); response is a bounded page.
- T-04-15 (port leak on SIGTERM): `queryServer` added to `RunningReceivers` + stopped in `shutdown()` alongside `ktor.stop`.
- T-04-14 (auth) is `accept (descoped)` per CONTEXT — documented, not implemented this phase.
- T-04-SC (dependency installs): no new packages (consumes the Plan-01 JSON deps); no checkpoint required.

## Self-Check: PASSED

- Created file: `backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/QueryApi.kt` — FOUND.
- Modified files: `TracerApplication.kt`, `QueryApiTest.kt` — FOUND.
- Commits `c73ee15`, `9fd1b02` — both FOUND in git log.
