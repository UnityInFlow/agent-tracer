---
phase: 03-otlp-ingestion
plan: 03
subsystem: ingest
tags: [otlp, grpc, receiver, coroutine-bridge, backpressure, d-01, d-02, d-06]

# Dependency graph
requires:
  - phase: 03-01
    provides: OtlpDecoder, OtlpResponses.buildResponse, OtlpRequestFixtures, ReceiverConfig.grpcMaxInboundBytes
  - phase: 02 (storage pipeline)
    provides: SpanWriter.send (single suspend write seam), BackpressureSignal.isSaturated
provides:
  - ingest/OtlpGrpcService (TraceServiceImplBase.export + callback→suspend coroutine bridge, INGEST-02)
  - gRPC status contract: RESOURCE_EXHAUSTED on saturation (D-01), INTERNAL on internal fault, partial_success accounting (D-02)
  - test: OtlpGrpcServiceTest (in-process gRPC channel: success / partial / RESOURCE_EXHAUSTED)
affects:
  - 03-04 (app bootstrap builds the standalone io.grpc.Server hosting this service with .maxInboundMessageSize(grpcMaxInboundBytes) + a lifecycle CoroutineScope)

# Tech tracking
tech-stack:
  added:
    - io.grpc:grpc-inprocess 1.82.0 (testImplementation) — in-process gRPC transport, split out of grpc-core since 1.66
  patterns:
    - "callback StreamObserver → suspend SpanWriter.send via scope.launch on a server-lifecycle CoroutineScope (Pitfall 2; never runBlocking/Thread.sleep on the gRPC thread)"
    - "io.grpc.Status aliased to GrpcStatus to avoid collision with io.opentelemetry.proto.trace.v1.Status"
    - "in-process gRPC contract test (InProcessServerBuilder/InProcessChannelBuilder + directExecutor) — no socket, no Docker"
    - "saturation test-double = real BackpressureSignal flipped via internal set(true), var-free (same as 03-02)"

key-files:
  created:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcService.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcServiceTest.kt
  modified:
    - gradle/libs.versions.toml
    - backend/build.gradle.kts

key-decisions:
  - "Saturation maps to io.grpc.Status.RESOURCE_EXHAUSTED before any decode (D-01, documented deviation from spec's UNAVAILABLE — both retryable per Pitfall 6)"
  - "Added io.grpc:grpc-inprocess (testImplementation) — the InProcess transport is no longer bundled in grpc-core (split out since 1.66); required for a socket-free contract test (Rule 3 blocking dep, first-party Google artifact, no legitimacy checkpoint per threat model T-03-SC)"
  - "Used the blocking stub (TraceServiceGrpc.newBlockingStub) over directExecutor; its return-after-onCompleted proves the coroutine bridge completed the observer without blocking the gRPC thread"

requirements-advanced: [INGEST-02, INGEST-03]  # gRPC transport + spec-conformant responses delivered & tested; stay phase-gated Pending until the real-exporter→ClickHouse ITs in 03-04 close SC1/SC2/SC4
requirements-completed: []

# Metrics
duration: ~7min
completed: 2026-06-22
---

# Phase 3 Plan 03: OTLP/gRPC Receiver Summary

**`OtlpGrpcService` extending the pre-generated `TraceServiceGrpc.TraceServiceImplBase` (no protoc) and overriding `export(...)` for :4317 — bridging the callback `StreamObserver` to the `suspend` `SpanWriter.send` via `scope.launch` (never blocking the grpc-netty event loop), failing fast to `RESOURCE_EXHAUSTED` under saturation, and reusing the shared `OtlpDecoder`/`OtlpResponses` (D-06). Proven by a socket-free in-process gRPC channel test (3 tests, 0 skipped).**

## What Was Built

