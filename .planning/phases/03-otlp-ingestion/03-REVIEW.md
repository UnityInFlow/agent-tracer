---
phase: 03-otlp-ingestion
reviewed: 2026-06-23T00:00:00Z
depth: standard
files_reviewed: 12
files_reviewed_list:
  - backend/build.gradle.kts
  - gradle/libs.versions.toml
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/DecodeResult.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpDecoder.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcService.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutes.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpResponses.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/ReceiverConfig.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpDecoderTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutesTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcServiceTest.kt
findings:
  critical: 2
  warning: 5
  info: 3
  total: 10
status: issues_found
---

# Phase 3: Code Review Report

**Reviewed:** 2026-06-23T00:00:00Z
**Depth:** standard
**Files Reviewed:** 12
**Status:** issues_found

## Summary

Phase 3 ships two OTLP trace receivers (Ktor HTTP :4318 + standalone gRPC :4317) feeding the
shared Phase-2 `SpanWriter`. The decode path, partial_success accounting, D-07 hex encoding, and
HTTP status mapping are correct and well-tested at unit level. Kotlin style rules (no `var`, no
`!!`, coroutines-only) are honored throughout.

However, the lifecycle wiring in `TracerApplication.kt` has two correctness defects that can cause
**span loss and hung gRPC calls on shutdown** — exactly the failure modes the D-04 drain order was
designed to prevent. Both are concurrency-ordering bugs between the shared `scope`, the gRPC
per-call coroutines, and `SpanWriter.drainAndClose()`. There is also an uncaught-exception path in
the gRPC bridge that can crash a per-call coroutine, and a duplicate-KDoc/dead-doc cleanup set.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Shutdown drains the writer before in-flight gRPC `export` coroutines finish sending — span loss + `ClosedSendChannelException`

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt:137-147`
(interacts with `OtlpGrpcService.kt:88-102` and `SpanWriter.kt:142-145`)

**Issue:** `OtlpGrpcService.export` does `scope.launch { ... writer.send(...) ... }` — the decode and
`writer.send` run asynchronously on the lifecycle `scope`, decoupled from the gRPC call thread.
`grpc.shutdown().awaitTermination(...)` waits for the gRPC *transport* to terminate (no new RPCs,
in-flight RPC threads return), but it does **not** wait for the coroutines those RPCs launched onto
`scope`. So the ordering in `shutdown()` is:

1. `grpc.shutdown().awaitTermination(...)` — transport down, but a launched `export` coroutine may
   still be mid-`decoder.decode` / mid-`writer.send`.
2. `writer.drainAndClose()` — `intake.close()` then `writer.join()`.

If a still-running `export` coroutine calls `writer.send(...)` *after* step 2 closed the intake
channel, `Channel.send` on a closed channel throws `ClosedSendChannelException`. That exception is
caught by the bridge's `catch (e: Exception)` and mapped to `onError(INTERNAL)` — but the channel is
already closed, so **those accepted spans are silently dropped**, directly violating the D-04
"no span lost on SIGTERM" guarantee and T-03-14. There is no `scope`-quiescence step (e.g.
`scope.coroutineContext.job.children.forEach { it.join() }` or draining the launched jobs) between
closing the gRPC port and closing the intake.

**Fix:** Wait for the gRPC service's launched coroutines to quiesce before closing the intake. One
option — give the gRPC service its own child job and join it after `grpc.awaitTermination`:

```kotlin
// In bootReceivers: derive a child scope for gRPC per-call work so it can be joined independently.
val grpcCallJob = SupervisorJob(scope.coroutineContext.job)
val grpcScope = CoroutineScope(scope.coroutineContext + grpcCallJob)
// ... addService(OtlpGrpcService(decoder, writer, writer.backpressure, grpcScope))

