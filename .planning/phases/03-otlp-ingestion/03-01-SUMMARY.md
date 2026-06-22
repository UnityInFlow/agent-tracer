---
phase: 03-otlp-ingestion
plan: 01
subsystem: ingest
tags: [otlp, decoder, grpc, ktor, protobuf, d-06, d-07, d-02, d-03]
requires:
  - convention/AgentSpanExtractor (extract bridge)
  - convention/AttrView (OTLP KeyValue view)
  - storage/SpanContext (D-07 identity contract)
  - storage/pipeline/SpanWriter.SpanEnvelope (intake record)
provides:
  - ingest/OtlpDecoder (transport-agnostic OTLP tree walker, D-06)
  - ingest/DecodeResult (accept/reject accounting envelope)
  - ingest/OtlpResponses.buildResponse (partial_success builder)
  - ingest/ReceiverConfig (env-sourced ports/caps/Retry-After)
  - test: OtlpRequestFixtures (RS/SS/Span builders)
affects:
  - 03-02 (HTTP route calls OtlpDecoder + buildResponse + ReceiverConfig)
  - 03-03 (gRPC service calls the same decoder + response builder)
  - 03-04 (app bootstrap wires ReceiverConfig)
tech-stack:
  added:
    - io.ktor:ktor-server-core/netty/test-host 3.5.0
    - io.grpc:grpc-netty-shaded/stub/protobuf 1.82.0
    - io.opentelemetry:opentelemetry-bom/sdk/exporter-otlp 1.63.0 (test scope)
    - com.google.protobuf:protobuf-java 4.35.1 (now a DIRECT dependency)
  patterns:
    - transport-agnostic single decoder (D-06)
    - byte-for-byte hex id pinning against Phase-2 fixtures (D-07)
    - env-sourced immutable config with init{} require() (mirrors ClickHouseConfig)
key-files:
  created:
    - gradle/libs.versions.toml (catalog entries)
    - backend/build.gradle.kts (dependency wiring)
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/DecodeResult.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/ReceiverConfig.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpResponses.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpDecoder.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpRequestFixtures.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpDecoderTest.kt
  modified:
    - gradle/libs.versions.toml
    - backend/build.gradle.kts
decisions:
  - "D-07 hex format = lowercase, no 0x prefix, per-byte zero-padded, empty parent = \"\" — matches storage/SpanFixtures.DEFAULT_TRACE_ID byte-for-byte"
  - "ReceiverConfig defaults: httpPort 4318, grpcPort 4317, grpc+http max bytes 33554432 (32 MiB), retryAfter 1s; AGENT_TRACER_* env overrides via toIntOrNull"
  - "protobuf-java 4.35.1 added as a DIRECT implementation dependency so it wins over the proto artifact's transitive 4.34.0 (Pitfall 3)"
metrics:
  duration: ~6 min
  completed: 2026-06-22
  tasks: 3
  files: 8
---

# Phase 3 Plan 01: OTLP Decoder Core Summary

Built the transport-agnostic core of the OTLP receiver — the single `OtlpDecoder` (D-06) that turns an `ExportTraceServiceRequest` into `(AgentSpan, SpanContext)` envelopes via the existing `AgentSpanExtractor`/`AttrView` seams, with the D-07 hex id format pinned byte-for-byte to the Phase-2 `storage/SpanFixtures` constants and proven by a round-trip unit test. Added the `DecodeResult` accounting type (D-02), the `OtlpResponses.buildResponse` partial_success builder, the env-sourced `ReceiverConfig`, and the Phase-3 Ktor/gRPC/otel build dependencies (protobuf-java pinned to 4.35.1).

## What Was Built

### Task 1 — Phase-3 dependencies (`chore`, commit 261d64c)
- Catalog: `ktor = 3.5.0`, `grpc = 1.82.0`, `opentelemetry-sdk = 1.63.0`; libraries for ktor-server-core/netty/test-host, grpc-netty-shaded/stub/protobuf, otel-bom/sdk/exporter-otlp.
- `backend/build.gradle.kts`: Ktor/gRPC as `implementation`, otel + ktor-test-host as `testImplementation`, and `protobuf-java` as a DIRECT `implementation` so 4.35.1 wins over the proto artifact's transitive 4.34.0.
- No `application` plugin added (packaging deferred to Phase 4).
- Verified: `:backend:dependencies` shows `protobuf-java:4.34.0 -> 4.35.1` and `3.25.8 -> 4.35.1`, `grpc-netty-shaded:1.82.0`, `ktor-server-netty:3.5.0`.

### Task 2 — Shared value types (`feat`, commit 3fb8073)
- `DecodeResult` — immutable `data class(accepted, rejectedSpans, errorMessage)` with the `rejected==0 ⇒ errorMessage==null ⇒ partial_success UNSET` invariant in KDoc.
- `ReceiverConfig` — env-sourced ports + 32 MiB inbound caps + Retry-After; `init{}` validates ports `1..65535`, `httpPort != grpcPort`, positive caps, `retryAfter >= 0`; `fromEnv` mirrors `ClickHouseConfig.fromEnv` with `AGENT_TRACER_*` overrides via `toIntOrNull`.
- `OtlpResponses.buildResponse` — pure `object` builder; sets `partial_success` (rejected_spans + error_message) only when `rejectedSpans > 0`, else leaves it unset.

