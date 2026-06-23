---
phase: 03-otlp-ingestion
verified: 2026-06-23T00:00:00Z
status: human_needed
score: 4/4 must-have truth-groups verified at code level; 2/4 success criteria require Docker-gated operation-level observation
re_verification:
  # Initial verification — no prior VERIFICATION.md existed.
human_verification:
  - test: "Run OtlpHttpReceiverIT on native Docker/CI (./gradlew :backend:test --tests \"*OtlpHttpReceiverIT\") and observe both tests GREEN, 0 skipped"
    expected: "A real OtlpHttpSpanExporter posting to :4318 lands the span in ClickHouse, retrievable by repo.getTraceById(sameHexId) (SC1, D-07 round-trip). A stock non-agent span lands through the same store-all path with agent columns null/default (D-03)."
    why_human: "Testcontainers ryuk fails with ContainerLaunchException under this sandbox's Colima/QEMU (documented Phase-2/kore infra limit). The IT is written faithfully with the real OTel SDK exporter but cannot execute here — only native Docker/CI can observe it green."
  - test: "Run OtlpGrpcReceiverIT on native Docker/CI (./gradlew :backend:test --tests \"*OtlpGrpcReceiverIT\") and observe GREEN, 0 skipped"
    expected: "A real OtlpGrpcSpanExporter sending a 6 MB (>4 MB) prompt-heavy span to :4317 lands it in ClickHouse, truncated-not-rejected (payloadOriginalSizeBytes == 6 MiB), proving the raised maxInboundMessageSize (SC2)."
    why_human: "Same Docker-gated Testcontainers limit. The gRPC >4 MB acceptance path is exercised only by this IT against a real ClickHouse container."
  - test: "Run TracerApplicationIT on native Docker/CI (./gradlew :backend:test --tests \"*TracerApplicationIT\") and observe both tests GREEN, 0 skipped"
    expected: "schemaPresentAfterBoot proves migrate-before-serve (D-04 step 2 / Pitfall 7); drainOnShutdownLosesNoInFlightSpan drives N spans through the started HTTP receiver, runs shutdown(), and asserts every span persisted (D-04 drain-no-loss / CR-01 quiescence barrier)."
    why_human: "Same Docker-gated Testcontainers limit. The end-to-end boot-ordering and drain-no-loss behavior (the CR-01/CR-02 lifecycle fix) is observable only against a real ClickHouse container."
  - test: "Confirm the full suite (./gradlew :backend:test) is GREEN with 0 skipped on native Docker/CI before flipping INGEST-01/02/03 to complete in REQUIREMENTS.md"
    expected: "All unit suites (29 ingest tests) + the 3 Docker-gated ITs pass, 0 skipped — the VALIDATION.md release gate ('full suite GREEN, 0 skipped on native Docker')."
    why_human: "The 0-skipped assertion across the full suite including ITs cannot be made in a sandbox where the ITs cannot launch a container."
---

# Phase 3: OTLP Ingestion Verification Report

**Phase Goal:** Any OTel-compatible source — KORE, a stock OTel SDK, or telemetrygen — can export traces to agent-tracer on the standard OTLP ports and have them land in ClickHouse.
**Verified:** 2026-06-23
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

The codebase delivers both receivers (OTLP/HTTP on :4318, OTLP/gRPC on :4317) wired to the single Phase-2 `SpanWriter` batching pipeline through one transport-agnostic `OtlpDecoder`. Every must-have truth is verified at the code level: the implementation exists, is substantive (not stub), is wired (both transports share the same decoder/writer/backpressure signal and feed `writer.send` only), and the data path flows into ClickHouse via the Phase-2 pipeline.

