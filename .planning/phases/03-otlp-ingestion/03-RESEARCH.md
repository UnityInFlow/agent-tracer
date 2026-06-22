# Phase 3: OTLP Ingestion - Research

**Researched:** 2026-06-22
**Domain:** OpenTelemetry OTLP trace receivers (HTTP/protobuf + gRPC) on the JVM, wiring into an existing ClickHouse batching pipeline
**Confidence:** HIGH

## Summary

Phase 3 adds two spec-conformant OTLP **trace receivers** in front of seams that already exist and are green: the Phase-1 `AgentSpanExtractor`/`AttrView` (OTLP-native — they consume `KeyValue` protos directly) and the Phase-2 `SpanWriter` batching pipeline + `BackpressureSignal`. **No new domain, attribute, or storage code is needed.** Phase 3 is pure transport wiring: decode `ExportTraceServiceRequest` → walk `ResourceSpans → ScopeSpans → Span` → for each span build `(AgentSpan, SpanContext)` via the existing extractor → `SpanWriter.send(...)`. One transport-agnostic decoder (D-06) is shared by a Ktor Netty route (`POST /v1/traces` on :4318) and a standalone `io.grpc.Server` `TraceServiceImplBase` (:4317), because Ktor cannot host gRPC.

The locked stack is fully verified by **jar inspection** of `io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha` (already in the Gradle cache): the artifact ships pre-generated `TraceServiceGrpc` with an `AsyncService` interface whose single method is `export(ExportTraceServiceRequest, StreamObserver<ExportTraceServiceResponse>)` — **no protoc pipeline**. The proto POM depends ONLY on `protobuf-java` (4.34.0, override to 4.35.1); it does **not** pull grpc transitively, so `grpc-netty-shaded`, `grpc-stub`, and `grpc-protobuf` (1.82.0, all verified on Maven Central) must be added explicitly to make `TraceServiceGrpc.bindService(...)` and the `io.grpc.Server` runnable.

The two genuinely tricky areas are: (1) the **coroutine bridge** — gRPC's `export` hands you a callback-style `StreamObserver` but `SpanWriter.send` is `suspend`; the correct pattern is to `launch` on a server-lifecycle `CoroutineScope` and complete the observer from inside the coroutine; and (2) **spec-conformant responses** — OTLP/HTTP partial rejection is HTTP **200** with `partial_success.rejected_spans` (NOT an error code), undecodable body is **400**, and saturation is **429 + Retry-After** (D-01); the gRPC equivalents are a normal response with `partial_success` and `RESOURCE_EXHAUSTED` for saturation (D-01; the spec's literal recommendation is `UNAVAILABLE`, see Pitfall 6).

**Primary recommendation:** Build one `OtlpDecoder` (transport-agnostic, returns a decode result carrying accepted envelopes + a rejected-span count + first error message), then a thin Ktor route and a thin gRPC service that both call it and translate the same result into their transport's success/partial/error/saturation response. Wire everything in a single manual-DI `fun main()` that migrates → starts `SpanWriter` → starts both servers → drains on shutdown. Prove it with **real OTel SDK exporters** (`OtlpHttpSpanExporter`/`OtlpGrpcSpanExporter`, opentelemetry 1.63.0) against a Testcontainers ClickHouse.

## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01 (Backpressure response policy, SC3):** Fail-fast retryable. When `BackpressureSignal.isSaturated` is true at request handling, the receiver immediately returns a retryable saturation status — gRPC `RESOURCE_EXHAUSTED` and OTLP/HTTP **429** with a `Retry-After` header — rather than holding the request open. When not saturated, it calls `SpanWriter.send(...)` per span (a brief suspension on the bounded buffer is acceptable).
- **D-02 (partial_success / span rejection, SC3):** Tolerant per-span. Decode the whole export request and accept every span that can be mapped to a valid row. A span is counted in `ExportTracePartialSuccess.rejected_spans` (with a human-readable `error_message`) **only** when it cannot become a valid row at all. Oversized prompt/completion payloads are **truncated** (Phase-2 256 KB cap), **never** rejected. A transport-level error (non-2xx / gRPC error status) is returned **only** when the entire protobuf body is undecodable. One bad span never fails a whole batch.
- **D-03 (Generic non-agent spans, SC1):** Store all, unified path. Every span persists through one path; the extractor yields an `AgentSpan` with null/default agent columns for non-agent spans while raw attributes are preserved. No span dropped, no separate generic-span table.
- **D-04 (App bootstrap & lifecycle):** Migrate-then-serve + drain. A single `fun main()`: load env config (reuse Phase-2 `ClickHouseConfig`; add receiver host/port + gRPC max-message-size knobs) → run migrations to completion **before** opening any port (fail fast) → start `SpanWriter` → start Ktor Netty (:4318) + standalone `io.grpc.Server` (:4317) → register a shutdown hook that **closes ports first**, then `SpanWriter.drainAndClose()`, then closes the ClickHouse client.
- **D-05 (Manual wiring, no Spring DI):** Components are constructed and passed explicitly in `main`.
- **D-06 (Transport-agnostic decode):** A single decoder turns an `ExportTraceServiceRequest` (ResourceSpans → ScopeSpans → Span) into `(AgentSpan, SpanContext)` envelopes via `AttrView.of(span.attributes)` + `AttrView.of(resource.attributes)` + the Phase-1 extractor. Both transports call this same decoder, so `partial_success` accounting and the `SpanWriter` hand-off are implemented once.
- **D-07 (Identity encoding):** `SpanContext` identity (trace_id / span_id / parent_span_id) is built from raw OTLP span bytes, **hex-encoded** to match the Phase-2 storage column format established in 02. Follow the Phase-2 `SpanContext` contract exactly — do not invent a new identity encoding.

### Claude's Discretion

