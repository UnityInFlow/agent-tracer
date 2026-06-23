# Phase 4: Query API, Demo Emitter & Compose Stack - Pattern Map

**Mapped:** 2026-06-23
**Files analyzed:** 19 new/modified
**Analogs found:** 16 / 19 (3 infra files have no in-repo analog — Docker/Compose are net-new to this repo)

> All analog paths are absolute under `…/09-agent-tracer/`. Line numbers reference files read this session.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `backend/.../query/QueryApi.kt` | route | request-response | `backend/.../ingest/OtlpHttpRoutes.kt` (`Route.otlpTraces`) | role-match (HTTP route ext-fn; different body codec) |
| `backend/.../query/DagAssembler.kt` | service (transform) | transform | `backend/.../storage/ClickHouseSpanRepository.kt` `toAgentRunRow`/`toSpanRow` mappers | partial (transform style, no SQL) |
| `backend/.../query/SaturationAssembler.kt` | service (transform) | transform | `DagAssembler.kt` (sibling, same phase) → fallback same mappers | partial |
| `backend/.../query/RunListMapper.kt` | utility/mapper | transform | `ClickHouseSpanRepository.listRuns` filter-build block (lines 111–159) | role-match (params↔RunFilter) |
| `backend/.../query/dto/DagResponse.kt` | model (DTO) | request-response | `storage/AgentRunRow.kt` (immutable `data class` read model) | role-match (immutable read model; +`@Serializable`) |
| `backend/.../query/dto/SaturationResponse.kt` | model (DTO) | request-response | `storage/AgentRunRow.kt` | role-match |
| `backend/.../query/dto/RunListResponse.kt` | model (DTO) | request-response | `storage/AgentRunRow.kt` | role-match |
| `backend/.../app/TracerApplication.kt` (MODIFY) | config/bootstrap | request-response | *itself* — `bootReceivers`/`RunningReceivers`/`shutdown` (lines 82–214) | exact (extend in place) |
| `backend/.../storage/ClickHouseSpanRepository.kt` (MODIFY `listRuns`) | service (repository) | CRUD (read) | *itself* — `listRuns` (lines 111–159) | exact (extend in place) |
| `backend/.../storage/SpanRepository.kt` (MODIFY `listRuns` sig) | model (interface) | CRUD (read) | *itself* (lines 36–40) | exact |
| `backend/build.gradle.kts` (MODIFY: +3 JSON deps, +serialization plugin) | config | n/a | *itself* + `agent-semconv/build.gradle.kts` | exact |
| `gradle/libs.versions.toml` (MODIFY: +serialization libs/plugin) | config | n/a | *itself* | exact |
| `settings.gradle.kts` (MODIFY: include `demo-emitter`) | config | n/a | *itself* (lines 3–6) | exact |
| `demo-emitter/build.gradle.kts` | config | n/a | `agent-semconv/build.gradle.kts` + `backend/build.gradle.kts` (OTel test deps) | role-match |
| `demo-emitter/.../demo/DemoEmitter.kt` | service (client `main`) | event-driven (emit) | `OtlpHttpReceiverIT.kt` exporter setup (lines 104–143, 210–238) + `TracerApplication.main` (lines 190–214) | role-match (OTel SDK client) |
| `demo-emitter/.../demo/SeededRunFactory.kt` | utility (generator) | transform | `OtlpHttpReceiverIT.kt` span-build calls (lines 116–124); attr keys from `agent-semconv` | partial |
| `demo-emitter/.../demo/RunScenario.kt` | model (sealed) | n/a | `domain/AgentOutcome.kt` / `GenAiOperation.kt` (sealed/enum domain model) | role-match |
| `docker/compose.yaml` | config (infra) | n/a | **none in repo** (research Pattern 6 is the source) | NO ANALOG |
| `docker/tracer.Dockerfile` | config (infra) | n/a | **none in repo** | NO ANALOG |
| `docker/emitter.Dockerfile` | config (infra) | n/a | **none in repo** | NO ANALOG |

## Pattern Assignments

### `backend/.../query/QueryApi.kt` (route, request-response)