The **only** gap is environmental: the three real-exporter end-to-end ITs that close SC1 (HTTP round-trip), SC2 (gRPC >4 MB), and SC4-end-to-end / D-04 drain are Testcontainers-gated and cannot launch a container in this sandbox (`ContainerLaunchException: testcontainers/ryuk` — the documented Colima/QEMU infra limit recorded for Phase-2/kore v0.1.0 D-03). The ITs are written faithfully against the real OTel SDK exporters and would pass on native Docker. Per the documented precedent, operation-level IT proof is classified **human_needed**, not a failed gap.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Single transport-agnostic `OtlpDecoder` walks ResourceSpans→ScopeSpans→Span → (AgentSpan, SpanContext) envelopes via AgentSpanExtractor + AttrView (D-06) | ✓ VERIFIED | `OtlpDecoder.kt:34-92` — single `decode()` nested-loop, `AgentSpanExtractor.extract(...)` at line 78, `AttrView.of(...)` at 41/75. Both transports call it. OtlpDecoderTest: 15 tests pass. |
| 2 | Store-all: every span (agent + non-agent) persisted through one decoder; no drop, no separate generic table (D-03) | ✓ VERIFIED | `OtlpDecoder.kt:77-78` every span runs through extractor; only rejection is empty id (line 73). No generic-span branch/table anywhere in ingest/. OtlpHttpReceiverIT.genericNonAgentSpanLandsThroughStoreAllPath asserts this (Docker-gated for execution). |
| 3 | trace_id/span_id/parent hex-encoded lowercase fixed-width, parent-empty='', byte-for-byte Phase-2 SpanFixtures (D-07) | ✓ VERIFIED | `OtlpDecoder.kt:85-87` + `toHex()` at line 102 (lowercase, no-prefix, per-byte padStart(2,'0')); parent `if (isEmpty) ""`. OtlpDecoderTest pins the round-trip (risk-reducer). |
| 4 | Span counted in rejectedSpans ONLY when trace/span id empty; oversized never rejected here (D-02) | ✓ VERIFIED | `OtlpDecoder.kt:73` sole rejection rule; no size check in decoder (comment lines 26-27). |
| 5 | buildResponse leaves partial_success UNSET when rejected==0, sets rejected_spans+error otherwise | ✓ VERIFIED | `OtlpResponses.kt:17-29` — `if (rejectedSpans > 0)` sets partial_success else leaves unset. |
| 6 | ReceiverConfig sources http/grpc ports, grpc max-inbound, http max-body, Retry-After from AGENT_TRACER_* env with defaults | ✓ VERIFIED | `ReceiverConfig.kt:48-67` all 5 env consts + fromEnv; defaults 4318/4317/32 MiB/32 MiB/1. ReceiverConfigTest: 4 tests pass. |
| 7 | POST /v1/traces on :4318 reads protobuf body, decodes via shared decoder, sends each accepted span via SpanWriter.send (INGEST-01/03) | ✓ VERIFIED | `OtlpHttpRoutes.kt:56-96` — receiveChannel→parseFrom→decoder.decode→`writer.send(it.span, it.context)`. OtlpHttpRoutesTest: 7 tests pass. |
| 8 | HTTP: 200 full (partial unset), 200 partial, 400 undecodable, 429+Retry-After fail-fast before body read, 413 over body cap (incl. chunked) (SC3, D-02, D-01) | ✓ VERIFIED | `OtlpHttpRoutes.kt:58-95` saturation FIRST (429+Retry-After), Content-Length 413 fast-path, `readBodyCapped` chunked 413 (WR-03 fix), 400 on InvalidProtocolBufferException, 200. OtlpHttpRoutesTest asserts each (7 named tests incl. chunked-413). |
| 9 | OtlpGrpcService extends TraceServiceImplBase, overrides export on :4317 (INGEST-02) | ✓ VERIFIED | `OtlpGrpcService.kt:77` extends `TraceServiceGrpc.TraceServiceImplBase`; `export(...)` at 83. OtlpGrpcServiceTest: 3 tests pass. |
| 10 | gRPC bridges callback StreamObserver→suspend SpanWriter.send via server-lifecycle scope, never runBlocking/Thread.sleep (Pitfall 2) | ✓ VERIFIED | `OtlpGrpcService.kt:112` `scope.launch { ... writer.send(...) }`; no runBlocking/Thread.sleep in export. |
| 11 | gRPC saturation → io.grpc.Status.RESOURCE_EXHAUSTED before launch/decode (D-01) | ✓ VERIFIED | `OtlpGrpcService.kt:88-95` first check, RESOURCE_EXHAUSTED, no decode. OtlpGrpcServiceTest.saturatedSignalFailsWithResourceExhaustedAndDoesNotDecode. |
| 12 | gRPC server configured maxInboundMessageSize = grpcMaxInboundBytes (32 MiB) so >4 MB spans accepted (SC2) | ✓ VERIFIED (code) / human (e2e) | `TracerApplication.kt:125` `.maxInboundMessageSize(receiverConfig.grpcMaxInboundBytes)`. End-to-end 6 MB acceptance is OtlpGrpcReceiverIT (Docker-gated). |
| 13 | Single fun main() migrates to completion BEFORE opening ports, starts SpanWriter, then both servers (D-04/D-05) | ✓ VERIFIED | `TracerApplication.kt:107` `runMigrations().getOrThrow()` before any `.start()`; grpc at 122-128, ktor at 131-134. |
| 14 | Shutdown closes both ports first, quiesces gRPC scope, then drainAndClose, then closes client — no span lost on SIGTERM (D-04 drain order) | ✓ VERIFIED | `TracerApplication.kt:159-175` ports→`grpcCallJob.children.join()`→`writer.drainAndClose()`→`client.close()`. CR-01/CR-02 fix. Hook registered before boot (WR-05) lines 196-203. |
| 15 | Spans from both transports flow through the single Phase-2 SpanWriter pipeline — no per-request insert path (SC4, INGEST-03) | ✓ VERIFIED | grep `ClickHouseSpanRepository`/`.insert` in ingest/ → NONE; only sink is `writer.send`. SC4 grep gate clean. |
| 16 | Real OtlpHttpSpanExporter → :4318 → span in ClickHouse via getTraceById(sameHexId) (SC1, Pitfall 1) | ⚠ human_needed | OtlpHttpReceiverIT.kt written with real `OtlpHttpSpanExporter` + `repo.getTraceById` (lines 110, 129); compiles. Operation-level proof Docker-gated. |
| 17 | Real OtlpGrpcSpanExporter >4 MB span → :4317 → ClickHouse truncated (SC2) | ⚠ human_needed | OtlpGrpcReceiverIT.kt: real exporter, 6 MB span (line 95), getTraceById assert; compiles. Docker-gated. |

