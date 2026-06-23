# Phase 4: Query API, Demo Emitter & Compose Stack - Research

**Researched:** 2026-06-23
**Domain:** Read-side REST query API over ClickHouse, query-time DAG assembly, JVM OTLP client (demo emitter), Docker Compose all-in-one
**Confidence:** HIGH (every claim grounded in files read this session + version catalog + Maven Central)

## Summary

Phase 4 is **pure read-side + packaging** on top of finished Phase-2 storage and Phase-3 ingest. Three deliverables: (1) a JSON REST query API on **:8080**, (2) a demo emitter as a new Gradle module that is an OTLP **client**, (3) a Docker Compose stack that cold-starts tracer + ClickHouse + a one-shot seeder.

The central discovery from reading the seam files: **no new ClickHouse query is required for API-01/02 (DAG) or API-04 (saturation).** `ClickHouseSpanRepository.getTraceById(traceId): Result<List<SpanRow>>` already returns every span of a run, and `SpanRow` already carries `parentSpanId`, `costUsd`, `inputTokens`, `outputTokens`, `operation`, `agentName`, `skillName`, `outcome`, `timestamp`, `duration`, `statusCode`, and `contextSaturationPct` (`Float?`). DAG assembly, subtree cost roll-ups, and the saturation timeline are **all pure in-JVM transforms over that one list** — built in a new `query/` package, leaving `storage/` untouched. `listRuns(filter: RunFilter)` already serves API-03; the only repository changes needed are **offset/limit + sort** parameters on `listRuns` (currently unbounded, `ORDER BY StartTime, TraceId` ascending — must become newest-first paginated per D-05).

The demo emitter (D-06) is a **client** of the Phase-3 receiver, so it must NOT reuse the `opentelemetry-proto` receiver artifact. The correct tool is the **OpenTelemetry Java SDK + `opentelemetry-exporter-otlp`** — already present in the version catalog (`otel-bom` 1.63.0, `otel-sdk`, `otel-exporter-otlp`) but currently `testImplementation`-scoped in `backend`. The emitter module uses them as `implementation`.