### Task 3 — OtlpDecoder + fixtures + test (`feat`, commit 49cd5e7)
- `OtlpDecoder.decode` walks RS→SS→Span once; per span calls `AgentSpanExtractor.extract(AttrView.of(span.attrs), resourceView, otelStatusIsError)` and builds a `SpanContext` from hex ids; emits `SpanWriter.SpanEnvelope`. Never throws (one bad span → `rejected++`).
- Private `ByteString.toHex()` = `joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }` — lowercase, no prefix, zero-padded; empty parent → `""`.
- `OtlpRequestFixtures` builds `Span`/`ResourceSpans`/`ExportTraceServiceRequest` and a `hexToBytes` inverse, reusing `convention/SpanFixtures` KeyValue helpers.
- `OtlpDecoderTest` — 15 tests, 0 skipped: hex round-trip vs `DEFAULT_TRACE_ID`/`DEFAULT_SPAN_ID`, empty/present parent, start-time timestamp, empty-trace/empty-span rejection, tolerant per-span (1 bad among 2 good), gen_ai token bridge, non-agent store-all (D-03), resource service.name → agentName fallback, 3 MB attr accepted (no size check), no-resource empty view, error-status outcome, determinism.

## Output Spec Confirmations (required by plan `<output>`)
- **toHex format used:** `(it.toInt() and 0xFF).toString(16).padStart(2, '0')` joined — lowercase, no `0x`, fixed-width per byte. Matches `storage/SpanFixtures.DEFAULT_TRACE_ID = "0af7651916cd43dd8448eb211c80319c"` (32 chars) and `DEFAULT_SPAN_ID = "b7ad6b7169203331"` (16 chars) exactly; empty parent = `""`. Proven by `decodedTraceIdRoundTripsToPhase2HexConstantByteForByte`.
- **Env-var names + defaults:** `AGENT_TRACER_HTTP_PORT`=4318, `AGENT_TRACER_GRPC_PORT`=4317, `AGENT_TRACER_GRPC_MAX_INBOUND_BYTES`=33554432, `AGENT_TRACER_HTTP_MAX_BODY_BYTES`=33554432, `AGENT_TRACER_RETRY_AFTER_SECONDS`=1.
- **Resolved protobuf-java version:** 4.35.1 (wins over transitive 4.34.0; confirmed in `:backend:dependencies`).

## Verification
- `./gradlew :backend:test --tests "*OtlpDecoderTest"` — green; JUnit XML: `tests="15" skipped="0" failures="0" errors="0"`.
- `./gradlew :backend:dependencies` — `protobuf-java` resolves to 4.35.1.
- `./gradlew :backend:lintKotlin` — clean (formatKotlin applied chain-continuation formatting to the test).
- D-07: decoded `context.traceId`/`spanId` equal the Phase-2 `SpanFixtures` lowercase hex constants exactly.
- D-03: a non-agent span (only `http.request.method`) decodes to an accepted envelope with agent fields null/default.

## Threat Model Coverage
- **T-03-01 (Tampering, malformed attrs):** decoder routes all attrs through `AttrView`/`AgentSpanExtractor` (never-throw); decoder mirrors the invariant — one bad span → `rejected++`, no exception. Covered by `oneBadSpanAmongGoodOnesDoesNotDropTheGoodOnes` + `resourceSpansWithNoResourceUsesEmptyViewAndDoesNotThrow`.
- **T-03-02 (DoS oversized attr, accept-by-design):** no size check in the decoder; `multiMegabyteStringAttributeSpanIsStillAcceptedNoSizeCheck` proves a 3 MB attr is accepted (truncation is downstream).
- **T-03-03 (DoS misconfig):** `ReceiverConfig.init{}` require() validates ports and positive caps, failing fast at construction.

## Deviations from Plan
None — plan executed as written. One minor in-task correction: the plan's `<behavior>`/`<action>` prescribed explicit `: Unit` block bodies for every `@Test` (Pitfall 5). The repo's ktlint config (`standard:no-unit-return`) rejects a redundant `: Unit` on a block body, and the established convention tests use plain block bodies (`fun x() { ... }`) — which are equally immune to the Pitfall-5 silent-skip (only expression-body `fun x() = matcher` is skipped). Tests use plain block bodies accordingly; "0 skipped" is asserted from the JUnit XML. This honors Pitfall 5's intent while passing the repo lint gate; not tracked as a deviation since the requirement (no silently-skipped tests) is met.

## Known Stubs
None. The HTTP route, gRPC service, and app bootstrap are intentionally out of this plan's scope (03-02/03-03/03-04) and are not stubs — `OtlpDecoder`/`OtlpResponses`/`ReceiverConfig` are fully wired to real Phase-1/Phase-2 seams and exercised by passing tests.

## Self-Check: PASSED

All 6 created source files exist on disk; all 3 per-task commits (261d64c, 3fb8073, 49cd5e7) are present in git history.
