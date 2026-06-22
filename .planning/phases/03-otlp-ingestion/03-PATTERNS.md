# Phase 3: OTLP Ingestion - Pattern Map

**Mapped:** 2026-06-22
**Files analyzed:** 11 (6 main + 5 test)
**Analogs found:** 6 strong / 11 (5 files are net-new transport types with no in-repo analog — see "No Analog Found")

> **Read this first (planner):** Phase 3 is glue, not greenfield logic. Every new file either (a) *calls* an existing Phase-1/Phase-2 seam, or (b) is a transport type the repo has never had (Ktor route, gRPC service, `fun main`). For (a), copy the exact call shape from the analogs below. For (b), the in-repo analog only governs *style* (KDoc density, `Result`/sealed-class error handling, coroutine-`.await()` bridging, no-`var`); the transport mechanics come from RESEARCH §Patterns 2-4.

---

## File Classification

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `ingest/OtlpDecoder.kt` | service (transport-agnostic decode) | transform (OTLP tree → envelopes) | `convention/AgentSpanExtractor.kt` | role-match (both are pure, no-I/O, never-throw transformers over OTLP) |
| `ingest/DecodeResult.kt` | model (decode result + reject accounting) | transform | `storage/pipeline/SpanWriter.SpanEnvelope` + `SpanContext.kt` | exact (immutable data class, identical idiom) |
| `ingest/ReceiverConfig.kt` | config (host/ports, gRPC max-inbound, Retry-After) | request-response | `storage/ClickHouseConfig.kt` + `SpanWriter.Config.fromEnv` | exact (env-sourced immutable config + `fromEnv`) |
| `ingest/OtlpResponses.kt` | utility (build `ExportTraceServiceResponse` / status mapping) | transform | `storage/SpanRow.from` (pure builder factory) | role-match (pure projection/builder) |
| `ingest/OtlpHttpRoutes.kt` | route (Ktor `POST /v1/traces` :4318) | request-response | — (no Ktor in repo yet) | **no analog** (style only) |
| `ingest/OtlpGrpcService.kt` | service (gRPC `TraceServiceImplBase` :4317) | request-response + event-driven (callback→coroutine bridge) | `storage/migrations/MigrationRunner` (coroutine `.await()` bridge) for *bridge style only* | **no analog** for gRPC; partial-match for the async-bridge idiom |
| `app/TracerApplication.kt` (`fun main`) | config / bootstrap (migrate→serve→drain) | event-driven (lifecycle) | `MigrationRunner.runMigrations` call site + `SpanWriter` construction in `SpanRepositoryIT.applySchema` | partial (no existing `main`; lifecycle pieces all exist) |
| `OtlpDecoderTest.kt` | test (unit, pure JVM) | transform | `convention/AgentSpanExtractor*Test.kt` family | exact |
| `OtlpHttpRoutesTest.kt` | test (unit, ktor-server-test-host) | request-response | — (no Ktor test in repo) | **no analog** (style only) |
| `OtlpGrpcServiceTest.kt` | test (unit, in-process gRPC channel) | request-response | — | **no analog** (style only) |
| `Otlp{Http,Grpc}ReceiverIT.kt`, `TracerApplicationIT.kt` | test (integration, Testcontainers) | request-response | `storage/SpanRepositoryIT.kt` + `ClickHouseTestSupport.kt` | exact (reuse the same container fixture + poll-for-visibility idiom) |

---

## Pattern Assignments

### `ingest/OtlpDecoder.kt` (service, transform)

**Analog:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt`

**What to copy — the never-throw, pure-transformer contract** (AgentSpanExtractor lines 9-21): the decoder must NOT throw on malformed input. It relies on `AttrView` returning `null` and counts an unmappable span into `rejectedSpans` (D-02), never propagating an exception. Mirror this KDoc-stated invariant.

**Seam call #1 — build the AttrView from OTLP `KeyValue` protos** (AttrView.kt lines 47-61). `AttrView` consumes OTLP protos directly — do NOT build a second attribute model:
```kotlin
// span attrs
val attrs = AttrView.of(span.attributesList)        // List<KeyValue> → AttrView
// resource attrs — guard the proto default with hasResource()
val resourceView =
    if (resourceSpans.hasResource()) AttrView.of(resourceSpans.resource.attributesList)
    else AttrView.empty()