**Analog:** `backend/.../ingest/OtlpHttpRoutes.kt` — `Route.otlpTraces` (the only existing HTTP route ext-fn). Copy the **`fun Route.xxx(deps)` extension-function shape** and the `return@<verb>` early-exit error style; replace raw-protobuf `respondBytes` with `call.respond(dto)` JSON (new ContentNegotiation).

**Route extension-function pattern** (`OtlpHttpRoutes.kt:50-97`):
```kotlin
fun Route.otlpTraces(decoder: OtlpDecoder, writer: SpanWriter, signal: BackpressureSignal, config: ReceiverConfig) {
    post("/v1/traces") {
        if (signal.isSaturated) {
            call.response.headers.append(HttpHeaders.RetryAfter, config.retryAfterSeconds.toString())
            call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.TooManyRequests) { ByteArray(0) }
            return@post
        }
        // ...validate → call seam → respond...
    }
}
```
**For QueryApi.kt:** mirror as `fun Route.queryApi(repository: SpanRepository) { get("/api/runs") {...}; get("/api/runs/{id}/dag") {...}; get("/api/runs/{id}/saturation") {...} }`. Each handler is `suspend`; read `call.parameters["id"]`/`call.request.queryParameters`, call the repository read seam, and `call.respond(dto)`. Use the same guard-then-`return@get` early-exit style for 400 (bad param) / 404 (empty trace).

**Repository read seam to call** (`SpanRepository.kt:34,40`):
```kotlin
suspend fun getTraceById(traceId: String): Result<List<SpanRow>>   // dag + saturation
suspend fun listRuns(filter: RunFilter): Result<List<AgentRunRow>> // run list (after paging extension)
```
Unwrap `Result` with `.getOrElse { call.respond(HttpStatusCode.InternalServerError); return@get }` — the repo already logs the failure (`onFailure { logger.warn ... }`), so the route degrades gracefully (per `SpanRepository.kt:9-11` contract).

**Note (Pitfall 1):** ContentNegotiation + kotlinx-serialization are **NOT** currently on the backend classpath. `install(ContentNegotiation) { json() }` belongs on the **new :8080 `embeddedServer` only** (see TracerApplication pattern), never on the OTLP `Application` (would intercept `application/x-protobuf`).

---

### `backend/.../query/DagAssembler.kt` (service/transform, in-JVM, no SQL)

**Analog:** the binary-reader → row mappers in `ClickHouseSpanRepository.kt` (`toSpanRow` lines 190–222, `toAgentRunRow` lines 247–260) — same "pure function mapping one shape to another, nullable-preserving" style, but the assembler consumes `List<SpanRow>` and emits DTOs (no client-v2 calls). Keep it a free function / small class in `query/`, NOT in `storage/` (anti-pattern: storage is the sole SQL holder).

**`SpanRow` source fields** (verified `SpanRow.kt:25-63`) the assembler reads:
```kotlin
var spanId: String; var parentSpanId: String; var operation: String   // "" sentinel or GenAiOperation.name
var costUsd: Double?; var inputTokens: Long?; var outputTokens: Long?
var contextSaturationPct: Float?; var timestamp: Instant; var duration: Long
var statusCode: String; var agentName: String; var skillName: String; var outcome: String
```
**Assembly contract (RESEARCH Pattern 1, D-01/D-02/D-03):** one-pass `associateBy { it.spanId }`; orphan = `parentSpanId.isNotEmpty() && parentSpanId !in byId` → reparent to a synthetic root, never drop (SC1); `group = operation == "INVOKE_AGENT"`; subtree cost roll-up = cycle-guarded post-order DFS over edge adjacency (SC2). Ordering from `timestamp`, never "first span is root".

**Idiom to copy** (nullable-preserving, no `!!`) — from `ClickHouseSpanRepository.kt:213-216`:
```kotlin
inputTokens = readValue<Any?>("InputTokens")?.let { (it as Number).toLong() }
costUsd = readValue<Any?>("CostUsd")?.let { (it as Number).toDouble() }
```
Same `?.let` discipline applies to `costUsd?`/`contextSaturationPct?` when projecting into DTOs.

---