**Score:** 15/15 code-level truth groups VERIFIED; 2 truths (16, 17 — SC1/SC2 end-to-end) require Docker-gated human observation.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `ingest/OtlpDecoder.kt` | transport-agnostic decode + reject accounting | ✓ VERIFIED | `class OtlpDecoder`, wired into both transports + tests. |
| `ingest/DecodeResult.kt` | accepted/rejected/error envelope | ✓ VERIFIED | `data class DecodeResult(accepted, rejectedSpans, errorMessage)`. |
| `ingest/OtlpResponses.kt` | shared partial_success builder | ✓ VERIFIED | `object OtlpResponses.buildResponse`, used by both transports. |
| `ingest/ReceiverConfig.kt` | env-sourced ports/limits | ✓ VERIFIED | data class + fromEnv + init `require` validation (WR-04 fail-fast). |
| `ingest/OtlpHttpRoutes.kt` | Route.otlpTraces OTLP/HTTP receiver | ✓ VERIFIED | `fun Route.otlpTraces`, wired in TracerApplication.installOtlp. |
| `ingest/OtlpGrpcService.kt` | TraceServiceImplBase + coroutine bridge | ✓ VERIFIED | extends base, wired via NettyServerBuilder.addService. |
| `app/TracerApplication.kt` | fun main() migrate→serve→drain both transports | ✓ VERIFIED | `fun main`, bootReceivers, shutdown drain order. |
| `ingest/OtlpDecoderTest.kt` | D-06/D-02/D-07 unit proof | ✓ VERIFIED | 15 tests, 0 skipped, 0 failures. |
| `ingest/OtlpHttpRoutesTest.kt` | 200/partial/400/429/413 conformance | ✓ VERIFIED | 7 tests, 0 skipped, 0 failures. |
| `ingest/OtlpGrpcServiceTest.kt` | success/partial/RESOURCE_EXHAUSTED | ✓ VERIFIED | 3 tests, 0 skipped, 0 failures. |
| `ingest/ReceiverConfigTest.kt` | env defaults + validation | ✓ VERIFIED | 4 tests, 0 skipped, 0 failures. |
| `ingest/OtlpHttpReceiverIT.kt` | real exporter → ClickHouse round-trip (SC1, D-03) | ⚠ ORPHANED-by-infra | Exists, compiles, real OtlpHttpSpanExporter + getTraceById. Cannot execute (Docker). |
| `ingest/OtlpGrpcReceiverIT.kt` | real exporter 6 MB → ClickHouse (SC2) | ⚠ ORPHANED-by-infra | Exists, compiles. Cannot execute (Docker). |
| `app/TracerApplicationIT.kt` | boot ordering + drain-no-loss (D-04) | ⚠ ORPHANED-by-infra | Exists, compiles. Cannot execute (Docker). |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| OtlpDecoder | AgentSpanExtractor + AttrView | `AgentSpanExtractor.extract(AttrView.of(...))` | ✓ WIRED (`OtlpDecoder.kt:78`) |
| OtlpDecoder | SpanWriter.SpanEnvelope + SpanContext | `SpanWriter.SpanEnvelope(agentSpan, SpanContext(hexIds, startTime))` | ✓ WIRED (`OtlpDecoder.kt:80-91`) |
| OtlpHttpRoutes | OtlpDecoder + OtlpResponses | `decoder.decode(request)` then `OtlpResponses.buildResponse` | ✓ WIRED (`OtlpHttpRoutes.kt:90-94`) |
| OtlpHttpRoutes | SpanWriter + BackpressureSignal | `signal.isSaturated` first, then `writer.send` | ✓ WIRED (`OtlpHttpRoutes.kt:58, 92`) |
| OtlpGrpcService | OtlpDecoder + OtlpResponses | `decoder.decode` then `buildResponse` inside launched coroutine | ✓ WIRED (`OtlpGrpcService.kt:119-122`) |
| OtlpGrpcService | SpanWriter + BackpressureSignal | `isSaturated → RESOURCE_EXHAUSTED`; else `scope.launch { writer.send }` | ✓ WIRED (`OtlpGrpcService.kt:88, 112-121`) |
| TracerApplication | MigrationRunner + ClickHouseClientFactory | `MigrationRunner(client).runMigrations().getOrThrow()` before serve | ✓ WIRED (`TracerApplication.kt:107`) |
| TracerApplication | OtlpHttpRoutes + OtlpGrpcService + SpanWriter | `embeddedServer(Netty){ otlpTraces }` + `NettyServerBuilder...addService`; `drainAndClose` on shutdown | ✓ WIRED (`TracerApplication.kt:122-134, 169`) |