**Primary recommendation:** Add a `query/` package + 3 routes to the existing Ktor app on a **second `embeddedServer(Netty, port=8080)`** started inside `bootReceivers`; add `ktor-server-content-negotiation` + `ktor-serialization-kotlinx-json` + `kotlinx-serialization-json` to `backend` (NOT currently on the classpath — the CONTEXT's "content-negotiation already wired" claim is wrong; the OTLP route uses raw protobuf bytes). Build the demo emitter as a new `demo-emitter` Gradle module using the OTel SDK. Wire Compose with `depends_on: { clickhouse: { condition: service_healthy } }` and a one-shot seeder service with `restart: "no"`.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| API-01 | Assembled run DAG | `getTraceById` returns `List<SpanRow>`; assemble flat `nodes[]/edges[]` in-JVM from `spanId`/`parentSpanId` (D-01). Orphan-tolerant (D-02). |
| API-02 | Subagent hierarchy + cost roll-ups | `operation == INVOKE_AGENT` (`gen_ai.operation.name=invoke_agent`) marks agent/`group` nodes; subtree cost = sum of descendant `costUsd` computed post-assembly (D-03). |
| API-03 | Filtered run list | Maps query params → existing `RunFilter`; add offset/limit + newest-first sort to `listRuns` (D-05). `outcome` already a typed `agent_runs` column. |
| API-04 | Context-saturation timeline | Own endpoint `/api/runs/{id}/saturation`; derive `(timestamp, contextSaturationPct)` series from the same `getTraceById` spans where `contextSaturationPct != null` (D-04). |
| TOOL-01 | Demo emitter | New `demo-emitter` Gradle module; OTel SDK + `opentelemetry-exporter-otlp` client; seeded `kotlin.random.Random(seed)`; emits full attribute convention; one-shot `main` then exits (D-06). |
| DIST-01 | docker compose all-in-one | tracer (`eclipse-temurin:21-jre`) + ClickHouse `:26.3` + one-shot seeder; CH healthcheck gates tracer; seeder fires after tracer healthy (D-07). |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

- Kotlin 2.4.0 (catalog), JVM target 21, Gradle Kotlin DSL only, version catalog (`gradle/libs.versions.toml`).
- Coroutines for all async — never `Thread.sleep()`/raw threads. The repository already bridges client-v2 futures with `.await()`.
- No `var` (documented exception: client-v2 `register()` POJOs `SpanRow`/`PayloadRow` only — the DAG/saturation DTOs the API returns must be immutable `val` data classes).
- No `!!` without a safety comment.
- Sealed classes for domain modelling; immutable data classes for value objects.
- JUnit 5 + Kotest assertions; Testcontainers for ClickHouse-backed proofs (Ryuk/Docker caveat — see Validation Architecture).
- `ktlint` (kotlinter 5.0.0) before every commit.
- Group `io.github.unityinflow`; new module version `0.0.1-SNAPSHOT` (inherited from root `subprojects {}`).
- No secrets committed — ClickHouse creds via `CLICKHOUSE_*` env (`ClickHouseConfig.fromEnv`); Compose passes env, never bakes secrets.
- CI on `runs-on: [arc-runner-unityinflow]`, never `ubuntu-latest`; spec-ci-plugin lints CLAUDE.md.
- Backend must be built/tested before any frontend (frontend is Phase 5 — out of scope here).

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** `GET /api/runs/{id}/dag` assembles the parent-child span tree at query time from `getTraceById` raw spans but returns a **flat `nodes[] + edges[]`**, not a nested tree. Each node carries id, parentId, kind (agent/skill/etc.), token + `costUsd` metrics, timing, and a `group` marker; subagents are compound/`group` nodes so Phase-5 `elkjs` consumes directly. Edges are `{from, to}`.
- **D-02:** DAG assembly is **query-time and fault-tolerant** — tolerate late, out-of-order, and **orphaned** spans (parent id absent). Orphans are returned (synthetic root / flagged), never dropped (SC1).
- **D-03:** Subagent hierarchy = same payload, with **subtree cost roll-ups** (sum of descendant costs) on agent/`group` nodes, computed at query time (API-02 / SC2).
- **D-04:** `GET /api/runs/{id}/saturation` is its **own endpoint** (timeline series), NOT embedded in the DAG payload.
- **D-05:** List endpoint maps to existing `RunFilter` (agent, model, cost range, outcome, date range); **offset/limit** pagination, default **newest-first**; `outcome` (ok/error) derived from the run's **root-span status** at query time. (Cursor/keyset paging rejected.)
- **D-06:** Demo emitter = its **own Gradle module**, runnable `main`, one-shot, **seeded-random**; emits N realistic runs over OTLP exercising the **full attribute convention**, then exits.
- **D-07:** `docker compose up` brings up tracer + ClickHouse **unconditionally**; demo emitter is a **one-shot seeder** that fires **after the tracer is healthy**; cold `up` ingests end-to-end (SC5). ClickHouse healthcheck gates tracer startup (respect Phase-3 migrate-then-serve). Tracer image `eclipse-temurin:21-jre`; ClickHouse pinned `:26.3` LTS.

### Claude's Discretion

- Query API served by the **same JVM/Ktor app** on **:8080** (separate connector or second `embeddedServer`) — distinct from :4318/:4317.
- Exact route paths, JSON field names (`kotlinx-serialization`), error shapes, pagination defaults (default `limit`, max cap), the `kind` enumeration, the orphaned-span synthetic-root strategy, demo-emitter default run count + seed, Compose service names / healthcheck intervals / `depends_on` conditions, Dockerfile specifics — all Claude's discretion within the decisions.
- Optional `--profile demo` so a demo-free `up` is possible (nice-to-have; default is seed-on-`up`).

### Deferred Ideas (OUT OF SCOPE)

- **Phase 5** — the D3/elkjs interactive DAG frontend (Phase 4 delivers only the API it consumes). **Do NOT research/plan frontend.**
- **Auth/token validation** on query + OTLP surfaces.
- **DIST-02** — Helm chart.
- **INGEST-04 / INGEST-05** — OpenInference mapping, span-link subagent linkage, JSON-encoded OTLP.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Run DAG assembly (API-01/02) | API / Backend (new `query/` package) | Storage (`getTraceById` read) | Pure in-JVM transform over the span list; correctness lives in Kotlin tests (STATE roadmap decision: "server-side DAG assembly — DAG correctness stays inside Kotlin test coverage"). Storage stays the sole SQL holder. |
| Subtree cost roll-up (API-02) | API / Backend | — | Tree-walk over assembled nodes; no SQL. |
| Saturation timeline (API-04) | API / Backend | Storage (`getTraceById` read) | Filter+project the same span list to `(ts, pct)`; no new query. |
| Filtered run list (API-03) | API / Backend (route) | Storage (`listRuns` + new paging) | Query params → `RunFilter`; storage owns the parameterized WHERE + new LIMIT/OFFSET. |
| JSON serialization | API / Backend (Ktor content-negotiation) | — | kotlinx-serialization DTOs; new dependency. |
| Demo trace generation (TOOL-01) | Standalone client (new module) | — | OTel SDK exporter; a *client* of :4318, not part of the server JVM. |
| Cold-start orchestration (DIST-01) | Infra (Compose) | — | Healthcheck gating + one-shot seeder; respects Phase-3 migrate-then-serve. |

## Standard Stack

All backend libs are already in `gradle/libs.versions.toml` unless marked NEW. The phase adds **3 JSON deps to `backend`** and a **new `demo-emitter` module** that reuses catalog OTel entries.

### Core (already in catalog — reuse)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.ktor:ktor-server-netty` | 3.5.0 | Hosts the :8080 query API (second `embeddedServer`) | Already the Phase-3 HTTP engine; one engine, one JVM. `[CITED: gradle/libs.versions.toml]` |
| `io.ktor:ktor-server-core` | 3.5.0 | Routing / `Application` | Already used by `installOtlp`. `[CITED: libs.versions.toml]` |
| `com.clickhouse:client-v2` | 0.9.8 | Read path via existing repository | No new SQL needed for DAG/saturation. `[CITED: libs.versions.toml]` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | `suspend` route handlers + `.await()` | Ecosystem rule; already wired. `[CITED: libs.versions.toml]` |
| `io.github.oshai:kotlin-logging-jvm` / `logback-classic` | 8.0.4 / 1.5.34 | Logging | Existing convention. `[CITED: libs.versions.toml]` |

### Supporting — NEW deps for `backend` (JSON query API)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | `@Serializable` DTOs for DAG/saturation/run-list payloads | Required — no JSON lib currently on the backend classpath. `[VERIFIED: Maven Central maven-metadata.xml, 2026-06-23]` |
| `io.ktor:ktor-server-content-negotiation` | 3.5.0 | Ktor `ContentNegotiation` plugin | Wires JSON into routes; **NOT currently a dependency**. `[VERIFIED: Maven Central, 2026-06-23]` |
| `io.ktor:ktor-serialization-kotlinx-json` | 3.5.0 | Bridges kotlinx-serialization to Ktor CN | Pairs with content-negotiation. `[VERIFIED: Maven Central, 2026-06-23]` |
| `org.jetbrains.kotlin.plugin.serialization` | 2.4.0 | Kotlin compiler plugin for `@Serializable` | Add to backend (and emitter) `plugins {}`; version = Kotlin version. `[ASSUMED]` (standard kotlinx-serialization requirement) |

### Supporting — `demo-emitter` module deps (catalog OTel entries, re-scoped to `implementation`)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.opentelemetry:opentelemetry-bom` | 1.63.0 | BOM governing the SDK + exporter versions | `platform(libs.otel.bom)`. Already `latest`/`release` on Central. `[VERIFIED: Maven Central opentelemetry-bom maven-metadata.xml, 2026-06-23]` |
| `io.opentelemetry:opentelemetry-sdk` | (BOM) | `SdkTracerProvider`, `Tracer`, `Span`, `Attributes` | Manual instrumentation to build the synthetic DAG. `[CITED: libs.versions.toml otel-sdk; opentelemetry.io/docs/languages/java/sdk]` |
| `io.opentelemetry:opentelemetry-exporter-otlp` | (BOM) | `OtlpHttpSpanExporter` (→ :4318) or `OtlpGrpcSpanExporter` (→ :4317) | The client transport the emitter posts with. `[CITED: libs.versions.toml otel-exporter-otlp]` |
| `io.github.unityinflow:agent-semconv` (project) | 0.0.1-SNAPSHOT | Attribute KEY constants (`GenAiAttributes`, `UnityInFlowAttributes`, `GenAiOperation`) | Emitter must set the **exact** convention keys — reuse the constants, never hardcode strings. `[CITED: agent-semconv/src/main/kotlin/...]` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Optional `runBlocking` shell for the one-shot `main` | Ecosystem rule. `[CITED: libs.versions.toml]` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Second `embeddedServer(Netty, 8080)` | Second Netty **connector** on the existing OTLP server | One server with two connectors is leaner, but the OTLP server installs raw-protobuf routes (no content-negotiation) — mixing a JSON CN plugin onto the same `Application` risks the CN plugin intercepting the `application/x-protobuf` OTLP route. A **separate `embeddedServer`** keeps the JSON plugin fully isolated from the protobuf receiver. **Recommend separate server.** |
| OTel SDK exporter (emitter) | Hand-build `ExportTraceServiceRequest` protobuf with the existing `opentelemetry-proto` dep + raw HTTP POST | Hand-building couples the emitter to receiver internals and re-implements batching/retry. The SDK exporter is the canonical client, handles OTLP framing, and is already in the catalog. **Recommend SDK exporter.** |
| `OtlpHttpSpanExporter` (:4318) | `OtlpGrpcSpanExporter` (:4317) | Both work; the Phase-3 ITs (`OtlpHttpReceiverIT`, `OtlpGrpcReceiverIT`) prove both receivers accept stock exporters. HTTP/4318 is simpler in Compose (one fewer port mapping). **Recommend HTTP; keep gRPC as a one-line swap.** |
| In-JVM DAG assembly | ClickHouse recursive CTE / `arrayJoin` tree build | SQL tree assembly is fragile, dialect-heavy, and can't easily express orphan/synthetic-root tolerance. Roadmap decision already locks server-side (Kotlin) assembly. **Recommend in-JVM.** |

**Installation (catalog additions):**
```toml
# [versions] — add
ktor = "3.5.0"  # already present
serialization = "1.11.0"  # NEW (kotlinx-serialization-json)

# [libraries] — add
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }

# [plugins] — add
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

**Version verification performed (2026-06-23):**
- `opentelemetry-bom` → `<latest>1.63.0</latest> <release>1.63.0</release>` (matches catalog) — VERIFIED.
- `ktor-serialization-kotlinx-json` → `<latest>3.5.0</latest>` — VERIFIED.
- `ktor-server-content-negotiation` → `<latest>3.5.0</latest>` — VERIFIED.
- `kotlinx-serialization-json` → `<latest>1.11.0</latest>` (matches coroutines line maturity) — VERIFIED.
- `opentelemetry-exporter-otlp` metadata fetch returned a stale/truncated list; its version is governed by the BOM, so no explicit pin is required (use `platform(otel-bom)`).

## Package Legitimacy Audit

All packages are first-party (JetBrains Kotlin/Ktor, OpenTelemetry) or already-vendored project modules. No third-party/community packages introduced. `gsd-tools` was not on PATH in this session, so the seam check could not be auto-run; verdicts below are from direct Maven Central `maven-metadata.xml` confirmation + provenance (all from official org coordinates).

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | Maven Central | mature (JetBrains) | github.com/Kotlin/kotlinx.serialization | OK | Approved |
| `io.ktor:ktor-server-content-negotiation` | Maven Central | mature (JetBrains) | github.com/ktorio/ktor | OK | Approved |
| `io.ktor:ktor-serialization-kotlinx-json` | Maven Central | mature (JetBrains) | github.com/ktorio/ktor | OK | Approved |
| `io.opentelemetry:opentelemetry-sdk` | Maven Central | mature (CNCF) | github.com/open-telemetry/opentelemetry-java | OK | Approved (already test-scoped in backend) |
| `io.opentelemetry:opentelemetry-exporter-otlp` | Maven Central | mature (CNCF) | github.com/open-telemetry/opentelemetry-java | OK | Approved (already test-scoped in backend) |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
                         docker compose up  (cold start, DIST-01 / D-07)
                                   │
        ┌──────────────────────────┼───────────────────────────────────┐
        ▼                          ▼                                     ▼
 ┌─────────────┐          ┌──────────────────┐                 ┌──────────────────┐
 │ clickhouse  │◀─health──│   tracer (JVM)   │◀──depends_on────│  demo-emitter    │
 │  :26.3 LTS  │  gate    │ eclipse-temurin  │  service_healthy│  (one-shot)      │
 │  :8123      │          │  21-jre          │  (tracer)       │  restart: "no"   │
 └─────────────┘          │                  │                 └────────┬─────────┘
        ▲                 │ migrate→serve    │                          │ OTLP/HTTP
        │ client-v2       │ (Phase-3 D-04)   │                          │ export
        │ (read+write)    │                  │   :4318  ◀───────────────┘ (full convention)
        │                 │  Receivers:      │   :4317
        └─────────────────│   :4318 OTLP/HTTP│
                          │   :4317 OTLP/gRPC│
                          │  NEW :8080 API   │◀──── curl  GET /api/runs?...           (API-03)
                          │  (2nd embedded   │            GET /api/runs/{id}/dag       (API-01/02)
                          │   Netty server)  │            GET /api/runs/{id}/saturation(API-04)
                          └────────┬─────────┘
                                   │ in-JVM, no new SQL
                  ┌────────────────┴─────────────────┐
                  ▼                                   ▼
        getTraceById(id) → List<SpanRow>      listRuns(RunFilter,limit,offset)
                  │                                   │
   ┌──────────────┴───────────────┐                  ▼
   ▼              ▼                ▼            RunListResponse (newest-first, paged)
 DagAssembler  saturation     rootStatus
 (nodes+edges, series         (outcome
  orphan-root, (ts,pct)        derivation)
  cost rollup)
```

### Recommended Project Structure
```
backend/src/main/kotlin/io/github/unityinflow/agenttracer/
├── app/
│   └── TracerApplication.kt        # EXTEND: bootReceivers starts a 2nd embeddedServer(:8080)
├── query/                          # NEW package — all in-JVM, no SQL
│   ├── QueryApi.kt                 # Route.queryApi(repo): the 3 routes + ContentNegotiation install
│   ├── DagAssembler.kt             # List<SpanRow> -> DagResponse (D-01/D-02/D-03)
│   ├── SaturationAssembler.kt      # List<SpanRow> -> SaturationResponse (D-04)
│   ├── RunListMapper.kt            # query params -> RunFilter; AgentRunRow -> RunSummary; outcome derive (D-05)
│   └── dto/                        # @Serializable immutable data classes
│       ├── DagResponse.kt          # DagNode, DagEdge
│       ├── SaturationResponse.kt   # SaturationPoint
│       └── RunListResponse.kt      # RunSummary + paging meta
└── storage/
    ├── RunFilter.kt                # (unchanged shape; paging lives in listRuns args)
    └── ClickHouseSpanRepository.kt # EXTEND listRuns: add limit/offset + DESC sort (D-05)

demo-emitter/                       # NEW Gradle module (add to settings.gradle.kts)
├── build.gradle.kts               # kotlin-jvm + kotlin-serialization; otel-bom/sdk/exporter; project(:agent-semconv)
└── src/main/kotlin/io/github/unityinflow/agenttracer/demo/
    ├── DemoEmitter.kt              # fun main(): build N seeded runs, export, flush, exit
    ├── RunScenario.kt             # sealed model of a realistic run shape (plan→skills→subagents→tools)
    └── SeededRunFactory.kt        # Random(seed)-driven span tree generator

docker/                            # NEW (DIST-01)
├── compose.yaml                   # clickhouse + tracer + demo-emitter (seeder)
├── tracer.Dockerfile              # eclipse-temurin:21-jre, runs the backend fat/app jar
└── emitter.Dockerfile             # eclipse-temurin:21-jre, runs the demo-emitter jar
```

### Pattern 1: Query-time flat DAG assembly with orphan tolerance (D-01/D-02)
**What:** Transform `List<SpanRow>` into `{nodes:[{id,parentId,kind,costUsd,tokens,group,...}], edges:[{from,to}]}` in one pass, never assuming parents arrive before children or at all.
**When to use:** `GET /api/runs/{id}/dag`.
**Approach (grounded in real `SpanRow` fields):**
```kotlin
// Source: derived from SpanRow (storage/SpanRow.kt) — fields verified this session.
// kind enumeration maps from SpanRow.operation (GenAiOperation.name) — "" sentinel => "unknown".
// group marker := operation == "INVOKE_AGENT" (a subagent/agent node is a compound group).
fun assemble(spans: List<SpanRow>): DagResponse {
    val byId = spans.associateBy { it.spanId }                 // spanId never empty (SpanContext)
    val nodes = spans.map { s ->
        val orphan = s.parentSpanId.isNotEmpty() && s.parentSpanId !in byId
        DagNode(
            id = s.spanId,
            // orphan => reparent to synthetic root; true root (parentSpanId=="") => null
            parentId = when {
                s.parentSpanId.isEmpty() -> null
                orphan -> SYNTHETIC_ROOT_ID
                else -> s.parentSpanId
            },
            kind = s.operation.ifEmpty { "unknown" },          // GenAiOperation.name or sentinel
            costUsd = s.costUsd,                                // Nullable -> JSON null when unmeasured
            tokens = (s.inputTokens ?: 0) + (s.outputTokens ?: 0),
            group = s.operation == "INVOKE_AGENT",
            orphaned = orphan,
            startTimeUnixNanos = s.timestamp.toEpochNanos(),    // ordering, not parent-presence
            durationNanos = s.duration,
            status = s.statusCode,
            agentName = s.agentName.ifEmpty { null },
            skillName = s.skillName.ifEmpty { null },
        )
    }
    val hasOrphans = nodes.any { it.orphaned }
    val allNodes = if (hasOrphans) nodes + syntheticRootNode() else nodes
    // edges only for resolvable parents (incl. synthetic root); never drop a node (SC1)
    val edges = allNodes.mapNotNull { n -> n.parentId?.let { DagEdge(from = it, to = n.id) } }
    return DagResponse(nodes = allNodes, edges = edges, rolledUp = rollUpCosts(allNodes, edges))
}
```
**Late/out-of-order tolerance:** because assembly is a set operation over the full returned list (no streaming, no "first span is root" assumption) and ordering is derived from `timestamp`, late/reordered arrival is irrelevant at query time — every span present in ClickHouse at read time is placed. (Phase-2 explicitly deferred dedup/late-span handling to Phase 4 per STATE `[02-05]`.)

### Pattern 2: Subtree cost roll-up on group/agent nodes (D-03/API-02)
**What:** Each agent (`group`) node carries the sum of its own + all descendants' `costUsd`.
**Approach:** Build a children-adjacency from edges, then post-order DFS accumulate. Guard against cycles (a malformed/late graph could in theory self-reference) with a `visited` set — never recurse unbounded.
```kotlin
// O(n) post-order over the adjacency built from edges; cycle-guarded (visited set).
// Returns map<nodeId, subtreeCostUsd>; only group nodes need it surfaced, but compute for all.
```
**Pitfall:** orphan subtree costs must roll up into the **synthetic root**, not vanish — include the synthetic root in the adjacency.

### Pattern 3: Saturation timeline as a separate projection (D-04/API-04)
```kotlin
// Source: SpanRow.contextSaturationPct is Float? (storage/SpanRow.kt line 54, ContextSaturationPct Nullable(Float32)).
fun timeline(spans: List<SpanRow>): SaturationResponse =
    SaturationResponse(
        points = spans.asSequence()
            .filter { it.contextSaturationPct != null }
            .map { SaturationPoint(it.timestamp.toEpochMilli(), it.contextSaturationPct!!) } // !! safe: filtered non-null
            .sortedBy { it.timestampMillis }
            .toList(),
    )
```
**Note the `!!`:** allowed only with the safety comment (CLAUDE.md). Prefer `mapNotNull { p -> p.contextSaturationPct?.let { SaturationPoint(...) } }` to avoid `!!` entirely — **recommend the `mapNotNull` form**.
**Emitter dependency:** saturation is emitter-gated (STATE blocker) — the timeline is empty until something emits `unityinflow.context.saturation_pct`. The demo emitter (TOOL-01) is the mitigation; SC3 validation must seed saturation values.

### Pattern 4: Run-list paging + outcome derivation (D-05/API-03)
**Map query params → `RunFilter`** (exact existing field names: `agentName`, `model`, `costMin`, `costMax`, `outcome`, `dateFrom`, `dateTo` — `Instant` for dates). **Add to `listRuns`** a `limit: Int`, `offset: Int`, and DESC sort. Current SQL is `ORDER BY StartTime, TraceId` (ascending, unbounded) — change to:
```sql
ORDER BY StartTime DESC, TraceId DESC
LIMIT {limit:UInt32} OFFSET {offset:UInt32}
```
**`outcome` derivation (D-05):** the list response's ok/error is "derived from the root span status." `AgentRunRow.outcome` already exists (anyLast(Outcome) rollup) — but D-05 wants **root-span** status. Two options to surface in planning:
- (A) Use the existing `agent_runs.Outcome` rollup column (cheap, already there) — but it's `anyLast`, not guaranteed root.
- (B) For the DAG endpoint, derive `ok/error` from the assembled root node's `statusCode`. **Recommend:** list endpoint uses `AgentRunRow.outcome` (acceptable proxy, no extra query); the per-run DAG response additionally exposes the true root-span status. Flag this as the one place D-05's "root-span status" is approximated at list scale — confirm acceptable with the planner.

### Pattern 5: Demo emitter as OTel SDK client (D-06/TOOL-01)
```kotlin
// Source: opentelemetry.io/docs/languages/java/sdk — manual instrumentation.
// Build a tracer wired to OtlpHttpSpanExporter(:4318), emit a seeded synthetic DAG, flush, exit.
fun main() {
    val endpoint = System.getenv("OTLP_ENDPOINT") ?: "http://localhost:4318/v1/traces"
    val seed = System.getenv("DEMO_SEED")?.toLong() ?: 42L
    val runCount = System.getenv("DEMO_RUNS")?.toInt() ?: 20
    val rng = kotlin.random.Random(seed)

    val exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()
    val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        .setResource(Resource.create(Attributes.of(stringKey("service.name"), "demo-agent")))
        .build()
    val tracer = tracerProvider.get("demo-emitter")

    repeat(runCount) { generateRun(tracer, rng) }   // each: invoke_agent root → plan → skills → subagents → tools

    tracerProvider.shutdown().join(30, TimeUnit.SECONDS)  // bounded flush; one-shot exit
}
```
**Attribute convention (MUST use `agent-semconv` constants):** set `GenAiAttributes.OPERATION_NAME` (`invoke_agent`/`execute_tool`/`chat`/`plan`), `AGENT_NAME`, `REQUEST_MODEL`/`RESPONSE_MODEL`, `USAGE_INPUT_TOKENS`/`USAGE_OUTPUT_TOKENS`, `TOOL_NAME`; and `UnityInFlowAttributes.SKILL_NAME`, `CONTEXT_SATURATION_PCT` (0–100 double), `COST_USD`, `PLAN_STEP`, `OUTCOME`, `SCHEMA_VERSION`. **Subagents** = nested `invoke_agent` spans with `setParent(Context.current().with(parentSpan))`. This exercises the full convention end-to-end (SC4) and is the only producer of saturation data (mitigates the STATE saturation blocker).

### Pattern 6: Compose cold-start with healthcheck gating (D-07/DIST-01)
```yaml
# Source: Docker Compose depends_on long-form + healthcheck (standard).
services:
  clickhouse:
    image: clickhouse/clickhouse-server:26.3   # LTS pin, never :latest
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8123/ping"]
      interval: 5s
      timeout: 3s
      retries: 20
  tracer:
    build: { context: .., dockerfile: docker/tracer.Dockerfile }
    environment:
      CLICKHOUSE_URL: "http://clickhouse:8123/"
    depends_on:
      clickhouse: { condition: service_healthy }   # gate migrate→serve on a live CH (Phase-3 D-04)
    ports: ["4318:4318", "4317:4317", "8080:8080"]
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1:8080/healthz"]  # NEW route needed
      interval: 5s
      timeout: 3s
      retries: 20
  demo-emitter:                                    # one-shot seeder
    build: { context: .., dockerfile: docker/emitter.Dockerfile }
    environment:
      OTLP_ENDPOINT: "http://tracer:4318/v1/traces"
    depends_on:
      tracer: { condition: service_healthy }       # fire only after tracer serves
    restart: "no"                                   # run once, then exit (SC5)
```
**Two derived requirements for planning:**
1. The tracer needs a **liveness route** (`GET /healthz` on :8080) for its own healthcheck — currently there is none; add it alongside the query API. (Discretionary route name/path.)
2. `eclipse-temurin:21-jre` has no `curl`; `wget --spider` is present in the ClickHouse image but **not** in temurin-jre. The tracer healthcheck may need a JVM/`/proc`-based check or installing a tiny tool — **flag: choose a healthcheck mechanism that exists in `eclipse-temurin:21-jre`** (e.g. a `HEALTHCHECK` using a bundled shell `curl`-free check, or add `busybox-wget`). Open question below.

### Anti-Patterns to Avoid
- **Putting DAG/saturation assembly in `storage/` or in SQL** — violates the "storage is the sole SQL holder" seam and the locked server-side-assembly decision. Keep it in `query/`.
- **Reusing `opentelemetry-proto` (receiver artifact) in the emitter** — that's the server's input shape; the emitter is a client → OTel SDK exporter.
- **Installing `ContentNegotiation(json)` on the OTLP `Application`** — risks intercepting the `application/x-protobuf` receiver route. Use a separate `embeddedServer`.
- **`ORDER BY StartTime ASC` for the run list** — D-05 mandates newest-first; the current `listRuns` is ascending and unbounded.
- **Dropping orphaned spans** — explicit SC1 violation.
- **Unbounded recursion in cost roll-up** — guard with a visited set (late/malformed graphs).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| OTLP export from the emitter | Hand-rolled `ExportTraceServiceRequest` protobuf + raw HTTP | `OtlpHttpSpanExporter` (OTel SDK) | Batching, retry, framing, content-type all handled; canonical client. |
| JSON serialization | Manual string building / Gson | `kotlinx-serialization-json` + Ktor CN | Compile-time `@Serializable`, matches ecosystem TS/Kotlin convention; nullable handling for `costUsd?`. |
| Span tree layout | Custom layout math in the API | (deferred) `elkjs` in Phase 5 | API returns flat nodes/edges precisely so elkjs lays out later (D-01). API must NOT compute positions. |
| ClickHouse trace fetch | New SQL | Existing `getTraceById` | Already does the STOR-02 two-step pruned scan. |
| Run filtering SQL | New WHERE builder | Existing `listRuns` + `RunFilter` | Parameterized, injection-safe; only add LIMIT/OFFSET/DESC. |
| Container readiness ordering | Sleep/retry loops in entrypoints | Compose `depends_on: condition: service_healthy` | Declarative gating respects migrate-then-serve. |

**Key insight:** Phase 4 is ~80% wiring of already-built seams. The only genuinely new *logic* is the in-JVM DAG/cost/saturation transforms and the seeded run generator — everything else (storage reads, OTLP transport, JSON) is library or existing code.

## Common Pitfalls

### Pitfall 1: Assuming content-negotiation is already wired
**What goes wrong:** Plan reuses a non-existent JSON setup; routes return 406/500.
**Why:** CONTEXT says "kotlinx-serialization + content-negotiation already wired," but the backend `build.gradle.kts` has **no** `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`, or `kotlinx-serialization-json`, and the only route (`otlpTraces`) uses raw `respondBytes`/protobuf. **The JSON stack is net-new for Phase 4.**
**Avoid:** Add the 3 deps + the serialization compiler plugin; install `ContentNegotiation { json() }` on the new :8080 server only.
**Warning signs:** `@Serializable` won't compile (missing plugin); `call.respond(dto)` 500s (no CN).

### Pitfall 2: `SpanRow` is mutable but DTOs must not be
**What goes wrong:** Copying `var` POJO style into API DTOs violates the no-`var` rule.
**Why:** `SpanRow`/`PayloadRow` are sanctioned `var` exceptions *only* because client-v2 reflection needs setters. API DTOs have no such constraint.
**Avoid:** All `query/dto/*` types are immutable `val` `@Serializable data class`es.

### Pitfall 3: Two `embeddedServer`s, one lifecycle/drain
**What goes wrong:** The new :8080 server isn't stopped on SIGTERM, leaking a port or losing the clean-shutdown property.
**Why:** `RunningReceivers.shutdown()` (TracerApplication.kt) currently stops `grpc` + `ktor` (the :4318 receiver) only. A second server must be added to `RunningReceivers` and stopped in `shutdown()`.
**Avoid:** Add `val queryServer: EmbeddedServer<...>` to `RunningReceivers`; `queryServer.stop(...)` in the drain sequence (ports-first, before writer drain). The query server has no writer/intake, so its ordering is simple — stop it alongside `ktor`.
**Warning signs:** `docker compose down` hangs; port 8080 still bound after stop.

### Pitfall 4: Healthcheck binary not in the base image
**What goes wrong:** Tracer healthcheck always fails → `demo-emitter` never starts → SC5 fails.
**Why:** `eclipse-temurin:21-jre` ships no `curl`/`wget`.
**Avoid:** Pick a healthcheck that works in temurin-jre (e.g. a no-extra-binary check, or add a minimal `/healthz` checked by a shell `exec 3<>/dev/tcp/...`, or bake a tiny static `curl`). Decide in planning (open question).
**Warning signs:** `tracer` container shows `health: starting` forever.

### Pitfall 5: Testcontainers Ryuk blocked in sandbox (carry-forward)
**What goes wrong:** Integration tests fail with `ContainerLaunchException: testcontainers/ryuk:0.12.0` — infra, not code (documented across Phases 2/3).
**Avoid:** Split validation into Docker-free (pure-JVM assembler tests, Ktor `testApplication` route tests with a faked repo) vs Docker-required (full ClickHouse round-trip, Compose cold-start). The Phase-4 Compose stack on a Linux runner is the natural place to finally observe the deferred Phase-2/3 ITs green (CONTEXT carry-forward).

### Pitfall 6: Saturation timeline empty without an emitter
**What goes wrong:** SC3 saturation validation passes against zero data, proving nothing.
**Avoid:** The demo emitter MUST set `unityinflow.context.saturation_pct` on multiple spans per run; SC3's saturation test asserts a non-empty, monotonic-ish series from seeded data.

## Code Examples

### Existing signatures to build on (verified this session)
```kotlin
// storage/SpanRepository.kt
suspend fun getTraceById(traceId: String): Result<List<SpanRow>>       // DAG + saturation source (API-01/02/04)
suspend fun listRuns(filter: RunFilter): Result<List<AgentRunRow>>     // run list (API-03) — ADD limit/offset/DESC

// storage/RunFilter.kt  (immutable data class; map query params to these exact fields)
RunFilter(agentName: String?, model: String?, costMin: Double?, costMax: Double?,
          outcome: String?, dateFrom: Instant?, dateTo: Instant?)

// storage/SpanRow.kt  (DAG node sources — all present)
var spanId: String; var parentSpanId: String; var operation: String        // "" sentinel or GenAiOperation.name
var costUsd: Double?; var inputTokens: Long?; var outputTokens: Long?
var contextSaturationPct: Float?; var timestamp: Instant; var duration: Long
var statusCode: String; var agentName: String; var skillName: String; var outcome: String

// storage/AgentRunRow.kt  (run-list response source — immutable)
AgentRunRow(traceId, agentName, model, outcome, startTime, endTime,
            inputTokens, outputTokens, costUsd, maxSaturation, spanCount)
```

### Extending `bootReceivers` for the :8080 query server
```kotlin
// app/TracerApplication.kt — add inside bootReceivers, after `ktor` starts:
val queryServer = embeddedServer(Netty, port = 8080) {
    install(ContentNegotiation) { json() }
    routing {
        get("/healthz") { call.respondText("ok") }
        queryApi(repository)            // the 3 query routes, repository already constructed above
    }
}.start(wait = false)
// add queryServer to RunningReceivers; stop it in shutdown() alongside ktor.stop(...)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Server returns a nested tree (early roadmap note) | Flat `nodes[]+edges[]` with compound `group` nodes | CONTEXT D-01 (2026-06-23) | API output is elkjs-ready; assembler emits a flat list, not a tree. (STATE roadmap "nested tree" note is superseded by D-01.) |
| `listRuns` ascending + unbounded (Phase 2) | newest-first + offset/limit (D-05) | Phase 4 | Add paging args; change ORDER BY direction. |
| Late/out-of-order/orphan dedup deferred (STATE `[02-05]`) | Handled at query-time assembly (D-02) | Phase 4 | DAG assembler is the agreed home for this deferred concern; no storage change. |

**Deprecated/outdated:**
- The roadmap "frontend receives a render-ready nested tree" phrasing — superseded by the flat-payload D-01. Plan to the CONTEXT, not the older STATE note.

## Validation Architecture

> Nyquist validation is ENABLED. Test framework is in place (JUnit 5 + Kotest, Testcontainers) from Phases 1–3.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 5.14.4 + Kotest assertions 6.1.11 (`tasks.test { useJUnitPlatform() }`) |
| Config file | `backend/build.gradle.kts` (`tasks.test`); new `demo-emitter/build.gradle.kts` mirrors it |
| Quick run command | `./gradlew :backend:test --tests "*Dag*" --tests "*Saturation*" --tests "*RunList*"` (pure-JVM, Docker-free) |
| Full suite command | `./gradlew test` (includes Testcontainers ITs — Docker-gated) |
| Route tests | `ktor-server-test-host` `testApplication {}` (already a backend test dep) — drive the 3 routes against a fake/`mockk` repository, no Docker |

### Success Criteria → Test Map
| SC | Behavior | Test Type | Automated Command | Docker? | File (Wave 0 if ❌) |
|----|----------|-----------|-------------------|---------|---------------------|
| SC1 | Orphan-tolerant DAG assembly (orphans returned via synthetic root, late/out-of-order placed) | unit | `./gradlew :backend:test --tests "*DagAssembler*"` | no | ❌ Wave 0 `DagAssemblerTest.kt` |
| SC2 | Subtree cost roll-ups on group/agent nodes | unit | `./gradlew :backend:test --tests "*DagAssembler*rollup*"` | no | ❌ Wave 0 (same file) |
| SC3a | Filtered run list (params→RunFilter, newest-first, paged) | unit + IT | `./gradlew :backend:test --tests "*RunList*"` then full IT | mixed | ❌ Wave 0 `RunListMapperTest.kt`; IT extends `SpanRepositoryIT` |
| SC3b | Saturation timeline series | unit | `./gradlew :backend:test --tests "*Saturation*"` | no | ❌ Wave 0 `SaturationAssemblerTest.kt` |
| SC3-route | All 3 routes return correct JSON / status / paging | Ktor `testApplication` | `./gradlew :backend:test --tests "*QueryApi*"` | no | ❌ Wave 0 `QueryApiTest.kt` (fake repo) |
| SC4 | Demo emitter emits N seeded realistic runs over OTLP, full convention | unit + IT | `./gradlew :demo-emitter:test` (seed→deterministic tree); IT exports to a real receiver | mixed | ❌ Wave 0 `SeededRunFactoryTest.kt`; optional `EmitterRoundTripIT` reuses Phase-3 `OtlpHttpReceiverIT` pattern |
| SC5 | Cold `docker compose up` ingests end-to-end + serves queries | e2e Compose smoke | `docker compose -f docker/compose.yaml up -d && <poll /api/runs until non-empty>` | **YES (Linux Docker host)** | ❌ Wave 0 `scripts/compose-smoke.sh` |

### Sampling Rate
- **Per task commit:** `./gradlew :backend:test --tests "*Dag*" --tests "*Saturation*" --tests "*RunList*" --tests "*QueryApi*"` + `ktlint` (all Docker-free, < 30s).
- **Per wave merge:** `./gradlew test` (full backend + emitter, including Testcontainers where Docker available).
- **Phase gate:** Compose smoke (SC5) on a Linux Docker host / `arc-runner-unityinflow`; this also finally observes the deferred Phase-2/3 ClickHouse ITs green (closes INGEST-01/02/03 carry-forward).

### Docker-free vs Docker-required (Ryuk caveat from Phases 2–3)
- **Docker-free (run anywhere, incl. sandbox):** all assembler unit tests (`DagAssembler`, `SaturationAssembler`, `RunListMapper`), the Ktor `testApplication` route tests against a `mockk`/fake `SpanRepository`, and the seeded-run-factory determinism test. **These cover SC1, SC2, SC3a-mapping, SC3b, SC3-route, SC4-determinism without Docker** — the bulk of correctness.
- **Docker-required (Linux Docker host only):** the `listRuns` paging/sort IT against real ClickHouse, the emitter round-trip IT, and the SC5 Compose smoke. In the sandbox these fail solely with `ContainerLaunchException: testcontainers/ryuk:0.12.0` (infra). Observe green on CI / native Docker — same gate-closure path as `[02-05]`/`[03-04]`.

### Wave 0 Gaps
- [ ] `DagAssemblerTest.kt` — SC1 (orphan/synthetic-root, late/out-of-order) + SC2 (cost roll-up, cycle guard)
- [ ] `SaturationAssemblerTest.kt` — SC3b (filter nulls, sort by ts)
- [ ] `RunListMapperTest.kt` — SC3a (param→RunFilter, outcome derivation, paging defaults/caps)
- [ ] `QueryApiTest.kt` — Ktor `testApplication` over a fake repo (status codes, JSON shape, 404 unknown run)
- [ ] `SeededRunFactoryTest.kt` — SC4 (same seed → identical span tree; full convention attrs set)
- [ ] `scripts/compose-smoke.sh` — SC5 cold-start poll
- [ ] `demo-emitter` test source set + Gradle config (new module)
- Framework install: none — JUnit 5/Kotest/Testcontainers/ktor-test-host all present in `backend`; `demo-emitter` reuses the catalog test deps.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 21 | Build/test (jvmToolchain 21) | ✓ (Gradle toolchain) | 21 | Gradle auto-provisions |
| Gradle | Build | ✓ | 9.5.1 (wrapper) | — |
| Docker / Compose | SC5 cold-start, Testcontainers ITs | ✗ in sandbox (Ryuk blocked) | — | Run on `arc-runner-unityinflow` / native Docker host; Docker-free tests cover correctness |
| `eclipse-temurin:21-jre` | Tracer + emitter images | (pull at build) | 21-jre | pinned base; no `curl`/`wget` → healthcheck design constraint (Pitfall 4) |
| `clickhouse/clickhouse-server:26.3` | Compose storage | (pull at build) | 26.3 LTS | pin, never `:latest` |

**Missing dependencies with no fallback:** none (SC5 requires Docker but has a CI fallback).
**Missing dependencies with fallback:** Docker (sandbox) → CI Linux runner.

## Security Domain

> `security_enforcement` not explicitly false in config; included. Auth is explicitly OUT of scope (CONTEXT), so V2/V3/V4 are deferred by decision, but input-validation and the read-only surface still apply.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (descoped) | — (deferred to a later milestone; STACK.md transport-agnostic validator) |
| V3 Session Management | no | stateless read API |
| V4 Access Control | no (single-team local tool) | — |
| V5 Input Validation | **yes** | Coerce + bound all query params before building `RunFilter`: parse `costMin/Max` as `Double?` (reject NaN/Inf), `dateFrom/To` as ISO-8601 `Instant`, `limit` clamped to a max cap (e.g. ≤ 500), `offset ≥ 0`. Bind every value as a client-v2 `{name:Type}` placeholder (already the repository's invariant — never interpolate). `{id}` path param passed only as a placeholder to `getTraceById`. |
| V6 Cryptography | no | no crypto in this phase |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL injection via filter/path params | Tampering | client-v2 `{name:Type}` placeholders (repository invariant T-02-06); query layer never builds SQL |
| Unbounded result / DoS via huge `limit` | DoS | Clamp `limit` to a max cap; default page size; reject negative offset |
| Resource exhaustion on huge trace DAG | DoS | The 14-day TTL + per-run span bound limits trace size; assembler is O(n) single-pass; cycle-guard the roll-up |
| Secrets in Compose | Info Disclosure | ClickHouse creds via env only (`CLICKHOUSE_*`); never bake into image/compose file committed to git |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `org.jetbrains.kotlin.plugin.serialization` (version = Kotlin 2.4.0) is required for `@Serializable` | Standard Stack | Low — universally true for kotlinx-serialization; build fails fast if mis-versioned |
| A2 | `eclipse-temurin:21-jre` lacks `curl`/`wget` (healthcheck constraint) | Pitfall 4 / Compose | Low-Med — if a usable binary exists the healthcheck simplifies; verify at Dockerfile time |
| A3 | Using `agent_runs.Outcome` (anyLast) as the list-level ok/error proxy is acceptable vs D-05's literal "root-span status" | Pattern 4 | Med — if planner requires true root-span status at list scale, a per-run root lookup or a schema addition is needed; flagged as Open Question |
| A4 | `opentelemetry-exporter-otlp` version is fully governed by `otel-bom` 1.63.0 (no explicit pin) | Standard Stack | Low — standard BOM usage; the catalog already declares it version-less |
| A5 | A second `embeddedServer(Netty, 8080)` coexists cleanly with the :4318 receiver server in one JVM | Architecture | Low — Netty supports multiple servers; Phase-3 already runs Ktor-Netty + grpc-netty-shaded together |

## Open Questions

1. **List-level `outcome` source (D-05 "root-span status").**
   - What we know: `AgentRunRow.outcome` exists (anyLast rollup); true root status needs the root span.
   - What's unclear: whether the anyLast proxy satisfies D-05 at list scale, or a root-span derivation is mandatory.
   - Recommendation: list endpoint uses `AgentRunRow.outcome`; DAG endpoint exposes true root status. Confirm with planner (A3).
2. **Tracer healthcheck mechanism in `eclipse-temurin:21-jre`.**
   - What we know: temurin-jre likely has no `curl`/`wget`; the emitter must only start after the tracer is *healthy* (D-07).
   - Recommendation: add a `GET /healthz` route on :8080 and pick a check that works in the base image (bash `/dev/tcp`, a baked busybox, or a tiny JVM health-probe). Decide in planning.
3. **`--profile demo` (discretionary).**
   - Whether to gate the seeder behind a Compose profile so production `up` is demo-free. Default per D-07 is seed-on-`up`; offering `--profile demo` is a low-cost nice-to-have.
4. **Demo realism depth (SC4).**
   - How elaborate the seeded runs must be (how many subagent levels, skill variety, error/partial outcomes, saturation curve shape) to count as "realistic." Recommend: ≥2 subagent levels, ≥3 skills, a mix of ok/error outcomes, a rising saturation series per run — confirm threshold with planner.

## Sources

### Primary (HIGH confidence)
- Files read this session (authoritative, current code):
  - `backend/src/main/kotlin/.../storage/ClickHouseSpanRepository.kt` — `getTraceById`/`listRuns`/`getPayload` signatures + current `ORDER BY StartTime, TraceId` (ascending, unbounded)
  - `backend/src/main/kotlin/.../storage/{SpanRow,AgentRunRow,PayloadRow,RunFilter,SpanRepository}.kt` — field/type inventory for DAG/saturation/list DTOs
  - `backend/src/main/kotlin/.../app/TracerApplication.kt` — `bootReceivers`/`RunningReceivers`/`shutdown` lifecycle to extend with :8080
  - `backend/src/main/kotlin/.../ingest/{ReceiverConfig,OtlpHttpRoutes}.kt` + `ClickHouseConfig.kt` — ports, env, raw-protobuf route (no CN)
  - `agent-semconv/src/main/kotlin/.../{GenAiAttributes,UnityInFlowAttributes,GenAiOperation}.kt` — exact emitter attribute keys
  - `backend/src/main/resources/migrations/{001_spans,004_agent_runs_mv}.sql` — `ContextSaturationPct Nullable(Float32)`, `agent_runs` columns
  - `gradle/libs.versions.toml`, root + `backend/build.gradle.kts`, `settings.gradle.kts` — catalog/version state
- Maven Central `maven-metadata.xml` (fetched 2026-06-23): `opentelemetry-bom` 1.63.0; `ktor-serialization-kotlinx-json` 3.5.0; `ktor-server-content-negotiation` 3.5.0; `kotlinx-serialization-json` 1.11.0

### Secondary (MEDIUM confidence)
- [OpenTelemetry Java SDK docs](https://opentelemetry.io/docs/languages/java/sdk/) — `SdkTracerProvider` + `BatchSpanProcessor` + `OtlpHttpSpanExporter` manual-instrumentation pattern for the emitter
- Docker Compose `depends_on: condition: service_healthy` + `healthcheck` long-form (standard Compose spec)

### Tertiary (LOW confidence)
- `opentelemetry-exporter-otlp` standalone metadata fetch returned a stale/truncated version list; relied on the BOM instead (A4)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all backend libs in the catalog; 3 JSON deps verified on Central; OTel deps already test-scoped.
- Architecture: HIGH — grounded in actual signatures read this session; only assembly logic is net-new.
- Pitfalls: HIGH — derived from real gaps in the current code (no CN, ascending `listRuns`, single-server drain, jre base image).
- Compose/healthcheck: MEDIUM — standard pattern; base-image healthcheck binary is the one unverified detail (A2/Q2).

**Research date:** 2026-06-23
**Valid until:** 2026-07-23 (stack is pinned/stable; re-verify only if the catalog or ClickHouse LTS line moves)