### `backend/.../query/SaturationAssembler.kt` (service/transform)

**Analog:** sibling `DagAssembler.kt` (same package, same pure-transform style). Source field `SpanRow.contextSaturationPct: Float?` (`SpanRow.kt:54`).

**Approach (RESEARCH Pattern 3, D-04):** filter non-null saturation, project `(timestamp, pct)`, sort by timestamp. **Prefer `mapNotNull` over `!!`** (CLAUDE.md):
```kotlin
fun timeline(spans: List<SpanRow>): SaturationResponse =
    SaturationResponse(
        points = spans.mapNotNull { s -> s.contextSaturationPct?.let { SaturationPoint(s.timestamp.toEpochMilli(), it) } }
            .sortedBy { it.timestampMillis },
    )
```

---

### `backend/.../query/RunListMapper.kt` (utility/mapper, transform)

**Analog:** the filter-assembly block of `ClickHouseSpanRepository.listRuns` (`ClickHouseSpanRepository.kt:111-159`) — but RunListMapper does the **inverse**: HTTP query params → `RunFilter`, not `RunFilter` → SQL. Plus outcome derivation.

**`RunFilter` exact fields to populate** (`RunFilter.kt:21-29`):
```kotlin
data class RunFilter(
    val agentName: String? = null, val model: String? = null,
    val costMin: Double? = null, val costMax: Double? = null,
    val outcome: String? = null, val dateFrom: Instant? = null, val dateTo: Instant? = null,
)
```
**Validation (V5, Security Domain):** parse `costMin/Max` as `Double?` (reject NaN/Inf), `dateFrom/To` as ISO-8601 `Instant`, clamp `limit ≤ 500`, `offset ≥ 0`, default newest-first. **`outcome` derivation (D-05 / A3, open question):** list endpoint may use `AgentRunRow.outcome` (`AgentRunRow.kt:21`, an `anyLast` rollup) as the proxy; the per-run DAG exposes the true root-span `statusCode`. Flag to planner.

**`AgentRunRow` → `RunSummary` mapping source** (`AgentRunRow.kt:18-30`): `traceId, agentName, model, outcome, startTime, endTime, inputTokens, outputTokens, costUsd, maxSaturation, spanCount`.

---

### `backend/.../query/dto/{DagResponse,SaturationResponse,RunListResponse}.kt` (model/DTO, request-response)

**Analog:** `storage/AgentRunRow.kt` (lines 18–30) — the established **immutable `val` read-model `data class`**. DTOs follow it exactly, **plus `@Serializable`** (new). The KDoc on `AgentRunRow.kt:6-17` documents *why* read models are immutable `val` (no client-v2 `register()` needed) — this is the explicit contrast with the `var` `SpanRow` POJO, and DTOs are firmly on the immutable side (Pitfall 2).

```kotlin
// AgentRunRow.kt:18 — the shape DTOs copy (add @Serializable):
data class AgentRunRow(val traceId: String, val agentName: String, /* ... all val */)
```
**Apply:** every `query/dto/*` is `@Serializable data class … (val …)`. Nullable JSON fields (`costUsd: Double?`) map straight to kotlinx-serialization nullable (emits `null`). No `var`, ever, in DTOs.

---

### `backend/.../app/TracerApplication.kt` (config/bootstrap — MODIFY)

**Analog:** *itself* — extend `bootReceivers` / `RunningReceivers` / `shutdown` in place (lines 82–214). This is the **exact** integration seam; do not rewrite.