### Data-Flow Trace (Level 4)

| Path | Source | Produces Real Data | Status |
|------|--------|-------------------|--------|
| HTTP receiver → ClickHouse | decoder.decode → writer.send → SpanWriter batching → ClickHouseSpanRepository | Real OTLP protobuf decoded to AgentSpan envelopes; persisted through Phase-2 pipeline (no static returns) | ✓ FLOWING (code); end-to-end persistence Docker-gated (OtlpHttpReceiverIT) |
| gRPC receiver → ClickHouse | same shared decoder + writer | Same | ✓ FLOWING (code); end-to-end Docker-gated (OtlpGrpcReceiverIT) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Unit suites compile + pass | `./gradlew :backend:test --tests "*OtlpDecoderTest" --tests "*OtlpHttpRoutesTest" --tests "*OtlpGrpcServiceTest" --tests "*ReceiverConfigTest"` | BUILD SUCCESSFUL | ✓ PASS |
| OtlpDecoderTest | result XML | tests=15 skipped=0 failures=0 errors=0 | ✓ PASS |
| OtlpHttpRoutesTest | result XML | tests=7 skipped=0 failures=0 errors=0 | ✓ PASS |
| OtlpGrpcServiceTest | result XML | tests=3 skipped=0 failures=0 errors=0 | ✓ PASS |
| ReceiverConfigTest | result XML | tests=4 skipped=0 failures=0 errors=0 | ✓ PASS |
| SC4 no per-request insert | `grep -rl ClickHouseSpanRepository ...ingest/` | no output | ✓ PASS |
| Real-exporter ITs (SC1/SC2/drain) | `./gradlew :backend:test --tests "*ReceiverIT" --tests "*TracerApplicationIT"` | cannot launch container (ContainerLaunchException ryuk) | ? SKIP → human |

### Probe Execution