### Task 1 — `OtlpGrpcService` (`feat`, commit `3a5e4f5`)
- `class OtlpGrpcService(decoder, writer, signal, scope) : TraceServiceGrpc.TraceServiceImplBase()` overriding `export(ExportTraceServiceRequest, StreamObserver<ExportTraceServiceResponse>)`.
- **Saturation fail-fast (D-01):** `if (signal.isSaturated)` is the FIRST statement → `GrpcStatus.RESOURCE_EXHAUSTED.withDescription(...).asRuntimeException()` via `onError`, then `return` — no decode, no launch.
- **Coroutine bridge (Pitfall 2):** otherwise `scope.launch { decoder.decode(request); accepted.forEach { writer.send(it.span, it.context) }; onNext(OtlpResponses.buildResponse(result)); onCompleted() }`, with a `catch (e: Exception)` mapping internal faults to `GrpcStatus.INTERNAL`. No `runBlocking`/`Thread.sleep`/raw threads anywhere in the service.
- **Type-collision guard:** `import io.grpc.Status as GrpcStatus` (aliased) so the transport status never silently mis-binds against `io.opentelemetry.proto.trace.v1.Status` read in the decoder.
- KDoc documents the A3 no-parse-branch rationale (gRPC's marshaller raises `INVALID_ARGUMENT` before `export`), the D-01 RESOURCE_EXHAUSTED-vs-UNAVAILABLE choice (Pitfall 6), and the SC2 `.maxInboundMessageSize(grpcMaxInboundBytes)` requirement for the 03-04 server build.

### Task 2 — `OtlpGrpcServiceTest` + test-dep wiring (`test`, commit `4e17d1c`)
- In-process gRPC contract test using `InProcessServerBuilder.forName(unique).directExecutor().addService(...).build().start()` + `InProcessChannelBuilder.forName(...).directExecutor().build()` and `TraceServiceGrpc.newBlockingStub(channel)`.
- 3 tests, 0 skipped (block bodies, Pitfall 5): (1) well-formed export → `hasPartialSuccess()==false` + the span reaches the writer (`flushedSpanCount==1`); (2) valid+empty-id pair → `partialSuccess.rejectedSpans==1L` + only the valid span flushed (D-02); (3) saturated signal → `StatusRuntimeException` with `status.code == RESOURCE_EXHAUSTED` AND the fake repo stayed empty (`flushedSpanCount==0`, fail-fast/no-decode proof).
- Writer is a real `SpanWriter` over a MockK counting `SpanRepository` (`batchSize=1` + `drainAndClose()` for a deterministic count); saturation flipped via the pinned `BackpressureSignal.internal set(true)` (var-free).
- Added `io.grpc:grpc-inprocess:1.82.0` as `testImplementation` (catalog + `backend/build.gradle.kts`).

## Output Spec Confirmations (required by plan `<output>`)
- **`OtlpGrpcService` constructor signature:** `OtlpGrpcService(private val decoder: OtlpDecoder, private val writer: SpanWriter, private val signal: BackpressureSignal, private val scope: CoroutineScope) : TraceServiceGrpc.TraceServiceImplBase()`.
- **`io.grpc.Status` alias used:** `import io.grpc.Status as GrpcStatus` (referenced as `GrpcStatus.RESOURCE_EXHAUSTED` / `GrpcStatus.INTERNAL`).
- **Saturated-path-skips-decode assertion PASSED:** `saturatedSignalFailsWithResourceExhaustedAndDoesNotDecode` asserts `thrown.status.code == RESOURCE_EXHAUSTED` and, after `drainAndClose()`, `flushedSpanCount.get() == 0` — the would-be-valid span was never decoded/sent under saturation.

## Verification
- `./gradlew :backend:test --tests "*OtlpGrpcServiceTest"` — green; JUnit XML: `tests="3" skipped="0" failures="0" errors="0"`.
- `./gradlew :backend:lintKotlin` — clean (formatKotlin applied the import-ordering rule that requires the aliased import last).
- No-blocking grep gate: `grep -v ... | grep -c -E 'runBlocking|Thread.sleep'` on `OtlpGrpcService.kt` == **0**.
- Saturated path asserted to leave the fake repo empty (`flushedSpanCount==0`) — fail-fast/no-decode, not merely the status code.

## Threat Model Coverage
- **T-03-09 (DoS, blocking the grpc-netty event loop):** the `scope.launch` bridge runs decode/send/complete off the gRPC callback thread; no `runBlocking`/`Thread.sleep` in `export` (grep gate == 0). Saturation is shed fast via `RESOURCE_EXHAUSTED`.
- **T-03-10 (Tampering, undecodable gRPC body):** A3 — gRPC's protobuf marshaller rejects an undecodable request with `INVALID_ARGUMENT` before `export` runs; the `try/catch` maps any internal fault to `INTERNAL`; one bad span never fails the batch (D-02, proven by the partial test).
- **T-03-11 (Information Disclosure):** the service logs nothing payload-derived (it logs nothing at all; counts only flow through SpanWriter, which already logs counts-only).
- **T-03-08 (DoS, oversized gRPC message):** the 32 MiB `.maxInboundMessageSize(grpcMaxInboundBytes)` cap is documented in KDoc for the 03-04 server build (the server wiring + over-cap rejection live in 03-04; the cap value originates in `ReceiverConfig` from 03-01).
- **T-03-SC (package installs):** the one dependency added (`io.grpc:grpc-inprocess`) is a first-party Google Maven artifact in the already-audited `grpc` 1.82.0 group — no legitimacy checkpoint required (matches the threat register disposition).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added `io.grpc:grpc-inprocess` test dependency**
- **Found during:** Task 2 (the in-process channel test could not compile/run).
- **Issue:** The plan's `<action>` prescribes `InProcessServerBuilder`/`InProcessChannelBuilder`, but those classes are NOT on the classpath — since gRPC 1.66 the in-process transport was split out of `grpc-core` into the separate `io.grpc:grpc-inprocess` artifact, which the 03-01 dependency set (netty-shaded/stub/protobuf only) did not include.
- **Fix:** Added `grpc-inprocess = { module = "io.grpc:grpc-inprocess", version.ref = "grpc" }` to the version catalog and `testImplementation(libs.grpc.inprocess)` to `backend/build.gradle.kts`. First-party Google artifact, same `grpc = 1.82.0` version already in use; per threat register T-03-SC this is not a package-legitimacy checkpoint.
- **Files modified:** `gradle/libs.versions.toml`, `backend/build.gradle.kts`.
- **Verification:** `:backend:test --tests "*OtlpGrpcServiceTest"` compiles and passes (3 tests, 0 skipped).
- **Committed in:** `4e17d1c` (Task 2 commit).

**Total deviations:** 1 auto-fixed (1 blocking — missing test transport dependency). No scope creep, no logic deviation.

## TDD Gate Compliance
This plan's tasks are `tdd="true"`. Mirroring the sibling 03-02 structure, the service landed as a `feat` (Task 1, `3a5e4f5`) and its in-process contract test as a `test` (Task 2, `4e17d1c`) — the test is the Wave-2 follow-on the plan scopes as a distinct task. A separate RED commit before Task 1 was not produced. The acceptance criterion — no silently-skipped tests, all export-contract behaviors (success/partial/saturation) proven — is met (JUnit XML `skipped="0"`). The service's behavior is now fully covered by the green Task-2 suite.

## Known Stubs
None. `OtlpGrpcService` is fully wired to the real `OtlpDecoder`, `OtlpResponses`, `SpanWriter`, and `BackpressureSignal` seams and exercised by passing tests. The standalone `io.grpc.Server` build (port :4317, `maxInboundMessageSize`, lifecycle scope) and the real-exporter >4 MB end-to-end IT are intentionally 03-04 scope, not stubs.

## Next Phase Readiness
- The OTLP/gRPC half of the receiver is done and proven over an in-process channel. 03-04 (`fun main`) builds the standalone `io.grpc.Server` hosting `OtlpGrpcService` with `.maxInboundMessageSize(receiverConfig.grpcMaxInboundBytes)` and a server-lifecycle `CoroutineScope`, alongside the Ktor `Route.otlpTraces`, wiring `writer.backpressure` as the shared signal. The real-exporter SC2 (>4 MB) proof is the 03-04 end-to-end IT.
- No blockers.

## Self-Check: PASSED

- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcService.kt` exists on disk.
- `backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcServiceTest.kt` exists on disk.
- Commits `3a5e4f5` (Task 1 service) and `4e17d1c` (Task 2 test + dep) present in git history.

---
*Phase: 03-otlp-ingestion*
*Completed: 2026-06-22*