**Where the :8080 query server is added** — inside `bootReceivers`, after `ktor` starts (`TracerApplication.kt:131-137`), mirroring the existing `embeddedServer(Netty, port = …)` call:
```kotlin
// existing :4318 receiver (TracerApplication.kt:131-134) — copy this exact embeddedServer shape:
val ktor = embeddedServer(Netty, port = receiverConfig.httpPort) {
    installOtlp(decoder, writer, receiverConfig)
}.start(wait = false)
```
**New (RESEARCH "Extending bootReceivers"):**
```kotlin
val queryServer = embeddedServer(Netty, port = 8080) {
    install(ContentNegotiation) { json() }   // JSON plugin isolated to THIS server only (Pitfall 1)
    routing {
        get("/healthz") { call.respondText("ok") }   // liveness for the Compose healthcheck (D-07)
        queryApi(repository)                          // repository already built at line 110
    }
}.start(wait = false)
```
**`RunningReceivers` extension (Pitfall 3):** add `val queryServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>` to the class (`TracerApplication.kt:82-91`), and stop it in `shutdown()` **alongside `ktor.stop(...)`** (line 163) — ports-first, before the writer drain. The query server has no writer/intake, so ordering is simple:
```kotlin
// shutdown(), next to TracerApplication.kt:163:
ktor.stop(gracePeriodMillis = KTOR_GRACE_MILLIS, timeoutMillis = KTOR_SHUTDOWN_TIMEOUT_MILLIS)
queryServer.stop(gracePeriodMillis = KTOR_GRACE_MILLIS, timeoutMillis = KTOR_SHUTDOWN_TIMEOUT_MILLIS)
```
Repository is already constructed (`TracerApplication.kt:110`: `val repository = ClickHouseSpanRepository(client)`) — pass it straight into `queryApi`.

---

### `backend/.../storage/ClickHouseSpanRepository.kt` + `SpanRepository.kt` (service/interface — MODIFY `listRuns`)

**Analog:** *itself* — `listRuns` (`ClickHouseSpanRepository.kt:111-159`) and its interface decl (`SpanRepository.kt:36-40`). Extend signature with `limit`/`offset`; flip sort direction.

**Current SQL to change** (`ClickHouseSpanRepository.kt:147-154`):
```kotlin
val sql = """
    SELECT TraceId, AgentName, Model, Outcome, StartTime, EndTime,
           InputTokens, OutputTokens, CostUsd, MaxSaturation, SpanCount
    FROM $AGENT_RUNS_TABLE FINAL
    $where
    ORDER BY StartTime, TraceId          -- ⟵ ascending + unbounded (D-05 violation)
""".trimIndent()
```
**Becomes (D-05, RESEARCH Pattern 4):**
```sql
ORDER BY StartTime DESC, TraceId DESC
LIMIT {limit:UInt32} OFFSET {offset:UInt32}
```
**Placeholder discipline (T-02-06, copy verbatim style from lines 117–144):** bind `limit`/`offset` as `params["limit"] = limit` / `params["offset"] = offset` — never interpolate. Keep the `runCatching { … }.onFailure { logger.warn(it) { "listRuns failed for filter=$filter" } }` wrapper (line 159). Update the `SpanRepository.listRuns` interface signature + KDoc to match (`SpanRepository.kt:36-40`).

---

### `demo-emitter/.../demo/DemoEmitter.kt` (service/client `main`, event-driven emit)

**Analog (transport):** `OtlpHttpReceiverIT.kt` exporter + `SdkTracerProvider` setup (lines 104–143, 219–238) — the in-repo, proven OTel SDK client wiring. **Analog (`main` lifecycle):** `TracerApplication.main` (`TracerApplication.kt:190-214`) for the `runBlocking` + env-read + bounded-shutdown shape.

**Exporter + tracer-provider setup to copy** (`OtlpHttpReceiverIT.kt:109-114, 219-228`):
```kotlin
val exporter = OtlpHttpSpanExporter.builder().setEndpoint("http://...:4318/v1/traces").build()
val sdk = SdkTracerProvider.builder()
    .addSpanProcessor(SimpleSpanProcessor.create(exporter))  // IT uses Simple; emitter prefers Batch
    .build()
```
**Emitter `main` (RESEARCH Pattern 5, D-06):** read `OTLP_ENDPOINT`/`DEMO_SEED`/`DEMO_RUNS` from env, `kotlin.random.Random(seed)`, `repeat(runCount) { generateRun(tracer, rng) }`, then bounded flush + exit:
```kotlin
tracerProvider.shutdown().join(30, TimeUnit.SECONDS)   // mirrors IT's forceFlush().join(10, SECONDS) (line 125)
```
Use the **env-read idiom** from `ReceiverConfig.fromEnv` / `ClickHouseConfig.fromEnv` (`ClickHouseConfig.kt:40-47`) — `env(KEY) ?: default` with named constants; do not scatter raw `System.getenv` calls.