// In shutdown(), between closing the port and draining the writer:
grpc.shutdown().awaitTermination(GRPC_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
ktor.stop(gracePeriodMillis = KTOR_GRACE_MILLIS, timeoutMillis = KTOR_SHUTDOWN_TIMEOUT_MILLIS)
grpcCallJob.children.forEach { it.join() }   // let in-flight export coroutines finish sending
writer.drainAndClose()                       // now safe — no more send() calls can happen
client.close()
scope.cancel()
```

### CR-02: `scope.cancel()` ordering + launch-after-cancel can leave gRPC calls hung and silently drop sends

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt:144-145`,
`145` (interacts with `OtlpGrpcService.kt:88`)

**Issue:** Two related defects on the same lifecycle scope:

1. **Launch-after-cancel is a silent no-op.** `scope` is a `SupervisorJob() + Dispatchers.Default`.
   After `scope.cancel()` (shutdown step 3), any `export` call still admitted before the transport
   fully drained will execute `scope.launch { ... }` on a cancelled scope. `launch` on a cancelled
   scope produces an already-cancelled coroutine whose body never runs — so `responseObserver` is
   **never completed**: the client call hangs until its own deadline/timeout instead of getting a
   clean response or `UNAVAILABLE`. The body also never reaches `writer.send`, so the span is
   dropped with no error surfaced.

2. **`client.close()` precedes `scope.cancel()`** while the writer (also on `scope`) has already
   been joined — that part is fine — but because the *same* `scope` backs both the writer and the
   gRPC per-call coroutines, there is no clean boundary that distinguishes "writer done" from
   "gRPC callbacks done." This is the structural root of CR-01: a single flat scope is shared by a
   producer (gRPC bridge) and the consumer (writer) without an ordering barrier between them.

**Fix:** Separate the gRPC call scope from the writer scope (see CR-01 fix), and guard the bridge so
a cancelled scope still terminates the observer instead of hanging:

```kotlin
override fun export(request: ..., responseObserver: ...) {
    if (signal.isSaturated) { /* RESOURCE_EXHAUSTED, return */ }
    if (!scope.isActive) {                       // shutting down: don't accept new work silently
        responseObserver.onError(
            GrpcStatus.UNAVAILABLE.withDescription("server shutting down").asRuntimeException(),
        )
        return
    }
    scope.launch { ... }
}
```

Ordering in `shutdown()` should be: close ports → join gRPC call job → `drainAndClose()` →
`client.close()` → `scope.cancel()`.

## Warnings

### WR-01: gRPC bridge `catch` can call `onError` on an already-completed observer → `IllegalStateException` in the coroutine

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcService.kt:88-102`

**Issue:** Inside the launched coroutine, `responseObserver.onNext(...)` then `onCompleted()` run
inside the same `try`. If `onNext`/`onCompleted` itself throws (e.g. the client already cancelled the
call, so the observer is in a terminal state), control falls into `catch`, which calls
`responseObserver.onError(...)` on an **already-terminated** observer. grpc-java throws
`IllegalStateException("call already closed")` from `onError` in that state, which then escapes the
coroutine and reaches the `SupervisorJob`'s default handler — noisy logs, and the original fault is
masked. The `catch` should only wrap the decode/send work, not the terminal observer calls.

**Fix:** Narrow the `try` to the decode/send, and complete the observer outside it (or guard against
double-termination):

```kotlin
scope.launch {
    val response = try {
        val result = decoder.decode(request)
        result.accepted.forEach { writer.send(it.span, it.context) }
        OtlpResponses.buildResponse(result)
    } catch (e: Exception) {
        responseObserver.onError(GrpcStatus.INTERNAL.withDescription(e.message).asRuntimeException())
        return@launch
    }
    responseObserver.onNext(response)
    responseObserver.onCompleted()
}
```

### WR-02: `CancellationException` is swallowed by the broad `catch (e: Exception)` in the gRPC bridge

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcService.kt:95`

**Issue:** `catch (e: Exception)` catches `kotlinx.coroutines.CancellationException` (it extends
`IllegalStateException` → `Exception`). If the `scope` is cancelled while `writer.send` is suspended
mid-call, the `CancellationException` is caught and converted into a normal `onError(INTERNAL)`
response instead of propagating cooperative cancellation. This both breaks structured-concurrency
cancellation semantics and reports an internal error for what is really a shutdown. Cancellation must
be rethrown.

**Fix:** Rethrow cancellation before the generic handler:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    responseObserver.onError(GrpcStatus.INTERNAL.withDescription(e.message).asRuntimeException())
}
```

### WR-03: HTTP body-size guard relies solely on `Content-Length`; a chunked / missing-length upload bypasses the 413 DoS cap

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpHttpRoutes.kt:59-65`

**Issue:** The DoS guard is `declaredLength != null && declaredLength > httpMaxBodyBytes`. When the
client sends `Transfer-Encoding: chunked` (or otherwise omits `Content-Length`), `contentLength()`
returns `null` and the guard is skipped entirely — `call.receive<ByteArray>()` then buffers the
**entire** untrusted body into memory with no cap. A malicious client can stream an arbitrarily large
chunked POST and defeat the 32 MiB bound. The gRPC side is correctly capped by
`maxInboundMessageSize`; the HTTP side is not, despite the KDoc claiming symmetry.

**Fix:** Enforce the cap on the actual bytes read, not just the declared length — either cap the read
or check the received size before decoding:

```kotlin
val body = call.receive<ByteArray>()
if (body.size > config.httpMaxBodyBytes) {
    call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.PayloadTooLarge) { ByteArray(0) }
    return@post
}
```