```

**Seam call #2 — the extractor bridge** (AgentSpanExtractor.kt lines 32-36). The decoder calls `extract` with the OTel status mapped to a boolean (NOT the proto Status object):
```kotlin
val isError = span.status.code == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR
val agentSpan = AgentSpanExtractor.extract(attrs, resourceView, otelStatusIsError = isError)
```

**Seam call #3 — SpanContext identity, D-07 EXACT hex format.** See "Shared Patterns → D-07 Hex Encoding" below — this is the one place a wrong choice silently breaks `getTraceById`. The decoder fills `SpanContext` (SpanContext.kt lines 24-29):
```kotlin
val context = SpanContext(
    traceId = span.traceId.toHex(),                                   // 32 lowercase hex chars
    spanId = span.spanId.toHex(),                                     // 16 lowercase hex chars
    parentSpanId = if (span.parentSpanId.isEmpty) "" else span.parentSpanId.toHex(),  // "" for root
    timestamp = Instant.ofEpochSecond(0, span.startTimeUnixNano.toLong()),            // start time (A2)
)
```

**Rejection rule (D-02)** — a span is rejected ONLY when it cannot become a valid row: `if (span.traceId.isEmpty || span.spanId.isEmpty) return null /* → rejected++ */`. Oversized payloads are NEVER rejected here — the Phase-2 `AgentSpanRowMapper` 256 KB cap (downstream of `SpanWriter.send`) truncates them. Do not add a size check in the decoder.

**Envelope type** — emit `SpanWriter.SpanEnvelope(agentSpan, context)` (SpanWriter.kt lines 61-65), the existing intake record. Do not invent a new pair type for the decoder's accepted list.

---

### `ingest/DecodeResult.kt` (model, transform)

**Analog:** `storage/SpanContext.kt` (lines 24-29) — immutable data class with KDoc per property.

```kotlin
data class DecodeResult(
    val accepted: List<SpanWriter.SpanEnvelope>,
    val rejectedSpans: Long,
    val errorMessage: String?,
)
```
Match the repo idiom: `data class`, all `val`, one-line KDoc per property. `rejectedSpans == 0` ⇒ `errorMessage == null` ⇒ caller leaves `partial_success` UNSET (spec full-success).

---

### `ingest/ReceiverConfig.kt` (config, request-response)

**Analog (primary):** `storage/ClickHouseConfig.kt` — immutable data class + `companion object` with `ENV_*` constants + `fromEnv(env: (String) -> String? = System::getenv)`.

**Analog (secondary, for numeric env knobs):** `SpanWriter.Config.fromEnv` (SpanWriter.kt lines 90-103) — the `env[...]?.toIntOrNull() ?: default` idiom, and the `AGENT_TRACER_*` env-var prefix already established (e.g. `AGENT_TRACER_WRITER_CAPACITY`). Follow this prefix for the new receiver knobs.

**Copy this exact shape** (ClickHouseConfig.kt lines 19-48):
```kotlin
data class ReceiverConfig(
    val httpPort: Int,
    val grpcPort: Int,
    val grpcMaxInboundBytes: Int,
    val httpMaxBodyBytes: Int,      // RESEARCH Open Question 3 / Security note: mirror the gRPC cap
    val retryAfterSeconds: Int,
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 4318
        const val DEFAULT_GRPC_PORT = 4317
        const val DEFAULT_GRPC_MAX_INBOUND_BYTES = 32 * 1024 * 1024   // 32 MiB > SC2's >4 MB (A4)
        const val ENV_HTTP_PORT = "AGENT_TRACER_HTTP_PORT"
        const val ENV_GRPC_PORT = "AGENT_TRACER_GRPC_PORT"
        // ... ENV_GRPC_MAX_INBOUND_BYTES, ENV_RETRY_AFTER, ENV_HTTP_MAX_BODY_BYTES
        fun fromEnv(env: (String) -> String? = System::getenv): ReceiverConfig = /* env(...)?.toIntOrNull() ?: DEFAULT_* */
    }
}
```
`require(...)` validation in an `init {}` block is the established pattern (SpanWriter.Config.init, lines 83-88) — apply it to ports/sizes.

---

### `ingest/OtlpResponses.kt` (utility, transform)

**Analog:** `storage/SpanRow.from` (SpanRow.kt lines 73-113) — a pure builder/projection factory with no I/O.

Holds `buildResponse(result: DecodeResult): ExportTraceServiceResponse` (RESEARCH Code Examples lines 524-537): set `partial_success` ONLY when `rejectedSpans > 0`, else leave it UNSET. Keep it pure/stateless like `SpanRow.from`. Status-code mapping (200/400/429 for HTTP; gRPC `Status.RESOURCE_EXHAUSTED`/`INTERNAL`) lives here too so both transports share one translation.

> **Type-collision guard (RESEARCH §Pattern 3 note):** `io.grpc.Status` (transport status for `onError`) ≠ `io.opentelemetry.proto.trace.v1.Status` (span status read in the decoder). Both are in scope across `ingest/`. Alias one on import to avoid silent mis-binding.

---

### `ingest/OtlpHttpRoutes.kt` (route, request-response) — NO IN-REPO ANALOG

No Ktor route exists in the codebase yet. Transport mechanics come from **RESEARCH §Pattern 2** (lines 305-346), not from an analog. The in-repo seam calls it must make are fixed:

- **Saturation check FIRST** (D-01, before `call.receive`): read `writer.backpressure.isSaturated` (BackpressureSignal.kt line 27) → `429` + `Retry-After`. Do not decode under load.
- **Decode → write per span:** `decoder.decode(request).accepted.forEach { writer.send(it.span, it.context) }` — `SpanWriter.send` (SpanWriter.kt lines 125-136) is `suspend` and Ktor handlers are already suspend, so no bridge needed here (unlike gRPC).
- **Style to match:** KDoc the route's spec-conformance contract (Content-Type `application/x-protobuf`, 200/partial/400/429) the way storage classes KDoc their invariants.

---

### `ingest/OtlpGrpcService.kt` (service, request-response + callback→coroutine bridge) — NO gRPC ANALOG

No gRPC service exists in the repo. Mechanics come from **RESEARCH §Pattern 3** (lines 347-402). Two in-repo style analogs apply:

1. **Coroutine-bridge discipline** — the *style* analog is `MigrationRunner` (MigrationRunner.kt lines 107-117): the repo's rule is "bridge blocking/callback APIs to coroutines via suspension, never block." For gRPC: `export` is callback-style (`StreamObserver`) but `SpanWriter.send` is `suspend` → `scope.launch { ... responseObserver.onNext/onCompleted }` on a server-lifecycle `CoroutineScope`. **Never** `runBlocking`/`Thread.sleep` inside `export` (CLAUDE.md raw-thread ban + RESEARCH Pitfall 2).
2. **Saturation → `io.grpc.Status.RESOURCE_EXHAUSTED`** (D-01) before launching: read the same `BackpressureSignal.isSaturated`.

Seam calls are identical to the HTTP route (`decoder.decode` → `writer.send` per span) — that identity is the whole point of D-06; both call `OtlpDecoder` and `OtlpResponses.buildResponse`.

---

### `app/TracerApplication.kt` — `fun main` (bootstrap, event-driven) — NO `main` ANALOG

No `fun main` exists yet. Lifecycle mechanics come from **RESEARCH §Pattern 4** (lines 404-443). Every lifecycle *piece* it wires already exists with a known call shape — copy these exactly:

| Step | Seam call | Source |
|------|-----------|--------|
| Load CH config | `ClickHouseConfig.fromEnv()` | ClickHouseConfig.kt line 40 |
| Build client | `ClickHouseClientFactory.create(chConfig)` | ClickHouseClientFactory.kt line 20 |
| **Migrate BEFORE opening ports** (D-04, fail-fast) | `MigrationRunner(client).runMigrations().getOrThrow()` | MigrationRunner.kt line 50; call-site copy in `SpanRepositoryIT.applySchema` lines 44-50 |
| Build repo | `ClickHouseSpanRepository(client)` | ClickHouseSpanRepository.kt line 35 (**single-arg constructor** — repo takes only `client`; database comes from `ClickHouseConfig.database` via `setDefaultDatabase` in the factory. RESEARCH §Pattern 4's `ClickHouseSpanRepository(client, chConfig.database)` is WRONG — the real ctor is 1-arg.) |
| Build writer | `SpanWriter(repo, AgentSpanRowMapper(), appScope)` | SpanWriter.kt lines 55-60 |
| Read backpressure for receivers | `writer.backpressure` | SpanWriter.kt line 112 |
| **Drain on shutdown** (close ports → flush → close client) | `writer.drainAndClose()` then `client.close()` | SpanWriter.kt lines 142-145; `Client` is `AutoCloseable` per ClickHouseClientFactory.kt KDoc |

> **Config note:** `MigrationRunner` and `SpanWriter` both `getOrThrow()` / surface `Result`. Boot must fail loud if migrations fail (D-04 — the Code 62/549 class must surface at boot, not request time). Use `.getOrThrow()` exactly as `SpanRepositoryIT.applySchema` does.

---

### Test files

**Unit tests** (`OtlpDecoderTest.kt`) — analog: the `convention/AgentSpanExtractor*Test.kt` family (8 files). Same pure-JVM, hand-built-OTLP-proto, Kotest-matcher style. Build `ExportTraceServiceRequest` in-test; no container.

**Integration tests** (`Otlp{Http,Grpc}ReceiverIT.kt`, `TracerApplicationIT.kt`) — analog: `storage/SpanRepositoryIT.kt` + `ClickHouseTestSupport.kt`. Copy directly:
- `@Testcontainers` + `@TestInstance(PER_CLASS)`, `private val client = ClickHouseTestSupport.newClient()` (SpanRepositoryIT.kt lines 37-40). **Reuse the shared container** — do NOT create a new `ClickHouseContainer` (ClickHouseTestSupport.kt lines 31-34).
- `@BeforeAll fun applySchema()` running `MigrationRunner(client).runMigrations().getOrThrow()` (lines 43-50).
- The **async-visibility poll** idiom (`waitForSpans`/`waitForRun`, lines 198-228): ClickHouse inserts are async-visible; poll `SELECT count() ... WHERE TraceId = {tid:String}` with `kotlinx.coroutines.delay` (50 attempts × 100 ms) — never `Thread.sleep`. The receiver ITs must `writer.drainAndClose()` to flush the batch, THEN poll, THEN assert via `repo.getTraceById(knownHexId)`.
- **DISTINCT TraceId per test** so the shared container never cross-contaminates (lines 33-36, 55, 83).

---

## Shared Patterns

### D-07 Hex Encoding — THE load-bearing format (Open Question 1 / Pitfall 1) — PINNED

**Source of truth:** `backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanFixtures.kt` (lines 25-27, 165-168) — the Phase-2 fixtures that every storage test and `getTraceById` round-trip already commit to.

**Confirmed format (the decoder MUST match byte-for-byte):**
- **Lowercase, no `0x` prefix.** Fixtures use `"0af7651916cd43dd8448eb211c80319c"` (trace), `"b7ad6b7169203331"` (span), and the generator `"%016x".format(...)` (SpanFixtures.kt line 165) — Kotlin `%x` is lowercase.
- **Fixed widths:** trace id = 32 hex chars (16 bytes), span id = 16 hex chars (8 bytes).
- **Empty parent = `""`** (empty string), NOT null — `SpanFixtures.minimal` parentSpanId default is `""` (line 78); `SpanRow.parentSpanId` defaults to `""` (SpanRow.kt line 30).
- **Lookup is exact-string match:** `getTraceById` binds `{tid:String}` verbatim (ClickHouseSpanRepository.kt lines 78-81, 96-97) — any casing/width/prefix drift makes `getTraceById(knownId)` return empty while inserts still succeed (the exact Pitfall-1 failure).

**The `toHex` helper to use** (RESEARCH Pattern 1, lines 299-300) produces this exact format — lowercase, no prefix, zero-padded per byte:
```kotlin
private fun com.google.protobuf.ByteString.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
```
**Mandatory closing test (Pitfall 1):** a round-trip IT that exports a span with a known id and reads it back via `getTraceById(sameHex)` — fails loudly on any format mismatch. Put this in `Otlp{Http,Grpc}ReceiverIT`.

> Two `SpanFixtures.kt` files exist (`convention/` and `storage/`); the **`storage/` one** (read above) is the identity/hex source of truth. The planner should also glance at `convention/SpanFixtures.kt` for OTLP-proto-building helpers when constructing `ExportTraceServiceRequest` inputs in `OtlpDecoderTest`.

### Backpressure read (D-01) — applies to BOTH transports
**Source:** `storage/pipeline/BackpressureSignal.kt` (line 27: `val isSaturated: Boolean get() = state.value`), exposed via `SpanWriter.backpressure` (SpanWriter.kt line 112).
Both the Ktor route and gRPC service read `writer.backpressure.isSaturated` as the FIRST statement (before decode/body-read) and shed load: HTTP `429 + Retry-After`, gRPC `RESOURCE_EXHAUSTED`. (RESEARCH Pitfall 4 — never decode then check.)

### The single write seam (INGEST-03) — applies to both transports + the decoder
**Source:** `SpanWriter.send(span, context)` (SpanWriter.kt lines 125-136) is the ONLY path to ClickHouse. **Never** call `ClickHouseSpanRepository` from `ingest/`. Every accepted span: `writer.send(envelope.span, envelope.context)`. The mapper's 256 KB cap (`AgentSpanRowMapper`, lines 95-106) handles oversized payloads downstream — receivers never truncate or insert.

### `Result<T>` + coroutine `.await()` error idiom — applies to bootstrap + ITs
**Source:** `MigrationRunner.runMigrations(): Result<Int>` (lines 50-65) and `ClickHouseSpanRepository` (every method returns `Result<T>`, `runCatching { ... }.onFailure { logger.warn ... }`). Expected failures are `Result`, not exceptions (CLAUDE.md). All async hops bridge via `kotlinx.coroutines.future.await` — never blocking `.get()`.

### No-`var` / no-`!!` / KDoc-the-invariant house style — ALL files
Every analog opens with a KDoc stating the class's invariant and the decision IDs it satisfies. `var` appears ONLY inside the framework-required `SpanRow`/`PayloadRow` POJOs (documented exception, SpanRow.kt lines 8-24) — `ingest/` code must be `val`-only. `!!` requires a safety comment.

### JUnit non-Unit-return trap (Pitfall 5) — ALL test files
**Source:** RESEARCH Pitfall 5 + every existing IT — note `SpanRepositoryIT` tests all use explicit `: Unit =` block bodies (e.g. line 53: `fun typedAgentColumnsRoundTrip(): Unit = runBlocking {`). An expression-body `@Test` returning a matcher is silently SKIPPED. Every new `@Test` MUST have `: Unit` or a block body; assert "0 skipped" at the phase gate.

---

## No Analog Found

Files whose *transport mechanics* have no in-repo precedent (the planner uses RESEARCH §Patterns + cited OTLP spec for mechanics; in-repo analogs above govern style only):

| File | Role | Data Flow | Reason | Mechanics Source |
|------|------|-----------|--------|------------------|
| `ingest/OtlpHttpRoutes.kt` | route | request-response | No Ktor server/route exists in the repo yet (Ktor is a new Phase-3 dependency). | RESEARCH §Pattern 2 + OTLP/HTTP spec |
| `ingest/OtlpGrpcService.kt` | service | request-response | No gRPC service or `io.grpc.Server` in the repo (grpc-netty-shaded is new). | RESEARCH §Pattern 3 |
| `app/TracerApplication.kt` (`fun main`) | bootstrap | event-driven | No application entry point / lifecycle owner exists yet. | RESEARCH §Pattern 4 |
| `OtlpHttpRoutesTest.kt` | test | request-response | No `ktor-server-test-host` test precedent. | RESEARCH Validation Architecture |
| `OtlpGrpcServiceTest.kt` | test | request-response | No in-process gRPC channel test precedent. | RESEARCH Validation Architecture |

---

## Metadata

**Analog search scope:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/{convention,storage,storage/pipeline,storage/migrations,domain}/`, `backend/src/test/kotlin/io/github/unityinflow/agenttracer/{convention,storage,storage/pipeline}/`, `backend/build.gradle.kts`.
**Files scanned:** 31 Kotlin files enumerated; 11 read in full (6 main seams + SpanContext/AttrView, 3 test analogs + 2 SpanFixtures).
**Key correction surfaced for planner:** `ClickHouseSpanRepository` is a **1-arg** constructor (`client` only) — RESEARCH §Pattern 4's 2-arg `ClickHouseSpanRepository(client, chConfig.database)` does not compile; database is wired via `ClickHouseClientFactory.setDefaultDatabase`.
**Pattern extraction date:** 2026-06-22