**Span build pattern to copy** (`OtlpHttpReceiverIT.kt:116-124`):
```kotlin
sdk.get("demo-emitter").spanBuilder("invoke_agent")
    .setSpanKind(SpanKind.INTERNAL)
    .setAttribute(AttributeKey.longKey(GenAiAttributes.USAGE_INPUT_TOKENS), 1234L)
    .setAttribute(AttributeKey.stringKey(GenAiAttributes.AGENT_NAME), "planner-agent")
    .startSpan().end()
```

---

### `demo-emitter/.../demo/SeededRunFactory.kt` + `RunScenario.kt` (generator + sealed model)

**Analog (attribute keys — MUST reuse, never hardcode):** `agent-semconv` constants. The full key set the emitter must set (RESEARCH Pattern 5):
- `GenAiAttributes.OPERATION_NAME` / `AGENT_NAME` / `REQUEST_MODEL` / `RESPONSE_MODEL` / `USAGE_INPUT_TOKENS` / `USAGE_OUTPUT_TOKENS` / `TOOL_NAME` (`GenAiAttributes.kt:14-41`)
- `UnityInFlowAttributes.SKILL_NAME` / `CONTEXT_SATURATION_PCT` (0–100 double) / `COST_USD` / `PLAN_STEP` / `OUTCOME` / `SCHEMA_VERSION` (`UnityInFlowAttributes.kt:13-32`)
- operation values from `GenAiOperation` (`GenAiOperation.kt:9-33`): `invoke_agent`, `execute_tool`, `chat`, `plan`. **Subagents** = nested `invoke_agent` spans via `setParent(Context.current().with(parentSpan))`.

**`RunScenario.kt` sealed-model analog:** `domain/AgentOutcome.kt` and `GenAiOperation.kt` (`GenAiOperation.kt:9-33`) — the repo's established sealed/enum domain-modelling idiom (CLAUDE.md "sealed classes for domain modelling"). Seeded determinism: same seed → identical span tree (SC4 test `SeededRunFactoryTest`).

**`CONTEXT_SATURATION_PCT` is load-bearing (Pitfall 6):** the emitter is the *only* producer of saturation data — set it on multiple spans per run with a rising series, or SC3's saturation timeline tests against empty data.

---

### `docker/compose.yaml`, `docker/tracer.Dockerfile`, `docker/emitter.Dockerfile` (infra)

**Analog:** **NONE in repo** (no Dockerfile/compose/CI assets exist — confirmed). Use RESEARCH Pattern 6 + the CLAUDE.md/ecosystem constraints directly:
- Base image `eclipse-temurin:21-jre`; ClickHouse pinned `clickhouse/clickhouse-server:26.3` (never `:latest`).
- `depends_on: { clickhouse: { condition: service_healthy } }` gates tracer (respects Phase-3 migrate-then-serve, D-04); demo-emitter `depends_on: { tracer: { condition: service_healthy } }` + `restart: "no"` (one-shot seeder, SC5).
- Tracer healthcheck hits the new `GET /healthz` on :8080 — **but `eclipse-temurin:21-jre` has no `curl`/`wget`** (Pitfall 4 / A2 / Open Question 2): pick a check that exists in the base image (bash `/dev/tcp`, a baked busybox, or a JVM probe). Decide in planning.
- ClickHouse creds via `CLICKHOUSE_*` env only — never baked into the committed compose/image (Security Domain; mirrors `ClickHouseConfig.fromEnv` env-only convention, `ClickHouseConfig.kt:14-17`).
- **Packaging gap:** no `application`/shadow Gradle plugin exists yet (confirmed) — the Dockerfiles need a runnable jar; planning must add the packaging mechanism (`application` plugin / shadow / `jib`) for both `backend` and `demo-emitter`.

## Shared Patterns