- Concrete package layout under `…/agenttracer/ingest/` (e.g. `OtlpDecoder`, `OtlpHttpRoutes`, `OtlpGrpcService`, `TracerApplication`).
- Exact env-var names.
- Default gRPC max inbound size value (size it for prompt-heavy spans, e.g. 16–32 MB, justified against SC2's ">4 MB" case).
- The `Retry-After` value.
- Test structure (JUnit 5 + Kotest; Testcontainers for the end-to-end "real exporter → ClickHouse" proofs).

### Deferred Ideas (OUT OF SCOPE)

- **INGEST-04** OpenInference attribute mapping (Phoenix-ecosystem emitters).
- **INGEST-05** Cross-trace subagent linkage via OTLP span links (revisit when KORE's subagent-spawning model is concrete).
- OTLP/HTTP **JSON** encoding (protobuf only for v1).
- OTLP **logs/metrics** signals — traces-only this milestone.
- Query API, DAG assembly, demo emitter, Docker Compose, Helm → Phase 4 / DIST-02.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INGEST-01 | OTLP/HTTP-protobuf receiver on :4318 (`POST /v1/traces`) with spec-conformant `partial_success` and error responses | OTLP/HTTP spec verified ([CITED] opentelemetry.io/docs/specs/otlp): Content-Type `application/x-protobuf`, 200+unset on full success, 200+`partial_success.rejected_spans` on partial, 400 on undecodable body, 429+Retry-After on overload. Ktor Netty route reads raw body bytes → `ExportTraceServiceRequest.parseFrom(bytes)` → shared decoder → write response protobuf. See Pattern 2 + Code Examples. |
| INGEST-02 | OTLP/gRPC receiver on :4317, message size raised for prompt-heavy spans | `TraceServiceGrpc.AsyncService.export(...)` verified by jar inspection. Standalone `io.grpc.Server` (grpc-netty-shaded 1.82.0) with `.maxInboundMessageSize(32 MiB)` (SC2 needs >4 MB; gRPC default is 4 MiB). See Pattern 3 + Code Examples. |
| INGEST-03 | Both transports flow through one batching pipeline (no per-request inserts) | Both call the same `OtlpDecoder` then `SpanWriter.send(span, context)` per span. `SpanWriter` (Phase-2, verified) is the only path to ClickHouse and only ever batch-inserts. No new repository call anywhere in `ingest/`. |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| OTLP/HTTP transport (:4318) | API / Backend (Ktor Netty route) | — | HTTP framing, Content-Type negotiation, status codes belong to the HTTP server tier. |
| OTLP/gRPC transport (:4317) | API / Backend (standalone io.grpc.Server) | — | gRPC needs its own server; Ktor cannot host gRPC. Same JVM, separate listener. |
| Protobuf decode + span walk | API / Backend (OtlpDecoder) | — | Transport-agnostic decode is a backend concern shared by both listeners (D-06). |
| Attribute → AgentSpan mapping | Domain (Phase-1 AgentSpanExtractor) | — | Already exists; receiver tier MUST NOT duplicate it. |
| Envelope identity (trace/span/parent ids, ts) | Storage contract (Phase-2 SpanContext) | API/Backend (decoder populates it) | Identity encoding is the storage contract (D-07); the decoder just fills it from OTLP bytes. |
| Batching + backpressure + ClickHouse insert | Storage pipeline (Phase-2 SpanWriter) | — | Already exists; receiver only calls `send`/observes `BackpressureSignal`. |
| Boot/lifecycle (migrate, start, drain) | App bootstrap (TracerApplication/main) | — | Single owner of component lifecycle (D-04, D-05). |

## Standard Stack

### Core (verified)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.opentelemetry.proto:opentelemetry-proto` | 1.10.0-alpha | OTLP protobuf messages **and** pre-generated `TraceServiceGrpc` stubs | Already a backend dep. Jar inspection confirms `TraceServiceGrpc$AsyncService`, `$TraceServiceImplBase`, `bindService(...)`, and all message types — **no protoc**. `[VERIFIED: jar inspection]` |
| `io.grpc:grpc-netty-shaded` | 1.82.0 | The OTLP/gRPC server transport (:4317) | Shaded Netty → no classpath conflict with Ktor's Netty in one JVM. Latest stable. `[VERIFIED: Maven Central]` |
| `io.grpc:grpc-stub` | 1.82.0 | `StreamObserver`, `AbstractStub` runtime the generated `TraceServiceGrpc` needs at compile/run time | The proto jar's POM does **not** pull grpc transitively (only protobuf-java) — must add explicitly. `[VERIFIED: jar+POM inspection]` |
| `io.grpc:grpc-protobuf` | 1.82.0 | Protobuf marshalling for the gRPC service definition | Required alongside grpc-stub for `bindService`. `[VERIFIED: Maven Central]` |
| `com.google.protobuf:protobuf-java` | 4.35.1 | Protobuf runtime | proto 1.10.0-alpha declares ≥4.34.0; pin 4.35.1 (already in catalog). `[VERIFIED: jar POM + Maven Central]` |
| `io.ktor:ktor-server-netty` | 3.5.0 | HTTP server: `POST /v1/traces` (:4318) | Locked stack (CLAUDE.md). Latest stable. `[VERIFIED: Maven Central]` |
| `io.ktor:ktor-server-core` | 3.5.0 | Routing, application engine, lifecycle | Ktor 3.5.0 BOM line. `[VERIFIED: Maven Central]` |
| ClickHouse `client-v2`, `SpanWriter`, `MigrationRunner` | (existing) | Storage — reused unchanged | Phase-2 seams, green. `[VERIFIED: codebase]` |
| `kotlinx-coroutines-core` | 1.11.0 | gRPC→suspend bridge (`CoroutineScope.launch`), all async | Existing dep. `[VERIFIED: codebase]` |

### Supporting (verified)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `io.opentelemetry:opentelemetry-sdk` | 1.63.0 | Real exporter in integration tests | SC1/SC2 require driving the receivers with a **real** OTel SDK exporter, not hand-rolled protobuf. `[VERIFIED: Maven Central]` |
| `io.opentelemetry:opentelemetry-exporter-otlp` | 1.63.0 | `OtlpHttpSpanExporter` + `OtlpGrpcSpanExporter` for tests | Drives :4318 and :4317 end-to-end. `[VERIFIED: Maven Central]` |
| `io.opentelemetry:opentelemetry-bom` | 1.63.0 | Align all otel SDK test deps | Use `platform(...)` in the test config. `[VERIFIED: Maven Central]` |
| `io.ktor:ktor-server-test-host` | 3.5.0 | In-process Ktor route tests (status codes, partial_success) | Fast unit-level conformance tests without a socket. `[VERIFIED: Maven Central]` |
| `org.testcontainers:clickhouse` + `junit-jupiter` | 1.21.4 | Real-ClickHouse end-to-end proof | Reuse the Phase-2 `ClickHouseTestSupport` object fixture. `[VERIFIED: codebase]` |
| `io.kotest:kotest-assertions-core` | 6.1.11 | Matchers | Ecosystem rule. `[VERIFIED: codebase]` |
| `org.junit.jupiter:junit-jupiter` | 5.14.4 | Runner | Ecosystem rule. `[VERIFIED: codebase]` |

> **Optional (NOT recommended for v0.0.1):** A Gradle `application` plugin + `mainClass` for a runnable `main`. Adding it is fine, but the lifecycle wiring (D-04) is the substance; the plugin is just packaging. Phase 4 (Docker Compose) is where the runnable image matters — keep `main` testable now.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `grpc-netty-shaded` | `grpc-netty` (unshaded) | Unshaded Netty risks a version clash with Ktor's Netty in the same JVM. Shaded is the locked, safe choice. |
| Implementing `TraceServiceImplBase` | Implementing `AsyncService` + `bindService(asyncService)` | Both work (jar confirms both exist). `TraceServiceImplBase` is the conventional subclass; either is acceptable. The decoder is shared regardless. |
| `OtlpGrpcSpanExporter` (test) | `telemetrygen traces` CLI | telemetrygen is a great generic-source smoke test (D-03/SC1) but is a Go binary — keep it as an optional manual/CI check, not a unit-test dependency. The SDK exporter is the automatable proof. |
| Reading raw body in the Ktor route | `ktor-server-content-negotiation` with a protobuf converter | No first-party Ktor protobuf converter for OTLP; reading `call.receive<ByteArray>()` then `parseFrom` is simpler and explicit. Keep content-negotiation only if the query API (Phase 4) needs JSON. |

**Installation (add to `gradle/libs.versions.toml` + `backend/build.gradle.kts`):**

```toml
# [versions]
ktor = "3.5.0"
grpc = "1.82.0"
opentelemetry-sdk = "1.63.0"

# [libraries]
ktor-server-core    = { module = "io.ktor:ktor-server-core",    version.ref = "ktor" }
ktor-server-netty   = { module = "io.ktor:ktor-server-netty",   version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
grpc-netty-shaded   = { module = "io.grpc:grpc-netty-shaded",   version.ref = "grpc" }
grpc-stub           = { module = "io.grpc:grpc-stub",           version.ref = "grpc" }
grpc-protobuf       = { module = "io.grpc:grpc-protobuf",       version.ref = "grpc" }
otel-bom            = { module = "io.opentelemetry:opentelemetry-bom", version.ref = "opentelemetry-sdk" }
otel-sdk           = { module = "io.opentelemetry:opentelemetry-sdk" }
otel-exporter-otlp = { module = "io.opentelemetry:opentelemetry-exporter-otlp" }
```

```kotlin
// backend/build.gradle.kts dependencies
implementation(libs.ktor.server.core)
implementation(libs.ktor.server.netty)
implementation(libs.grpc.netty.shaded)
implementation(libs.grpc.stub)
implementation(libs.grpc.protobuf)
// protobuf-java is pinned to 4.35.1; add an explicit constraint so the proto's 4.34.0 doesn't win:
implementation(libs.protobuf.java)            // already referenced; ensure 4.35.1 resolves

testImplementation(platform(libs.otel.bom))
testImplementation(libs.otel.sdk)
testImplementation(libs.otel.exporter.otlp)
testImplementation(libs.ktor.server.test.host)
```

**Version verification:** All four grpc 1.82.0 artifacts, ktor 3.5.0 artifacts, protobuf-java 4.35.1, and opentelemetry-sdk/exporter-otlp/bom 1.63.0 return HTTP 200 from `repo1.maven.org` (fetched 2026-06-22). The proto POM was read directly (`protobuf-java 4.34.0` is its only declared dependency).

## Package Legitimacy Audit

All packages are first-party OpenTelemetry, gRPC (Google), Ktor (JetBrains), and protobuf (Google) — long-established, high-download, official-org artifacts on Maven Central. No npm/PyPI involved; no slopsquat surface.

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| io.opentelemetry.proto:opentelemetry-proto | Maven Central | years | github.com/open-telemetry/opentelemetry-proto-java | OK | Approved (already in use) |
| io.grpc:grpc-netty-shaded / grpc-stub / grpc-protobuf | Maven Central | years | github.com/grpc/grpc-java | OK | Approved |
| com.google.protobuf:protobuf-java | Maven Central | years | github.com/protocolbuffers/protobuf | OK | Approved (already in catalog) |
| io.ktor:ktor-server-* | Maven Central | years | github.com/ktorio/ktor | OK | Approved |
| io.opentelemetry:opentelemetry-sdk / exporter-otlp / bom | Maven Central | years | github.com/open-telemetry/opentelemetry-java | OK | Approved (test scope) |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
  OTel SDK exporter / KORE / telemetrygen
        │                         │
   HTTP/protobuf              gRPC/protobuf
   POST :4318                 :4317 (maxInbound 32 MiB)
        │                         │
        ▼                         ▼
 ┌──────────────┐         ┌──────────────────────┐
 │ Ktor Netty   │         │ io.grpc.Server        │
 │ OtlpHttpRoutes│        │ OtlpGrpcService        │
 │ (read bytes, │        │ (TraceServiceImplBase  │
 │  parseFrom)  │        │  .export + observer)   │
 └──────┬───────┘         └─────────┬────────────┘
        │ ExportTraceServiceRequest │
        └────────────┬──────────────┘
                     ▼
            ┌──────────────────┐   reads BackpressureSignal.isSaturated  ──► saturated?
            │   OtlpDecoder     │   yes → 429+Retry-After / RESOURCE_EXHAUSTED (D-01)
            │ (D-06, shared)    │
            │ walk RS→SS→Span   │
            │ AttrView.of(...)  │
            │ AgentSpanExtractor│   per span: ok → envelope; unmappable → rejected++ (D-02)
            │ → (AgentSpan,     │
            │    SpanContext)   │   SpanContext ids = hex(span bytes) (D-07)
            └────────┬──────────┘
                     │ per accepted span
                     ▼
            ┌──────────────────┐
            │ SpanWriter.send  │  (Phase-2; bounded Channel, batches, no per-request insert)
            └────────┬─────────┘
                     ▼
              ClickHouse (batched RowBinary)

  DecodeResult{ acceptedCount, rejectedSpans, firstErrorMessage }
        │
        ▼  build ExportTraceServiceResponse
   HTTP 200 + partial_success(rejected_spans,error_message)  (or 400 on undecodable body)
   gRPC  response + partial_success                          (or INVALID_ARGUMENT on undecodable)

  fun main(): MigrationRunner.runMigrations() ──► SpanWriter.start ──► Ktor + gRPC start
              shutdown hook: close ports ──► SpanWriter.drainAndClose() ──► client.close()
```

### Recommended Project Structure

```
backend/src/main/kotlin/io/github/unityinflow/agenttracer/
├── ingest/
│   ├── OtlpDecoder.kt          # D-06: ExportTraceServiceRequest → DecodeResult(envelopes, rejected, msg)
│   ├── DecodeResult.kt         # accepted: List<SpanEnvelope>, rejectedSpans: Long, errorMessage: String?
│   ├── OtlpHttpRoutes.kt       # Ktor Route.otlpTraces(decoder, writer): POST /v1/traces
│   ├── OtlpGrpcService.kt      # TraceServiceImplBase override export(...) + coroutine bridge
│   ├── OtlpResponses.kt        # build ExportTraceServiceResponse(partial_success); status mapping
│   └── ReceiverConfig.kt       # host/ports, gRPC maxInboundSize, Retry-After (fromEnv)
└── app/
    └── TracerApplication.kt    # fun main(): manual DI, migrate→serve→drain (D-04, D-05)
```

### Pattern 1: Transport-agnostic decoder (D-06) — the heart of the phase

**What:** One function walks the OTLP tree once and produces both the writable envelopes and the partial-success accounting. Both transports call it; response formatting differs, decode logic does not.

**When to use:** Always — this is the literal D-06 mandate and the only place the OTLP tree is walked.

```kotlin
// Source: jar-verified API (TraceServiceGrpc, ResourceSpans, Span, Status) + Phase-1/2 seams
package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agenttracer.convention.AgentSpanExtractor
import io.github.unityinflow.agenttracer.convention.AttrView
import io.github.unityinflow.agenttracer.storage.SpanContext
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter.SpanEnvelope
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.Status
import java.time.Instant

data class DecodeResult(
    val accepted: List<SpanEnvelope>,
    val rejectedSpans: Long,
    val errorMessage: String?,
)

class OtlpDecoder {
    fun decode(request: ExportTraceServiceRequest): DecodeResult {
        val accepted = ArrayList<SpanEnvelope>()
        var rejected = 0L
        var firstError: String? = null

        for (resourceSpans in request.resourceSpansList) {
            // Resource attrs absent → empty view (never throws). hasResource() guards the proto default.
            val resourceView =
                if (resourceSpans.hasResource()) {
                    AttrView.of(resourceSpans.resource.attributesList)
                } else {
                    AttrView.empty()
                }
            for (scopeSpans in resourceSpans.scopeSpansList) {
                for (span in scopeSpans.spansList) {
                    val envelope = toEnvelope(span, resourceView)
                    if (envelope != null) {
                        accepted.add(envelope)
                    } else {
                        rejected++
                        if (firstError == null) firstError = "span rejected: missing/invalid trace or span id"
                    }
                }
            }
        }
        return DecodeResult(accepted, rejected, firstError)
    }

    private fun toEnvelope(
        span: io.opentelemetry.proto.trace.v1.Span,
        resourceView: AttrView,
    ): SpanEnvelope? {
        // D-02: a span is rejected ONLY if it cannot become a valid row at all.
        // Minimum viable identity = non-empty trace id (16 bytes) + span id (8 bytes).
        val traceId = span.traceId
        val spanId = span.spanId
        if (traceId.isEmpty || spanId.isEmpty) return null

        val attrs = AttrView.of(span.attributesList)
        val isError = span.status.code == Status.StatusCode.STATUS_CODE_ERROR
        val agentSpan = AgentSpanExtractor.extract(attrs, resourceView, otelStatusIsError = isError)

        val context =
            SpanContext(
                // D-07: hex-encode the raw OTLP id bytes to match the Phase-2 storage column format.
                traceId = traceId.toHex(),
                spanId = spanId.toHex(),
                parentSpanId = if (span.parentSpanId.isEmpty) "" else span.parentSpanId.toHex(),
                timestamp = Instant.ofEpochSecond(0, span.startTimeUnixNano.toLong()),
            )
        return SpanEnvelope(agentSpan, context)
    }
}

// startTimeUnixNano is uint64 nanos; Long.toLong() is a no-op but documents the proto fixed64 source.
private fun com.google.protobuf.ByteString.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
```

> **D-07 verification note for the planner:** confirm against Phase-2 `SpanFixtures`/`ClickHouseSpanRepository` exactly how the hex is cased and whether parent-empty is `""`. The Phase-2 schema column format is the source of truth — the decoder MUST match it byte-for-byte or trace-by-id lookup breaks. (See Open Question 1.)

### Pattern 2: OTLP/HTTP route (INGEST-01)

**What:** Plain Ktor route; read raw bytes, parse, check saturation, decode, write per span, return the protobuf response.

```kotlin
// Source: OTLP/HTTP spec (opentelemetry.io/docs/specs/otlp) + Ktor 3.5.0 + jar-verified messages
fun Route.otlpTraces(decoder: OtlpDecoder, writer: SpanWriter, signal: BackpressureSignal, retryAfter: Int) {
    post("/v1/traces") {
        // D-01: fail fast under saturation BEFORE reading/decoding the body.
        if (signal.isSaturated) {
            call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respondBytes(ContentType("application", "x-protobuf"), HttpStatusCode.TooManyRequests) {
                ByteArray(0)
            }
            return@post
        }
        val body = call.receive<ByteArray>()
        val request =
            try {
                ExportTraceServiceRequest.parseFrom(body)
            } catch (e: InvalidProtocolBufferException) {
                // D-02: undecodable body is the ONLY transport-level error → 400 (spec).
                call.respondBytes(status = HttpStatusCode.BadRequest) { ByteArray(0) }
                return@post
            }
        val result = decoder.decode(request)
        result.accepted.forEach { writer.send(it.span, it.context) } // suspends only on backpressure
        val response = buildResponse(result) // unset partial_success when rejected == 0
        call.respondBytes(ContentType("application", "x-protobuf"), HttpStatusCode.OK) {
            response.toByteArray()
        }
    }
}
```

Spec conformance (all `[CITED: opentelemetry.io/docs/specs/otlp]`):
- Content-Type `application/x-protobuf` on request and response.
- Full success → **200**, `ExportTraceServiceResponse` with `partial_success` **unset**.
- Partial → **200**, `partial_success.rejected_spans` + `error_message` set; client MUST NOT retry.
- Undecodable body → **400** (client MUST NOT retry). (Optionally include a `Status` with `BadRequest` details — minimal empty body is acceptable for v0.0.1.)
- Overload → **429** (or 503) + `Retry-After` seconds (D-01 picks 429).

### Pattern 3: OTLP/gRPC service + coroutine bridge (INGEST-02, key question 3 & 4)

**What:** Implement `TraceServiceImplBase.export`. The method is callback-style (`StreamObserver`), but `SpanWriter.send` is `suspend` — bridge by `launch`-ing on a server-scoped `CoroutineScope` and completing the observer from inside.

```kotlin
// Source: jar-verified TraceServiceGrpc$AsyncService.export(req, StreamObserver<resp>) + grpc-stub 1.82.0
class OtlpGrpcService(
    private val decoder: OtlpDecoder,
    private val writer: SpanWriter,
    private val signal: BackpressureSignal,
    private val scope: CoroutineScope, // tied to the gRPC server lifecycle (D-04)
) : TraceServiceGrpc.TraceServiceImplBase() {

    override fun export(
        request: ExportTraceServiceRequest,
        responseObserver: StreamObserver<ExportTraceServiceResponse>,
    ) {
        // D-01: saturation → RESOURCE_EXHAUSTED (retryable; SDK/Collector back off). No body read needed.
        if (signal.isSaturated) {
            responseObserver.onError(Status.RESOURCE_EXHAUSTED // io.grpc.Status, NOT the proto Status
                .withDescription("ingest pipeline saturated; retry with backoff")
                .asRuntimeException())
            return
        }
        // Bridge callback → suspend: launch on the lifecycle scope, complete the observer inside.
        scope.launch {
            try {
                val result = decoder.decode(request)
                result.accepted.forEach { writer.send(it.span, it.context) }
                responseObserver.onNext(buildResponse(result))
                responseObserver.onCompleted()
            } catch (e: Exception) {
                // Undecodable bodies are already rejected at parse time by gRPC's own protobuf marshaller,
                // so reaching here is an internal fault, not a client error.
                responseObserver.onError(Status.INTERNAL.withDescription(e.message).asRuntimeException())
            }
        }
    }
}
```

Server build (raised inbound size — SC2):

```kotlin
// Source: grpc-netty-shaded 1.82.0; default maxInboundMessageSize is 4 MiB
val grpcServer: Server =
    NettyServerBuilder.forPort(grpcPort)
        .addService(OtlpGrpcService(decoder, writer, signal, serverScope))
        .maxInboundMessageSize(32 * 1024 * 1024) // 32 MiB — > SC2's "4 MB" prompt-heavy spans
        .build()
        .start()
```

> **gRPC note:** `io.grpc.Status` (the transport status used in `onError`) is a DIFFERENT type from `io.opentelemetry.proto.trace.v1.Status` (the span status read in the decoder). Both are in scope — alias one on import to avoid confusion.

> **Undecodable gRPC body:** unlike HTTP (where we call `parseFrom` ourselves), gRPC's protobuf marshaller deserializes `ExportTraceServiceRequest` *before* `export` is invoked. A malformed wire body fails inside grpc-java with `INVALID_ARGUMENT`/`INTERNAL` automatically — there is no decode-failure branch to write in `export`. This is the gRPC analogue of D-02's "transport-level error only when the whole body is undecodable."

### Pattern 4: App bootstrap & lifecycle (D-04, D-05)

```kotlin
// Source: D-04/D-05 + Phase-2 MigrationRunner/ClickHouseConfig + Ktor 3.5.0 embeddedServer + io.grpc.Server
fun main() = runBlocking {
    val chConfig = ClickHouseConfig.fromEnv()
    val receiverConfig = ReceiverConfig.fromEnv()
    val client = ClickHouseClientFactory.create(chConfig)

    // 1. Migrate to completion BEFORE opening any port; fail fast (Code 62/549 must surface at boot).
    MigrationRunner(client).runMigrations().getOrThrow()

    // 2. Start the writer pipeline on a structured scope.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo = ClickHouseSpanRepository(client, chConfig.database)
    val writer = SpanWriter(repo, AgentSpanRowMapper(), appScope)
    val decoder = OtlpDecoder()

    // 3. Start both servers in one JVM.
    val grpc = NettyServerBuilder.forPort(receiverConfig.grpcPort)
        .addService(OtlpGrpcService(decoder, writer, writer.backpressure, appScope))
        .maxInboundMessageSize(receiverConfig.grpcMaxInboundBytes)
        .build().start()
    val ktor = embeddedServer(Netty, port = receiverConfig.httpPort) {
        routing { otlpTraces(decoder, writer, writer.backpressure, receiverConfig.retryAfterSeconds) }
    }.start(wait = false)

    // 4. Drain on shutdown: close ports FIRST, then flush the in-flight buffer, then close the client.
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            grpc.shutdown().awaitTermination(10, TimeUnit.SECONDS)
            ktor.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            writer.drainAndClose()   // flush bounded buffer — no span lost on SIGTERM (T-02-14)
            client.close()
            appScope.cancel()
        }
    })
    grpc.awaitTermination() // keep main alive
}
```

### Anti-Patterns to Avoid

- **Per-request insert / bypassing SpanWriter.** Never call the repository from `ingest/`. Every span goes through `SpanWriter.send`. (INGEST-03; Pitfall in Phase-2 `SpanWriter` KDoc: `TOO_MANY_PARTS`.)
- **A second OTLP attribute model.** `AttrView`/`AgentSpanExtractor` already consume `KeyValue` protos. Do not re-parse attributes in the decoder.
- **Reading the request body before the saturation check (HTTP).** D-01 is *fail-fast* — check `signal.isSaturated` first, don't pay decode cost under load.
- **Blocking the gRPC thread.** Never call `runBlocking`/`Thread.sleep` inside `export`. Bridge to coroutines (Pattern 3). Blocking grpc-netty event-loop threads stalls the whole transport.
- **Inventing a new id encoding.** D-07: match the Phase-2 hex format exactly. Re-deriving "what looks right" instead of reading the Phase-2 fixture is the classic break.
- **Rejecting oversized payload spans.** D-02: truncate (Phase-2 256 KB cap handles it in the mapper), never count toward `rejected_spans`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| OTLP protobuf decode | A custom protobuf parser / JSON shim | `ExportTraceServiceRequest.parseFrom(bytes)` | Generated, exact, wire-compatible; handles every nested message. |
| gRPC service plumbing | Hand-written HTTP/2 framing | Pre-generated `TraceServiceImplBase` + grpc-netty-shaded | The whole reason the locked stack avoids protoc — stubs already ship. |
| Attribute typing | Re-reading `AnyValue` oneofs | Phase-1 `AttrView` + `AgentSpanExtractor` | Already strict, null-safe, never-throws, tested. |
| Batching / backpressure | A queue + flush loop in the receiver | Phase-2 `SpanWriter` + `BackpressureSignal` | Bounded channel, retry, no-drop, drain — all done and green. |
| Payload size capping | A truncation check in the decoder | Phase-2 `AgentSpanRowMapper` (256 KB cap) | D-02 says truncate not reject; the mapper already does it downstream of `send`. |
| Real exporter for tests | Hand-rolled protobuf fixtures over a socket | `OtlpHttpSpanExporter`/`OtlpGrpcSpanExporter` (otel-sdk 1.63.0) | SC1/SC2 demand a *real* exporter as the proof bar; hand-rolled bytes don't prove conformance. |

**Key insight:** Phase 3 has almost no novel logic — it is glue between four pre-built, verified seams. The risk is not "how do I build X" but "do I call the existing X correctly" (esp. D-07 id encoding, D-01 fail-fast ordering, and the coroutine bridge).

## Runtime State Inventory

> Greenfield-within-phase: Phase 3 adds new code and new dependencies; it does not rename or migrate any stored data. Inventory included for the new external-surface bindings.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — Phase 3 writes through the unchanged Phase-2 schema; no new tables, no key/format change. | None (verified: decoder feeds existing `SpanWriter`/`AgentSpanRowMapper`). |
| Live service config | New listening ports :4317 (gRPC) and :4318 (HTTP) become part of the app's external contract; Phase-4 Docker Compose must expose them. | Document ports in `ReceiverConfig`; flag for Phase-4 Compose (out of scope here). |
| OS-registered state | None — no OS-level task/service registration in this phase. | None. |
| Secrets/env vars | New env vars: receiver host/ports, gRPC max-inbound-size, Retry-After. ClickHouse creds reuse Phase-2 `CLICKHOUSE_*` (unchanged). No new secret. | Define `AGENT_TRACER_*` receiver env names (discretion); none are secrets. |
| Build artifacts | New runtime deps (grpc-netty-shaded, grpc-stub, grpc-protobuf, ktor-server-*). protobuf-java must resolve to 4.35.1 (proto declares 4.34.0). | Add explicit `protobuf-java` constraint so the proto's 4.34.0 transitive does not win (Pitfall 3). |

## Common Pitfalls

### Pitfall 1: D-07 id encoding mismatch breaks trace-by-id lookup
**What goes wrong:** The decoder hex-encodes trace/span ids one way (e.g. uppercase, or `null` parent) while the Phase-2 schema column expects another (lowercase, `''` parent). Inserts succeed but `getTraceById` returns nothing.
**Why it happens:** OTLP ids are raw bytes; "hex" has casing and empty-handling choices. The Phase-2 fixtures already committed to one.
**How to avoid:** Read `SpanFixtures` and `ClickHouseSpanRepository.getTraceById` in Phase 2 and match exactly. Add an IT that exports a known trace and reads it back by the same id (closes the loop).
**Warning signs:** Spans land (part counts grow) but `getTraceById(knownId)` is empty.

### Pitfall 2: Blocking the gRPC event loop with `runBlocking`/`Thread.sleep`
**What goes wrong:** Calling `writer.send` (suspend) from `export` by wrapping in `runBlocking` parks a grpc-netty I/O thread; under load the transport stalls and clients time out (looks like data loss).
**Why it happens:** `export` is a plain callback method; the temptation is to "just block until done."
**How to avoid:** `launch` on a server-lifecycle `CoroutineScope` (Pattern 3); complete the `StreamObserver` inside the coroutine. CLAUDE.md forbids `Thread.sleep`/raw threads anyway.
**Warning signs:** gRPC latency spikes; thread dumps show netty workers parked in `Channel.send`.

### Pitfall 3: protobuf-java version regression (4.34.0 vs 4.35.1)
**What goes wrong:** The proto artifact declares `protobuf-java 4.34.0`; if no explicit pin wins, Gradle may resolve 4.34.0 while grpc-java 1.82 expects the 4.x line you intend (4.35.1).
**Why it happens:** Transitive version of a direct-but-unpinned dependency.
**How to avoid:** Keep the explicit `protobuf-java = "4.35.1"` catalog entry as a direct `implementation` (already present) so it wins; verify with `./gradlew :backend:dependencies | grep protobuf-java`.
**Warning signs:** `NoSuchMethodError`/`IncompatibleClassChangeError` in protobuf runtime at startup.

### Pitfall 4: Saturation check placed after body decode (D-01 violated)
**What goes wrong:** Decoding a multi-MB body, THEN noticing saturation, wastes the work D-01 wanted to shed and delays the 429/RESOURCE_EXHAUSTED.
**How to avoid:** Check `signal.isSaturated` as the first statement of the handler, before `call.receive`/decode.
**Warning signs:** Under load, CPU stays high on parsing even while returning 429.

### Pitfall 5: JUnit silently skips expression-body `@Test` returning a non-Unit matcher (Phase-2 lesson)
**What goes wrong:** `@Test fun x() = something shouldBe y` compiles and is silently SKIPPED — the conformance assertion never runs, masking a real bug (this exact trap cost Phase 2 a UAT cycle).
**How to avoid:** Every `@Test` MUST have an explicit `: Unit` return or a block body. Add this to the plan's verification checklist for all new ingest tests.
**Warning signs:** Test count lower than method count; "0 skipped" not asserted.

### Pitfall 6: gRPC saturation status — D-01 says RESOURCE_EXHAUSTED, spec literally says UNAVAILABLE
**What goes wrong:** A reviewer cites the OTLP spec ("server SHOULD return code `Unavailable`") and flags `RESOURCE_EXHAUSTED` as non-conformant.
**Why it happens:** The spec's throttling recommendation names `UNAVAILABLE` (+`RetryInfo`); D-01 chose `RESOURCE_EXHAUSTED`. Both are in the OTel SDK/Collector retryable set, so both back off correctly in practice.
**How to avoid:** D-01 is the locked decision — use `RESOURCE_EXHAUSTED`. Document the deviation in the plan (it is a deliberate, retryable choice; the client behavior — backoff+retry — is identical). If strict spec literalism is later required, switching to `UNAVAILABLE` is a one-line change.
**Warning signs:** A conformance checklist that compares against the spec's exact word.

### Pitfall 7: Two ports in one JVM — port bind ordering and shutdown
**What goes wrong:** Migrations not finished before a port opens (request hits an empty schema), or shutdown drains before closing ports (new spans arrive mid-drain).
**How to avoid:** Strict D-04 ordering: migrate → start writer → start servers; shutdown: close ports → `drainAndClose()` → close client.
**Warning signs:** First-request schema errors at boot; spans accepted during shutdown then lost.

## Code Examples

### Build the ExportTraceServiceResponse (partial_success accounting)

```kotlin
// Source: jar-verified ExportTraceServiceResponse$Builder.setPartialSuccess + ExportTracePartialSuccess
fun buildResponse(result: DecodeResult): ExportTraceServiceResponse {
    val builder = ExportTraceServiceResponse.newBuilder()
    if (result.rejectedSpans > 0) {
        builder.partialSuccess =
            ExportTracePartialSuccess.newBuilder()
                .setRejectedSpans(result.rejectedSpans)
                .setErrorMessage(result.errorMessage ?: "some spans were rejected")
                .build()
    }
    // rejected == 0 → leave partial_success UNSET (full success, spec-required).
    return builder.build()
}
```

### Real OTel SDK exporter driving the HTTP receiver (integration test shape)

```kotlin
// Source: opentelemetry-sdk + exporter-otlp 1.63.0; SC1 proof bar
@Test
fun httpReceiverPersistsRealExporterSpansToClickHouse(): Unit = runBlocking {
    // ... start ClickHouse (Testcontainers, reuse ClickHouseTestSupport), migrate, start writer+route on :4318
    val exporter = OtlpHttpSpanExporter.builder()
        .setEndpoint("http://localhost:$httpPort/v1/traces")
        .build()
    val sdk = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    val span = sdk.get("it").spanBuilder("invoke_agent")
        .setAttribute("gen_ai.usage.input_tokens", 1234L)
        .startSpan()
    span.end()
    sdk.forceFlush().join(10, TimeUnit.SECONDS)
    // ... drainAndClose, then assert via SpanRepository.getTraceById(traceIdHex) that the span landed
}
```

### gRPC >4 MB prompt-heavy span (SC2 proof shape)

```kotlin
// Source: OtlpGrpcSpanExporter; .maxInboundMessageSize(32 MiB) on the server allows this
@Test
fun grpcReceiverAcceptsPromptHeavySpanOverDefaultLimit(): Unit = runBlocking {
    val bigPrompt = "x".repeat(6 * 1024 * 1024) // 6 MB > 4 MiB gRPC default
    val exporter = OtlpGrpcSpanExporter.builder()
        .setEndpoint("http://localhost:$grpcPort")
        .build()
    // ... emit a span with gen_ai.input.messages = bigPrompt; flush; assert it persists (truncated per D-02/256 KB)
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| protoc + protobuf-gradle-plugin to generate stubs | Use opentelemetry-proto's pre-generated `TraceServiceGrpc` | This artifact line | No codegen pipeline; faster build, fewer moving parts. (Locked decision, jar-confirmed.) |
| `opentelemetry-sdk` as a "receiver" | proto messages + your own grpc/http endpoints (SDK only for self-instrumentation/tests) | n/a | The SDK *emits*; it has no receiver role. (CLAUDE.md "What NOT to Use".) |
| kotlinx-rpc gRPC | grpc-netty-shaded + pre-generated stubs | n/a | kotlinx-rpc gRPC is development-preview; not for v0.0.1. |
| OTLP/HTTP JSON | protobuf-only for v1 | this milestone | JSON deferred; simpler conformance surface. |

**Deprecated/outdated:**
- `io.opentelemetry:opentelemetry-proto` (old coordinates) → use `io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha`.
- Blocking `.get()` on grpc/clickhouse futures → coroutine `.await()` (ecosystem rule).

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 5.14.4 (runner) + Kotest assertions 6.1.11 (matchers) |
| Config file | `backend/build.gradle.kts` (`tasks.test { useJUnitPlatform() }`) — no extra config |
| Quick run command | `./gradlew :backend:test --tests "*Otlp*Test"` |
| Full suite command | `./gradlew :backend:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| INGEST-01 (SC1) | Real `OtlpHttpSpanExporter` → :4318 → span in ClickHouse | integration (Testcontainers) | `./gradlew :backend:test --tests "*OtlpHttpReceiverIT"` | ❌ Wave 0 |
| INGEST-01 (SC3) | Partial reject → 200 + `partial_success.rejected_spans`; undecodable → 400 | unit (ktor-server-test-host) | `./gradlew :backend:test --tests "*OtlpHttpRoutesTest"` | ❌ Wave 0 |
| INGEST-01 (SC3) | Saturated → 429 + Retry-After | unit | `./gradlew :backend:test --tests "*OtlpHttpRoutesTest"` | ❌ Wave 0 |
| INGEST-02 (SC2) | Real `OtlpGrpcSpanExporter` → :4317 with >4 MB span → persisted | integration (Testcontainers) | `./gradlew :backend:test --tests "*OtlpGrpcReceiverIT"` | ❌ Wave 0 |
| INGEST-02 (SC3) | Saturated → `RESOURCE_EXHAUSTED` | unit (in-process grpc channel) | `./gradlew :backend:test --tests "*OtlpGrpcServiceTest"` | ❌ Wave 0 |
| INGEST-03 (SC4) | Decoder→`SpanWriter.send` only; no repo call from ingest; batched insert | unit + IT (assert batches, not per-span) | `./gradlew :backend:test --tests "*OtlpDecoderTest"` | ❌ Wave 0 |
| D-06 | Same decoder used by both transports yields identical `DecodeResult` for the same request | unit | `./gradlew :backend:test --tests "*OtlpDecoderTest"` | ❌ Wave 0 |
| D-07 | Exported trace is retrievable by `getTraceById(sameHexId)` | integration | `./gradlew :backend:test --tests "*Otlp*ReceiverIT"` | ❌ Wave 0 (closes Pitfall 1) |
| D-02 | Oversized payload span is truncated+stored, NOT counted in rejected_spans | unit + IT | `./gradlew :backend:test --tests "*OtlpDecoderTest"` | ❌ Wave 0 |
| D-04 | `main` migrates before opening ports; drains on shutdown without loss | integration | `./gradlew :backend:test --tests "*TracerApplicationIT"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :backend:test --tests "*Otlp*Test"` (pure-JVM unit tests, fast).
- **Per wave merge:** `./gradlew :backend:test` (includes Testcontainers ITs).
- **Phase gate:** full suite green (0 skipped — assert it, per Pitfall 5) before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `OtlpDecoderTest.kt` — D-06/D-02/D-07 decode + rejection accounting (pure JVM, hand-built `ExportTraceServiceRequest`).
- [ ] `OtlpHttpRoutesTest.kt` — status-code conformance via `ktor-server-test-host` (200/partial/400/429).
- [ ] `OtlpGrpcServiceTest.kt` — in-process gRPC channel + saturation status.
- [ ] `OtlpHttpReceiverIT.kt` / `OtlpGrpcReceiverIT.kt` — real SDK exporter → Testcontainers ClickHouse round-trip (reuse `ClickHouseTestSupport`).
- [ ] `TracerApplicationIT.kt` — boot ordering + drain.
- [ ] Test deps: `opentelemetry-bom`/`opentelemetry-sdk`/`opentelemetry-exporter-otlp` 1.63.0, `ktor-server-test-host` 3.5.0 (add to catalog + backend test config).
- [ ] Reuse `ClickHouseTestSupport` object + `SpanFixtures` from Phase 2 — do NOT re-create container fixtures.

> **Testcontainers caveat carried from Phase 2:** under Colima/QEMU the ClickHouse HTTP wait strategy read-times-out (container is healthy via manual curl). ITs run green on native Docker / CI runners. Plan should mark ITs "observe green on native Docker" and not fake a pass — same discipline as 02-05.

## Security Domain

> `security_enforcement` defaults enabled. agent-tracer v0.0.1 is a single-team local/self-hosted tool with **no auth/multi-tenancy** (REQUIREMENTS.md "Out of Scope"). The receivers are unauthenticated by design for this milestone.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (v0.0.1) | Out of scope — local/self-hosted single team. A transport-agnostic token validator is a *future* seam (CLAUDE.md "Stack Patterns" hint), not v0.0.1. |
| V3 Session Management | no | Stateless export RPCs. |
| V4 Access Control | no (v0.0.1) | No multi-tenancy. |
| V5 Input Validation | **yes** | OTLP protobuf parse is the trust boundary. `parseFrom` failure → 400/gRPC error (D-02). `AttrView`/extractor never throw on malformed attrs (Phase-1 V5/DoS control, already tested). |
| V6 Cryptography | no | No secrets handled in the receiver; ClickHouse creds via env (Phase-2). |

### Known Threat Patterns for OTLP receivers

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Oversized message DoS (memory blowup) | Denial of Service | gRPC `maxInboundMessageSize` cap (32 MiB) bounds a single message; Ktor body read is bounded by available heap — consider a soft body-size guard (note for plan). Payload fields capped at 256 KB downstream (Phase-2). |
| Unbounded ingest → OOM | Denial of Service | Bounded `SpanWriter` channel + D-01 fail-fast 429/RESOURCE_EXHAUSTED sheds load instead of buffering unbounded. |
| Malformed protobuf | Tampering | `parseFrom` rejects → 400 (HTTP) / `INVALID_ARGUMENT` (gRPC marshaller). One bad span never fails the batch (D-02). |
| Log injection via attribute values | Tampering | Phase-2 rule: never log payload bodies (`T-02-13`); ingest logging logs counts only, never attribute/payload content. |

> **Plan note:** add a configurable max HTTP body size guard (mirror the gRPC `maxInboundMessageSize`) so the HTTP path has the same DoS bound. Low effort, closes the asymmetry.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The Phase-2 hex id format is lowercase, no `0x`, parent-empty = `""`. | D-07 / Pattern 1 / Pitfall 1 | If casing/empty-handling differs, `getTraceById` returns nothing. **Mitigation:** planner MUST read `SpanFixtures`/`ClickHouseSpanRepository` and match — do not trust this assumption. (See Open Question 1.) |
| A2 | `SpanContext.timestamp` should be the span **start** time (`startTimeUnixNano`). | Pattern 1 | Phase-2 schema uses Timestamp as ORDER BY/partition; if it expects end-time or a different unit the partition pruning math could differ. **Mitigation:** confirm against Phase-2 fixture timestamp source. |
| A3 | gRPC's protobuf marshaller rejects an undecodable `ExportTraceServiceRequest` before `export` is called (so no manual decode-failure branch is needed in the gRPC service). | Pattern 3 | Standard grpc-java behavior; if a malformed body somehow reaches `export` it would be an internal error, already caught by the try/catch → `INTERNAL`. Low risk. |
| A4 | A 32 MiB gRPC `maxInboundMessageSize` comfortably covers SC2's ">4 MB" prompt-heavy spans. | Pattern 3 / D-discretion | If real agent spans exceed 32 MiB the limit must rise; it is env-configurable, so trivially adjustable. |

## Open Questions (RESOLVED)

1. **Exact Phase-2 id hex format (HIGH priority for planner).**
   - What we know: D-07 mandates hex-encoded ids matching the Phase-2 column format; the schema uses `TraceId`/`SpanId`/`ParentSpanId` String columns.
   - What's unclear: casing and empty-parent representation are not visible from the seam signatures alone.
   - Recommendation: planner's first ingest task reads `SpanFixtures.kt` + `ClickHouseSpanRepository.getTraceById` and pins the exact format; add a round-trip IT (Pitfall 1) so any mismatch fails loudly.
   - RESOLVED in 03-01 Task 3: format pinned byte-for-byte against `storage/SpanFixtures.DEFAULT_TRACE_ID` (`0af7651916cd43dd8448eb211c80319c`, lowercase, no `0x`, empty-parent = `""`) with a round-trip assertion; closed end-to-end by 03-04 Task 2's `getTraceById(knownHexId)` retrieval.

2. **gRPC saturation status: RESOURCE_EXHAUSTED (D-01) vs spec's UNAVAILABLE.**
   - What we know: D-01 locks `RESOURCE_EXHAUSTED`; both are retryable in the OTel SDK/Collector.
   - What's unclear: whether a future conformance bar demands literal spec wording.
   - Recommendation: follow D-01 (locked); document the deviation (Pitfall 6). One-line switch if ever required.
   - RESOLVED by D-01 -> `RESOURCE_EXHAUSTED`, implemented in 03-03 Task 1 (deviation from the spec's UNAVAILABLE documented in KDoc; both retryable).

3. **HTTP body-size guard value.**
   - What we know: gRPC is capped at 32 MiB; HTTP currently bounded only by heap.
   - Recommendation: add a configurable HTTP max-body guard mirroring the gRPC cap (security symmetry). Small task; sensible default 32 MiB.
   - RESOLVED to a 32 MiB default HTTP body guard (`httpMaxBodyBytes`), implemented in 03-02 Task 1, symmetric with the gRPC 32 MiB `maxInboundMessageSize`.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Gradle (wrapper) | build/test | ✓ (project wrapper, 9.5.1) | 9.5.1 | — |
| JDK 21 toolchain | Kotlin JVM 21 | ✓ (Gradle toolchain) | 21 | Gradle auto-provisions |
| Docker (Testcontainers ClickHouse) | ITs only | ⚠ Colima/QEMU HTTP-wait read-timeout (Phase-2) | 26.3 image | Run ITs on native Docker / CI; unit tests need no Docker |
| Maven Central | dependency resolution | ✓ (all new artifacts return 200) | — | — |
| opentelemetry-proto 1.10.0-alpha jar | compile (already cached) | ✓ (in `~/.gradle/caches`) | 1.10.0-alpha | — |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** Docker for ITs — unit-level conformance (decoder, routes via test-host, in-process gRPC) runs without Docker; real-exporter→ClickHouse ITs require native Docker (observe green on CI, per Phase-2 discipline).

## Sources

### Primary (HIGH confidence)
- **Jar inspection** of `opentelemetry-proto-1.10.0-alpha.jar` (`~/.gradle/caches`) via `javap`: confirmed `TraceServiceGrpc$AsyncService.export(ExportTraceServiceRequest, StreamObserver<ExportTraceServiceResponse>)`, `$TraceServiceImplBase`, `bindService(...)`, `Span.getTraceId/getSpanId/getParentSpanId/getStartTimeUnixNano/getAttributesList/getStatus`, `Status$StatusCode.{UNSET,OK,ERROR}`, `ResourceSpans.getScopeSpansList/getResource/hasResource`, `ScopeSpans.getSpansList`, `ExportTraceServiceRequest.getResourceSpansList/parseFrom`, `ExportTracePartialSuccess.{setRejectedSpans,setErrorMessage}`, `ExportTraceServiceResponse$Builder.setPartialSuccess` — fetched 2026-06-22.
- **POM read** `opentelemetry-proto-1.10.0-alpha.pom`: sole dependency `protobuf-java 4.34.0` (grpc NOT transitive) — fetched 2026-06-22.
- **Maven Central metadata/HEAD** (repo1.maven.org, fetched 2026-06-22): grpc-netty-shaded/grpc-stub/grpc-protobuf 1.82.0 (HTTP 200), ktor-server-* 3.5.0 (200), protobuf-java 4.35.1 (200), opentelemetry-sdk/exporter-otlp/bom 1.63.0 (200).
- **Codebase inspection** (HIGH): `AgentSpanExtractor`, `AttrView`, `SpanWriter`, `BackpressureSignal`, `SpanContext`, `MigrationRunner`, `ClickHouseConfig`, `AgentSpanRowMapper`, `SpanRepository`, `backend/build.gradle.kts`, `libs.versions.toml`.

### Secondary (MEDIUM confidence)
- [CITED: https://opentelemetry.io/docs/specs/otlp/] — OTLP/HTTP: Content-Type `application/x-protobuf`; 200 full/partial success; `partial_success.rejected_spans`+`error_message`; client MUST NOT retry partial; 400 on bad data (MUST NOT retry); 429/503 + Retry-After on overload. OTLP/gRPC: `Unavailable`+RetryInfo on overload (D-01 deviates to RESOURCE_EXHAUSTED — both retryable).
- [CITED: github.com/open-telemetry/oteps/blob/main/text/0099-otlp-http.md] — OTLP/HTTP transport details corroboration.

### Tertiary (LOW confidence)
- None — every load-bearing claim is jar-, POM-, registry-, codebase-, or official-spec-verified.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every version verified on Maven Central; proto API verified by jar inspection.
- Architecture: HIGH — D-01..D-07 are locked; all four reused seams read directly from source.
- Pitfalls: HIGH — Pitfalls 1/5/6 carried from Phase-2/CONTEXT lessons; 2/3/4/7 are well-known JVM/grpc/protobuf traps.
- D-07 exact hex format: MEDIUM (A1/Open Question 1 — planner must confirm against Phase-2 fixtures).

**Research date:** 2026-06-22
**Valid until:** 2026-07-22 (stable JVM stack; re-verify versions if planning slips a month)