(Better still, install a Ktor limit on the receive pipeline so the body is never fully buffered.)

### WR-04: `ReceiverConfig.fromEnv` silently swallows malformed env values, masking misconfiguration

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/ReceiverConfig.kt:59-66`

**Issue:** Each knob uses `env(...)?.toIntOrNull() ?: DEFAULT`. If an operator sets
`AGENT_TRACER_HTTP_PORT=l8080` (typo) or `AGENT_TRACER_GRPC_MAX_INBOUND_BYTES=32MB`, `toIntOrNull`
returns `null` and the code **silently falls back to the default** rather than failing fast. This
contradicts the class KDoc's own "misconfiguration surfaces at boot, not at request time" promise:
a port typo means the server binds an unexpected port with no error. The `init` block validates
*range* but never sees the malformed string. (Same pattern exists in `ClickHouseConfig.fromEnv`, so
it is a deliberate convention — but for a security-relevant size cap and a listen port, silent
fallback is the wrong default.)

**Fix:** Distinguish "unset" (use default) from "set but unparseable" (fail fast):

```kotlin
private fun intEnv(env: (String) -> String?, key: String, default: Int): Int {
    val raw = env(key) ?: return default
    return raw.toIntOrNull() ?: error("$key must be an integer, got '$raw'")
}
```

### WR-05: `main()` adds the shutdown hook AFTER `bootReceivers` opens both ports — a SIGTERM during startup leaks the listeners and the writer

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt:154-170`

**Issue:** `bootReceivers` starts the gRPC server, the Ktor server, and the `SpanWriter` writer
coroutine (lines 103-118) *before* `main` registers the shutdown hook (lines 162-166). If the process
receives `SIGTERM` in that window — or if `bootReceivers` succeeds but `addShutdownHook` is delayed —
the drain sequence never runs: ports stay open, the writer coroutine and ClickHouse client are not
closed, and any buffered spans are lost. The "no span lost on SIGTERM" guarantee has a startup gap.

**Fix:** Register the shutdown hook against the live handle as the first action after boot, and make
boot+hook-registration atomic from the caller's view (e.g. register inside `bootReceivers` or
immediately wrap with `try`/`finally` that drains on any early failure). At minimum, ensure no
suspending work runs between `bootReceivers` returning and `addShutdownHook`.

## Info

### IN-01: Duplicate / stale lifecycle KDoc block above unrelated `private const val`s

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt:32-56`

**Issue:** The large class-level KDoc (lines 32-53) documents the bootstrap lifecycle but sits
immediately above the three `private const val` timeout declarations (lines 54-56), not above the
`RunningReceivers` class or `bootReceivers` it actually describes. The doc is effectively attached to
a constant, and a second overlapping KDoc describing the same lifecycle appears again on
`RunningReceivers` (58-70) and `bootReceivers` (81-88). This is harmless but confusing; the
authoritative lifecycle doc should sit on `bootReceivers`/`shutdown`, and the const block should have
its own one-line comment.

**Fix:** Move the lifecycle KDoc onto `bootReceivers` (or a file-level doc) and give the constants a
short comment of their own.

### IN-02: `OtlpResponses.buildResponse` uses mutable builder assignment style that could be expression-chained

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpResponses.kt:18-28`

**Issue:** Minor style: `val builder = newBuilder(); if (...) builder.partialSuccess = ...; return
builder.build()`. The function is correct and the conditional `partial_success` is exactly
spec-conformant (UNSET on full success). A more idiomatic Kotlin form uses `apply`/conditional
builder, but this is purely cosmetic and the current form is arguably clearer. No behavioral concern.

**Fix:** Optional — leave as-is, or `ExportTraceServiceResponse.newBuilder().apply { if (...) ... }.build()`.

### IN-03: `errorMessage` fallback string duplicated across `OtlpDecoder` and `OtlpResponses`

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpResponses.kt:24`
and `backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpDecoder.kt:55`

**Issue:** `OtlpDecoder` always sets a non-null `firstError` when `rejected > 0`, and the
`DecodeResult` invariant (`rejectedSpans > 0 ⇒ errorMessage != null`) is documented. Yet
`OtlpResponses` still defensively falls back to `?: "some spans were rejected"`. The fallback is dead
code given the invariant — harmless, but it means the invariant is enforced by convention in two
places with two different strings. Consider making `errorMessage` non-null when `rejectedSpans > 0`
at the type level, or drop the fallback.

**Fix:** Either trust the invariant (drop the `?:`) or, better, encode it in the type (e.g. a sealed
result so a non-zero rejection always carries the message).

---

_Reviewed: 2026-06-23T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