### Coroutines + `Result<T>` + `.await()` (every async hop)
**Source:** `ClickHouseSpanRepository.kt` (`runCatching { … client.query(...).await() … }.onFailure { logger.warn(it) { … } }`, lines 73–109, 111–159) and `SpanRepository.kt:9-11` (the `suspend … : Result<T>` contract).
**Apply to:** every new route handler (`QueryApi.kt`) and repository change. Routes call `suspend` repo methods, unwrap `Result` with `.getOrElse`/`.getOrThrow`, never block; the repo already wraps + logs failures. No `Thread.sleep` — coroutine `delay`/`await`/`join` only (CLAUDE.md).

### Immutable `val` read-model `data class` (DTOs & filters)
**Source:** `storage/AgentRunRow.kt:18-30` and `storage/RunFilter.kt:21-29`.
**Apply to:** all `query/dto/*`, the run-list filter mapping. The sanctioned `var` exception is **only** the client-v2 `register()` POJOs (`SpanRow`/`PayloadRow`, see `SpanRow.kt:8-24`) — DTOs never get `var` (Pitfall 2).

### Env-sourced config via `fromEnv(env: (String) -> String? = System::getenv)`
**Source:** `ClickHouseConfig.kt:40-47` and `ReceiverConfig.kt:61-83` — named `ENV_*`/`DEFAULT_*` constants, injectable `env` lambda for tests, fail-fast on malformed values (`intEnv`, `ReceiverConfig.kt:76-83`), no secret defaults.
**Apply to:** the demo-emitter's `OTLP_ENDPOINT`/`DEMO_SEED`/`DEMO_RUNS` reads, and any new tracer-side query/port config.

### SQL placeholder binding (never interpolate user values)
**Source:** `ClickHouseSpanRepository.kt:117-144` — `predicates += "X = {name:Type}"; params["name"] = value`.
**Apply to:** the `listRuns` `limit`/`offset` extension (`{limit:UInt32}`/`{offset:UInt32}` + `params`) and any new query. Threat T-02-06 (SQL injection) — the query layer must never build SQL from values; storage owns all SQL.

### Ktor `testApplication {}` route tests against a `mockk`/fake `SpanRepository` (Docker-free)
**Source:** `OtlpHttpRoutesTest.kt:51-123` — `testApplication { application { routing { <routeFn>(…) } }; client.get/post(...); response.status shouldBe … }`, with a `mockk<SpanRepository>()` + `coEvery { … } answers { … }` fake.
**Apply to:** `QueryApiTest.kt` (status codes, JSON shape, 404 unknown run, paging defaults) — no Docker needed. Pair with pure-JVM assembler tests (`DagAssemblerTest`, `SaturationAssemblerTest`, `RunListMapperTest`) and the OTel-SDK round-trip IT modelled on `OtlpHttpReceiverIT.kt` (Docker-gated).

### Module `build.gradle.kts` skeleton (new `demo-emitter`)
**Source:** `agent-semconv/build.gradle.kts:1-22` (plugins/`kotlin { jvmToolchain(21) }`/`tasks.test { useJUnitPlatform() }`) + `backend/build.gradle.kts:39-46` (the OTel test deps to **re-scope to `implementation`** in the emitter). Add `id("org.jetbrains.kotlin.plugin.serialization")` only if the emitter needs `@Serializable` (it likely does not). Inherits `group`/`version`/kotlinter from root `subprojects {}` (`build.gradle.kts:17-26`). Register in `settings.gradle.kts:3-6`.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `docker/compose.yaml` | config (infra) | n/a | No Compose file exists in the repo; first Docker asset. Use RESEARCH Pattern 6 + CLAUDE.md pins. |
| `docker/tracer.Dockerfile` | config (infra) | n/a | No Dockerfile exists; also needs a new runnable-jar packaging mechanism (no `application`/shadow plugin present). |
| `docker/emitter.Dockerfile` | config (infra) | n/a | Same — net-new image for the seeder module. |

## Metadata

**Analog search scope:** `backend/src/main` (app, ingest, storage, convention, domain), `backend/src/test` (ingest ITs/route tests), `agent-semconv/src/main`, all `*.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`; Docker/Compose/CI presence check across the repo.
**Files scanned:** 14 source/build files read in full; full file tree + Docker/CI/plugin grep.
**Pattern extraction date:** 2026-06-23
