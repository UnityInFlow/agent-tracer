# Phase 3: OTLP Ingestion - Context

**Gathered:** 2026-06-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Spec-conformant OTLP **receivers** that decode trace exports and feed the single Phase-2 batching pipeline into ClickHouse. Any OTel-compatible source — KORE, a stock OTel SDK, or `telemetrygen` — can export traces to agent-tracer on the standard ports and have them land in ClickHouse.

**In scope (from ROADMAP Phase 3 / INGEST-01..03):**
- OTLP/HTTP-protobuf receiver on **:4318** (`POST /v1/traces`), spec-conformant `partial_success` and error responses
- OTLP/gRPC receiver on **:4317**, with inbound message-size limit raised for prompt-heavy agent spans
- Both transports flow through **one** Phase-2 batching pipeline (`SpanWriter`) — no per-request inserts
- The application bootstrap (`fun main`) that hosts both servers + the writer + ClickHouse in one JVM

**Out of scope (later phases / deferred):**
- Query API, DAG assembly, demo emitter, Docker Compose stack → Phase 4 (TOOL-01, DIST-01)
- OpenInference attribute mapping → INGEST-04 (deferred)
- Cross-trace subagent linkage via span links → INGEST-05 (deferred, revisit when KORE's spawning model is concrete)
- JSON-encoded OTLP/HTTP (only protobuf is in scope for v1)
- Helm chart → DIST-02 (deferred)

</domain>

<decisions>
## Implementation Decisions

### Backpressure response policy (SC3)
- **D-01:** **Fail-fast retryable.** When `BackpressureSignal.isSaturated` is true at request handling, the receiver immediately returns a retryable saturation status — gRPC `RESOURCE_EXHAUSTED` (HTTP/2) and OTLP/HTTP **429** with a `Retry-After` header — rather than holding the request open. When not saturated, it calls `SpanWriter.send(...)` per span (a brief suspension on the bounded buffer is acceptable). Rationale: OTel SDK `BatchSpanProcessor` and the OTel Collector already retry `RESOURCE_EXHAUSTED`/429 with backoff; this is the spec-idiomatic backpressure signal and frees server resources instead of risking client-side timeouts that look like data loss.

### partial_success / span rejection semantics (SC3)
- **D-02:** **Tolerant per-span.** Decode the whole export request and accept every span that can be mapped to a valid row. A span is counted in OTLP `ExportTracePartialSuccess.rejected_spans` (with a human-readable `error_message`) **only** when it cannot become a valid row at all. Oversized prompt/completion payloads are **truncated** (the Phase-2 256 KB cap + truncation metadata), **never** rejected. A transport-level error (non-2xx / gRPC error status) is returned **only** when the entire protobuf body is undecodable. One bad span never fails a whole batch.

### Generic (non-agent) span handling (SC1)
- **D-03:** **Store all, unified path.** Every span is persisted through one path. The Phase-1 `AgentSpanExtractor` yields an `AgentSpan` whose agent-specific typed columns are null/default for non-agent spans, while the raw OTLP attributes are preserved in the attribute-map columns. No span is dropped and there is no separate generic-span table. This satisfies SC1 literally (a stock OTel SDK exporter's spans appear in ClickHouse) and keeps surrounding context spans (HTTP/DB/etc.) that aid debugging.

### App bootstrap & lifecycle
- **D-04:** **Migrate-then-serve + drain.** A single `fun main()`:
  1. Loads env-var config (reuse Phase-2 `ClickHouseConfig`; add receiver host/port + gRPC max-message-size knobs).
  2. Runs ClickHouse migrations to completion **before** opening any port — fail fast if the schema can't apply (the same Code-62/549 class just fixed in Phase 2 must surface at boot, not at request time).
  3. Starts the `SpanWriter` coroutine.
  4. Starts the Ktor Netty server (:4318) and the standalone `io.grpc.Server` (:4317).
  5. Registers a shutdown hook / Ktor stop handler that **closes the ports first**, then calls `SpanWriter.drainAndClose()` to flush the in-flight buffer, then closes the ClickHouse client — so nothing is lost on `docker compose down` / SIGTERM.
- **D-05:** **Manual wiring, no Spring DI** — consistent with the Ktor-first ecosystem rule. Components are constructed and passed explicitly in `main`.

### Shared concerns (apply to both transports)
- **D-06:** The OTLP→domain decode path is **transport-agnostic**: a single decoder turns an `ExportTraceServiceRequest` (ResourceSpans → ScopeSpans → Span) into `(AgentSpan, SpanContext)` envelopes via `AttrView.of(span.attributes)` + `AttrView.of(resource.attributes)` and the Phase-1 extractor. Both the Ktor route and the gRPC `TraceServiceImplBase` call this same decoder, so `partial_success` accounting and the `SpanWriter` hand-off are implemented once.
- **D-07:** `SpanContext` identity (trace_id / span_id / parent_span_id) is built from the raw OTLP span bytes (hex-encoded to match the Phase-2 storage column format established in 02). Follow the Phase-2 `SpanContext` contract exactly — do not invent a new identity encoding.

### Claude's Discretion
- Concrete package layout under `…/agenttracer/ingest/` (e.g. `OtlpDecoder`, `OtlpHttpRoutes`, `OtlpGrpcService`, `TracerApplication`), exact env-var names, the default gRPC max inbound size value (size it for prompt-heavy spans, e.g. 16–32 MB, justified against SC2's ">4 MB" case), the `Retry-After` value, and test structure (JUnit 5 + Kotest; Testcontainers for the end-to-end "real exporter → ClickHouse" proofs).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope & requirements
- `.planning/ROADMAP.md` §"Phase 3: OTLP Ingestion" — goal, 4 success criteria, INGEST-01..03 mapping
- `.planning/REQUIREMENTS.md` §Ingestion — INGEST-01 (HTTP :4318 partial_success), INGEST-02 (gRPC :4317 raised message size), INGEST-03 (single pipeline, no per-request inserts); INGEST-04/05 explicitly deferred

### Locked technology stack (do not re-decide)
- `CLAUDE.md` §"Technology Stack" / §"Key Architecture-Affecting Decisions" — Ktor Netty route for OTLP/HTTP; standalone `grpc-netty-shaded` `io.grpc.Server` for gRPC (Ktor cannot host gRPC); pre-generated `TraceServiceGrpc` stubs from `io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha` (no protoc); versions for grpc-java 1.82.0, protobuf-java 4.35.1
- `CLAUDE.md` §"What NOT to Use" — no `opentelemetry-sdk` as a receiver; no kotlinx-rpc gRPC; `-alpha` proto artifact is the stable OTLP v1 line

### Phase-1 / Phase-2 integration seams (reuse, don't rebuild)
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt` — `extract(attrs: AttrView, resource: AttrView, …)` is the OTLP-attrs → `AgentSpan` bridge
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt` — `AttrView.of(attributes: List<KeyValue>)` consumes OTLP `KeyValue` protos directly
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/SpanWriter.kt` — `suspend fun send(span: AgentSpan, context: SpanContext)` (backpressure-aware) and `drainAndClose()`
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/BackpressureSignal.kt` — `isSaturated` / `saturated: StateFlow<Boolean>` the receiver reads for D-01
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanContext.kt` — identity contract for D-07
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseConfig.kt` + `migrations/MigrationRunner.kt` — boot-time config + migrations for D-04

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `AgentSpanExtractor` + `AttrView`: already OTLP-native (operate on `KeyValue` protos) — the receiver does not need its own attribute model.
- `SpanWriter.send` / `drainAndClose` / `BackpressureSignal`: the complete write + backpressure + graceful-drain seam exists; Phase 3 only wires receivers to it.
- `MigrationRunner` + `ClickHouseConfig` + `ClickHouseClientFactory`: boot-time schema application and client construction are ready to call from `main`.

### Established Patterns
- No `var`, no `!!` without a safety comment, coroutines for all async, JUnit 5 + Kotest, Testcontainers for real-ClickHouse integration proofs (Phase 2 convention).
- Phase 2 lesson carried in: Kotlin expression-body `@Test` methods returning a non-`Unit` matcher are silently skipped by JUnit — Phase 3 tests must use explicit `: Unit`/block bodies (see 02-VERIFICATION.md).

### Integration Points
- New `ingest` package connects OTLP transports → existing `convention` (extractor) and `storage.pipeline` (`SpanWriter`).
- New `TracerApplication`/`main` owns the lifecycle of `MigrationRunner` → `SpanWriter` → Ktor + gRPC servers → ClickHouse client.

</code_context>

<specifics>
## Specific Ideas

- Success criteria call out real exporters as the proof bar: a stock OTel SDK exporter over HTTP (:4318) and over gRPC (:4317, including a >4 MB prompt-heavy span), plus `telemetrygen` as a generic source. Plan integration tests around real exporters, not hand-rolled protobuf.
- Both receivers must return spec-conformant `partial_success` on partial rejection and the correct retryable status under saturation — these are explicit SC3 assertions, not nice-to-haves.

</specifics>

<deferred>
## Deferred Ideas

- **INGEST-04** OpenInference attribute mapping (Phoenix-ecosystem emitters) — future phase.
- **INGEST-05** Cross-trace subagent linkage via OTLP span links — deferred until KORE's subagent-spawning model is concrete.
- OTLP/HTTP **JSON** encoding (protobuf only for v1).
- OTLP **logs/metrics** signals — agent-tracer is traces-only for this milestone.

</deferred>

---

*Phase: 3-OTLP Ingestion*
*Context gathered: 2026-06-22*
