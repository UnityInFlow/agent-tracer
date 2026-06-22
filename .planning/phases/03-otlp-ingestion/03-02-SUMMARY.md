---
phase: 03-otlp-ingestion
plan: 02
subsystem: ingest
tags: [otlp, http, ktor, protobuf, receiver, backpressure, d-01, d-02]

# Dependency graph
requires:
  - phase: 03-01
    provides: OtlpDecoder, OtlpResponses.buildResponse, ReceiverConfig, OtlpRequestFixtures
  - phase: 02 (storage pipeline)
    provides: SpanWriter.send (single write seam), BackpressureSignal.isSaturated
provides:
  - ingest/OtlpHttpRoutes.Route.otlpTraces (Ktor POST /v1/traces on :4318)
  - HTTP status contract 200 (full/partial) / 400 (undecodable) / 429+Retry-After (saturated) / 413 (oversized)
  - test: OtlpHttpRoutesTest (ktor-server-test-host status-code conformance)
affects:
  - 03-03 (gRPC service shares OtlpDecoder + buildResponse + the same BackpressureSignal fail-fast)
  - 03-04 (app bootstrap wires Route.otlpTraces into the Ktor server with writer.backpressure)

# Tech tracking
tech-stack:
  added: []  # all deps (ktor-server-test-host, ktor-server-core/netty) added in 03-01
  patterns:
    - "ktor-server-test-host testApplication { } in-process conformance test (first in repo)"
    - "saturation test-double = real BackpressureSignal flipped via internal set(true), var-free"
    - "fail-fast assertion = flushed-span count == 0 proves body NOT decoded under 429/400/413"

key-files:
  created:
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutesTest.kt
  modified: []  # OtlpHttpRoutes.kt was created+committed in Task 1 (this plan, 16a79fe)

key-decisions:
  - "Test asserts fail-fast ordering behaviorally (writer received 0 spans on 429/400/413), not just the status code"
  - "batchSize=1 + drainAndClose() makes the flushed-span count deterministic without virtual time (testApplication runs real Netty test engine, not runTest)"
  - "Saturation flipped via BackpressureSignal.internal set(true) — the one pinned mechanism; no var-based stub, no subclass (isSaturated is a non-open val)"

patterns-established:
  - "Pattern 1: ktor-server-test-host testApplication for HTTP status conformance — no socket, no Docker"
  - "Pattern 2: real SpanWriter over a MockK counting SpanRepository to observe the write seam from a route test"

requirements-advanced: [INGEST-01, INGEST-03]  # HTTP transport + spec-conformant responses delivered & tested; both stay phase-gated Pending until the real-exporter→ClickHouse ITs in 03-04 close SC1/SC4
requirements-completed: []

# Metrics
duration: ~10min
completed: 2026-06-22
---

# Phase 3 Plan 02: OTLP/HTTP Receiver Summary

**Ktor `Route.otlpTraces` serving `POST /v1/traces` on :4318 with saturation-first 429+Retry-After, a Content-Length DoS guard (413), 400-on-undecodable, and 200 full/partial — proven end-to-end by an in-process ktor-server-test-host conformance suite (5 tests, 0 skipped).**

## Performance

- **Duration:** ~10 min (resumed run; Task 1 was completed in the prior interrupted run)
- **Completed:** 2026-06-22T19:14Z
- **Tasks:** 2 (Task 1 from prior run, Task 2 this run)
- **Files modified:** 1 created this run (test); 1 created in Task 1 (route)

## Accomplishments
- `Route.otlpTraces(decoder, writer, signal, config)` — the OTLP/HTTP-protobuf receiver a stock `OtlpHttpSpanExporter` posts to (Task 1, `16a79fe`).
- Saturation is the FIRST statement (D-01 fail-fast): `signal.isSaturated` → 429 + `Retry-After` BEFORE any body read/decode.
- Content-Length body-size guard (T-03-04 DoS mitigation): a declared length over `config.httpMaxBodyBytes` → 413 before `call.receive`, mirroring the gRPC `maxInboundMessageSize` cap.
- 400 on an undecodable protobuf body — the sole transport-level error (D-02); accepted spans flow only through `SpanWriter.send` (INGEST-03), never a repository call.
- `OtlpHttpRoutesTest` proves all five behaviors in-process via `testApplication`, and proves the fail-fast/reject-before-decode ordering by asserting the writer received **zero** spans on the 429/400/413 paths (not merely the status code).

## Task Commits

Each task was committed atomically:

1. **Task 1: Route.otlpTraces (route + saturation-first ordering + body-size guard)** - `16a79fe` (feat) — prior run
2. **Task 2: OtlpHttpRoutesTest (ktor-server-test-host status-code conformance)** - `a42393b` (test) — this run

**Plan metadata:** _(this commit)_ (docs: complete plan)

_Per-task TDD note: this plan's tasks are `tdd="true"`. The route landed as a `feat` in Task 1 and its conformance test as a `test` in Task 2 (the test was the Wave-2 follow-on the plan scopes as a distinct task). The route's behavior is now fully covered by the green Task-2 suite._