No conventional `scripts/*/tests/probe-*.sh` probes declared for this JVM phase. The phase's runnable proof is the Gradle test suite (unit suites executed above; ITs Docker-gated → human). N/A.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| INGEST-01 | 03-01, 03-02, 03-04 | OTLP/HTTP-protobuf receiver on :4318 with spec-conformant partial_success + error responses | ✓ SATISFIED (code+unit); SC1 end-to-end human_needed | OtlpHttpRoutes + 7 unit tests prove SC3 status contract; OtlpHttpReceiverIT proves round-trip (Docker-gated). |
| INGEST-02 | 03-01, 03-03, 03-04 | OTLP/gRPC receiver on :4317, message size limits raised for prompt-heavy spans | ✓ SATISFIED (code+unit); SC2 end-to-end human_needed | OtlpGrpcService + maxInboundMessageSize(32 MiB) + 3 unit tests; OtlpGrpcReceiverIT proves 6 MB acceptance (Docker-gated). |
| INGEST-03 | 03-01..03-04 | Spans from both transports flow through one batching pipeline into ClickHouse (no per-request inserts) | ✓ SATISFIED | SC4 grep gate clean; only sink in ingest/ is writer.send; both transports share one SpanWriter. |

No orphaned requirements: REQUIREMENTS.md maps exactly INGEST-01/02/03 to Phase 3; all three appear in plan frontmatter. INGEST-04 (OpenInference mapping) and INGEST-05 (cross-trace subagent linkage) are explicitly deferred in REQUIREMENTS.md — out of Phase 3 scope, correctly excluded.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | — | No TBD/FIXME/XXX in any modified main or test file | — | No unresolved debt markers; completion is auditable. |
| OtlpDecoder.kt | 73 | `return null` | ℹ Info | Legitimate span-reject control flow (empty id), not a stub — accounted as rejectedSpans. |
| OtlpHttpRoutes.kt | 115 | `return null` | ℹ Info | Legitimate over-cap body guard signalling 413, not a stub. |

No TODO/HACK/PLACEHOLDER, no stub returns, no empty handlers. The 03-REVIEW.md Critical (CR-01, CR-02) and Warning (WR-01..WR-05) findings are all FIXED in commits a7e5fd4 / 6f433f1 / af846ef; the chunked-body 413 (WR-03) and shutdown-hook window (WR-05) fixes are present in the read source and exercised by OtlpHttpRoutesTest.chunkedOversizedBodyReturns413.

### Human Verification Required

The phase goal — spans actually *land in ClickHouse* from a real exporter — is proven end-to-end only by three Testcontainers ITs that cannot launch a container in this sandbox (`ContainerLaunchException: testcontainers/ryuk`, the documented Colima/QEMU infra limit, mirrored from Phase-2/kore v0.1.0 D-03). The ITs are written faithfully with the real `OtlpHttpSpanExporter` / `OtlpGrpcSpanExporter` and `repo.getTraceById` round-trip assertions; they compile. Run them on native Docker / CI and observe GREEN, 0 skipped, before flipping INGEST-01/02/03 to complete:

1. **OtlpHttpReceiverIT (SC1, D-07, D-03)** — real HTTP exporter → :4318 → ClickHouse; `getTraceById(knownHexId)` round-trip + generic non-agent span store-all.
2. **OtlpGrpcReceiverIT (SC2)** — real gRPC exporter, 6 MB span → :4317 → ClickHouse, truncated-not-rejected.
3. **TracerApplicationIT (D-04)** — migrate-before-serve + drain-on-shutdown loses no in-flight span.
4. **Full-suite 0-skipped gate** — `./gradlew :backend:test` GREEN, 0 skipped on native Docker (the VALIDATION.md release gate).

### Gaps Summary

No code gaps. Both OTLP receivers exist, are substantive, are wired to the single Phase-2 `SpanWriter` pipeline through one shared `OtlpDecoder`, and the SC3 spec-conformance contract (partial_success, 400, 429/RESOURCE_EXHAUSTED fail-fast, 413) is fully unit-proven (29 ingest tests, 0 skipped, 0 failures, lint/compile clean). SC4 (single pipeline, no per-request insert) is verified by the clean grep gate and source review. The review's Critical+Warning findings are fixed and committed.

The sole reason status is **human_needed** rather than **passed**: SC1 (HTTP round-trip → ClickHouse) and SC2 (gRPC >4 MB → ClickHouse) — the literal "land in ClickHouse" half of the phase goal — are observable only through Docker-gated real-exporter ITs that cannot execute in this sandbox. Per the documented Phase-2/kore precedent, this is deferred to native-Docker observation, not marked failed. Do not flip INGEST-01/02/03 to complete until the four human-verification items above are observed green.

---

_Verified: 2026-06-23_
_Verifier: Claude (gsd-verifier)_