## Files Created/Modified
- `backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutesTest.kt` — 5 `testApplication` tests: 200-full (partial_success UNSET, span reaches writer), 200-partial (rejected_spans==1), 400-undecodable (writer gets 0), 429+Retry-After=7 (body NOT decoded, writer gets 0), 413-oversized (tiny `httpMaxBodyBytes`, writer gets 0).
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutes.kt` — (Task 1, `16a79fe`) the route under test; unchanged this run.

## Output Spec Confirmations (required by plan `<output>`)
- **`Route.otlpTraces` signature:** `fun Route.otlpTraces(decoder: OtlpDecoder, writer: SpanWriter, signal: BackpressureSignal, config: ReceiverConfig)` registering `post("/v1/traces")`.
- **Body-size guard mechanism:** `call.request.contentLength()` — if non-null and `> config.httpMaxBodyBytes`, respond 413 with an empty `application/x-protobuf` body BEFORE `call.receive` (the cheap pre-read Content-Length guard).
- **429-skips-decode assertion PASSED:** `saturatedSignalReturns429WithRetryAfterAndDoesNotDecodeBody` posts a body that *would* decode, gets 429 + `Retry-After: 7`, and asserts `flushedSpanCount == 0` after `drainAndClose()` — the body was never decoded under saturation.

## Decisions Made
- Asserted fail-fast ordering **behaviorally** (writer received 0 spans), not just by status code — this is what actually proves "saturation checked before decode" and "reject before `call.receive`".
- Used `batchSize = 1` + `drainAndClose()` for a deterministic flushed-span count, since `testApplication` runs the real Netty test engine (no `runTest` virtual time available inside it).
- Flipped saturation via the pinned `BackpressureSignal.internal set(true)` (same Gradle module) — `isSaturated` is a non-open `val`, so no subclass/supplier stub is possible and none was attempted; fully `var`-free per CLAUDE.md.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Removed two ktlint `no-unused-imports` violations in the new test**
- **Found during:** Task 2 (`:backend:lintKotlin` gate after writing the test)
- **Issue:** Initial test imports included `io.ktor.client.request.header` and `ExportTraceServiceRequest`, which the final code did not reference — `standard:no-unused-imports` lint errors block the commit gate.
- **Fix:** Ran `./gradlew :backend:formatKotlin` (the repo's established auto-fix path, per 03-01) which removed the unused imports; no logic change.
- **Files modified:** `backend/.../ingest/OtlpHttpRoutesTest.kt`
- **Verification:** `:backend:lintKotlin` clean; `:backend:test --tests "*OtlpHttpRoutesTest"` still green (5 tests, 0 skipped).
- **Committed in:** `a42393b` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking — lint). No scope creep.
**Impact on plan:** A formatting-only fix required to pass the ktlint commit gate. Behavior unchanged.

## Issues Encountered
None. The first `:backend:test` compiled and passed on the first run; the only follow-up was the ktlint unused-import cleanup above.

## TDD Gate Compliance
The route's behavior is fully covered by the green Task-2 conformance suite (5 tests, 0 skipped, all five status paths). Per-task gate sequence: `feat` (Task 1, `16a79fe`) → `test` (Task 2, `a42393b`). A separate RED commit before Task 1 was not produced in the prior interrupted run; this is noted for transparency. The acceptance criterion — no silently-skipped tests, all status behaviors proven — is met (JUnit XML: `tests="5" skipped="0" failures="0" errors="0"`).

## Known Stubs
None. The route and its test are fully wired to the real `OtlpDecoder`, `OtlpResponses`, `SpanWriter`, and `BackpressureSignal` seams. The gRPC receiver (03-03) and app bootstrap (03-04) are intentionally out of this plan's scope, not stubs.

## Verification
- `./gradlew :backend:test --tests "*OtlpHttpRoutesTest"` — BUILD SUCCESSFUL; JUnit XML `tests="5" skipped="0" failures="0" errors="0"`.
- `./gradlew :backend:lintKotlin` — clean.
- 429 path asserted to NOT decode the body (`flushedSpanCount == 0`) — fail-fast ordering proven, not just the status code.

## Next Phase Readiness
- The OTLP/HTTP half of the receiver is done and proven. 03-03 (gRPC service) reuses the identical `OtlpDecoder` → `OtlpResponses.buildResponse` → `SpanWriter.send` seam plus the same `BackpressureSignal` fail-fast (mapped to `RESOURCE_EXHAUSTED`). 03-04 (`fun main`) wires `Route.otlpTraces` into the Ktor server with `writer.backpressure` as the signal.
- No blockers.

## Self-Check: PASSED

- `backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutesTest.kt` exists on disk.
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutes.kt` (Task 1) exists on disk.
- Commits `16a79fe` (Task 1 route) and `a42393b` (Task 2 test) present in git history.

---
*Phase: 03-otlp-ingestion*
*Completed: 2026-06-22*
