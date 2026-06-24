---
phase: 04-query-api-demo-emitter-compose-stack
reviewed: 2026-06-24T00:00:00Z
depth: standard
files_reviewed: 27
files_reviewed_list:
  - gradle/libs.versions.toml
  - settings.gradle.kts
  - backend/build.gradle.kts
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepository.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/DagAssembler.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/SaturationAssembler.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListMapper.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/QueryApi.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/DagResponse.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/SaturationResponse.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListResponse.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/InstantSerializer.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt
  - demo-emitter/build.gradle.kts
  - demo-emitter/src/main/kotlin/io/github/unityinflow/agenttracer/demo/DemoEmitter.kt
  - demo-emitter/src/main/kotlin/io/github/unityinflow/agenttracer/demo/RunScenario.kt
  - demo-emitter/src/main/kotlin/io/github/unityinflow/agenttracer/demo/SeededRunFactory.kt
  - docker/compose.yaml
  - docker/tracer.Dockerfile
  - docker/emitter.Dockerfile
  - docker/healthcheck.sh
  - scripts/compose-smoke.sh
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/DagAssemblerTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/SaturationAssemblerTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/RunListMapperTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/QueryApiTest.kt
  - demo-emitter/src/test/kotlin/io/github/unityinflow/agenttracer/demo/SeededRunFactoryTest.kt
findings:
  critical: 0
  warning: 7
  info: 6
  total: 13
status: issues_found
---

# Phase 4: Code Review Report

**Reviewed:** 2026-06-24T00:00:00Z
**Depth:** standard
**Files Reviewed:** 27
**Status:** issues_found

## Summary

Reviewed the Phase-4 query/transform layer, the new paginated `listRuns` SQL surface, the
two-server (OTLP protobuf + isolated JSON :8080) lifecycle wiring, the demo-emitter OTLP client,
and the docker/compose stack. The review focused on the four risk areas called out in the config:
SQL injection in `listRuns`, input validation in `RunListMapper`, error-leak surface in `QueryApi`,
and two-server ContentNegotiation isolation.

**Headline assessment:**
- **SQL injection surface is clean.** Every user value in `listRuns` (and the other read methods)
  is bound as a `{name:Type}` placeholder; predicate fragments and `LIMIT/OFFSET` are constant
  strings. No string interpolation of user input into SQL was found. The only interpolated tokens
  are compile-time-constant table-name `const val`s.
- **Two-server isolation holds.** `ContentNegotiation { json() }` is installed only on the
  `QUERY_PORT` (`8080`) `embeddedServer`; the OTLP HTTP `Application` (`installOtlp`) never installs
  it, so the protobuf receiver is not intercepted.
- **Error hygiene holds.** All three query routes return short generic bodies; no exception/SQL/DB
  internals are echoed.

No BLOCKER-class defects were proven. However, there are several correctness and robustness gaps —
most notably a timestamp-precision bug in the DAG assembler, an unvalidated cost-range ordering, a
non-isolated coroutine-cancellation in `listRuns`, an unbounded `limit` fallback path, and a few
security-relevant hardening gaps in the docker stack — that should be addressed.

## Warnings

### WR-01: DAG node `startTimeUnixNanos` is computed from millis, silently truncating to ms precision

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/DagAssembler.kt:63`
**Issue:** The field is named `startTimeUnixNanos` and the frontend/timeline consumes it as
nanosecond-resolution, but it is built as `span.timestamp.toEpochMilli() * 1_000_000`.
`toEpochMilli()` discards everything below a millisecond, then the `* 1_000_000` re-pads with three
zero digits. The spans are stored as `DateTime64(9)` (nanosecond precision) and OTel span start
times are nanosecond-resolution, so any sub-millisecond ordering / duration alignment on the DAG
timeline is destroyed. For closely-spaced spans (sub-ms) this produces ties or out-of-order
rendering. (No `Long` overflow for current dates — 2026 epoch-ms × 1e6 ≈ 1.8e18 < `Long.MAX`.)
**Fix:** Derive nanos directly from the `Instant` without going through millis:
```kotlin
startTimeUnixNanos = span.timestamp.epochSecond * 1_000_000_000L + span.timestamp.nano
```

### WR-02: `RunListMapper` never validates `costMin <= costMax` or `dateFrom <= dateTo`

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListMapper.kt:28-37`
**Issue:** The mapper validates each bound in isolation (finite double, ISO instant) but never
checks that the lower bound does not exceed the upper bound. A request like
`?costMin=5&costMax=1` (or `dateFrom` after `dateTo`) produces a `RunFilter` whose `WHERE
CostUsd >= 5 AND CostUsd <= 1` can never match, returning an always-empty page. This is a silent
"correct-looking but wrong" result rather than a clear 400 — exactly the kind of edge case the
validation seam (T-04-03) is supposed to reject.
**Fix:** After building the filter, reject inverted ranges:
```kotlin
require(filter.costMin == null || filter.costMax == null || filter.costMin <= filter.costMax) {
    "costMin must be <= costMax"
}
require(filter.dateFrom == null || filter.dateTo == null || !filter.dateFrom.isAfter(filter.dateTo)) {
    "dateFrom must be <= dateTo"
}
```
(The route already maps `IllegalArgumentException` → 400.)

### WR-03: `listRuns` swallows `CancellationException` into `Result.failure`, breaking structured-concurrency cancellation

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt:73-198`
**Issue:** All four suspend methods wrap their bodies (which `await()` on a `CompletableFuture`) in
`runCatching { ... }`. `runCatching` catches `Throwable`, including `kotlinx.coroutines`
`CancellationException`. When the calling coroutine is cancelled (e.g. the Ktor request scope is
torn down, or the `:8080` server is stopped mid-query during the `shutdown()` drain), the
`CancellationException` is captured as a `Result.failure` and the route turns it into a `500 Query
failed` instead of letting cancellation propagate. This both masks cancellation and can emit a
response on an already-cancelled call.
**Fix:** Rethrow cancellation before treating a failure as a DB error, e.g. catch explicitly:
```kotlin
}.onFailure { if (it is CancellationException) throw it; logger.warn(it) { ... } }
```
or replace `runCatching` with a `try/catch (e: Exception)` that rethrows `CancellationException`.
Apply to all four methods.

### WR-04: Non-numeric `limit` silently falls back to the default instead of returning 400, masking client error

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListMapper.kt:40-49`
**Issue:** `limitOf`/`offsetOf` use `?.toIntOrNull() ?: default`. A garbage value like
`?limit=abc` or `?limit=99999999999999` (overflows `Int`) silently becomes `DEFAULT_LIMIT` / `0`
rather than a 400. This is inconsistent with the cost/date params, which DO reject malformed input.
More importantly, a value like `?limit=-1` is silently clamped to 1 and `?offset=-1` to 0 — a
caller paginating with a computed-but-buggy offset gets wrong-page results with no signal. The
silent-default behavior is documented as intentional (DoS mitigation), so this is a WARNING, but the
asymmetry (cost rejects, limit swallows) is a latent correctness/observability trap.
**Fix:** At minimum, reject a *present-but-unparseable* `limit`/`offset` with `IllegalArgumentException`
(→ 400), reserving the default only for the *absent* case:
```kotlin
fun limitOf(params: Map<String, String?>): Int {
    val raw = params.nonBlank("limit") ?: return SpanRepository.DEFAULT_LIMIT
    val n = raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit: '$raw'")
    return n.coerceIn(1, MAX_LIMIT)
}
```

### WR-05: `RunListResponse.limit/offset` echo a default in `toResponse` that can disagree with what was actually applied

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListMapper.kt:52-61`
**Issue:** `toResponse` has default params `limit = DEFAULT_LIMIT, offset = 0`. The route happens to
always pass the real computed values, so the live path is fine — but any caller (or future caller)
that invokes `toResponse(rows)` without the paging args gets a response whose echoed `limit`/`offset`
silently differ from what the repository actually applied. The defaults make a wrong-but-compiling
call possible. The `RunListResponse` contract says the echoed values are "the effective paging
applied"; defaulting here can violate that invariant.
**Fix:** Make `limit`/`offset` required (drop the defaults) so the effective values must be threaded
through explicitly, or have `toResponse` take the already-computed `(limit, offset)` as a small value
object.

### WR-06: `healthcheck.sh` overwrites the `PATH` environment concept with a `PATH_` var but the bash request can hang with no read timeout

**File:** `docker/healthcheck.sh:13-24`
**Issue:** The probe opens `/dev/tcp` and does a blocking `read -r status_line <&3` with no timeout.
If the tracer accepts the TCP connection but stalls before sending a status line (e.g. thread-pool
saturation, half-open socket), the `read` blocks indefinitely. Docker's `HEALTHCHECK --timeout=3s`
will eventually `SIGKILL` the probe, so the container is not permanently wedged — but the probe
itself has no internal bound and relies entirely on the orchestrator's kill. Under `compose` the
same `timeout: 3s` applies, so impact is contained; still, a self-bounding probe is more robust and
avoids leaking a stuck bash + open fd between health intervals.
**Fix:** Add a read timeout: `read -t 2 -r status_line <&3 || exit 1` (and consider
`exec 3<>/dev/tcp/...` failure already handled). Bash's `read -t` bounds the wait without an extra
binary.

### WR-07: ClickHouse `default` user runs with empty-password auth enabled in the shipped compose stack

**File:** `docker/compose.yaml:19-37`
**Issue:** `CLICKHOUSE_SKIP_USER_SETUP: "1"` deliberately keeps the permissive `default` user with
empty-password auth so the tracer's empty-password `ClickHouseConfig.fromEnv` default works. This is
intentional and secret-free (no credential literal is committed — good, and it satisfies the
"no password literal in docker/" rule). However, the compose file is the all-in-one stack users will
copy as a starting point, and it exposes a passwordless ClickHouse. ClickHouse's HTTP port `8123` is
not published to the host here (only `4317/4318/8080` on the tracer are), so it is reachable only on
the compose network — which bounds the exposure — but a passwordless DB in a "copy-me" reference
stack is a footgun if someone later adds `ports: 8123:8123`. This is a hardening WARNING, not a
committed-secret BLOCKER.
**Fix:** Document inline that this is dev-only and that production must set `CLICKHOUSE_PASSWORD` via
env, and/or ship a commented `# ports: ["8123:8123"]` with a security note so nobody un-comments it
without setting a password. Confirm `8123` is never published in this file.

## Info

### IN-01: DAG cost roll-up double-counts a node's own cost inside a true cycle

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/DagAssembler.kt:119-127`
**Issue:** The cycle guard prevents infinite recursion (good, and the test only asserts termination),
but for a real cycle a→b→a the returned `subtreeCostUsd` is arithmetically wrong: the cycle-broken
branch returns `ownCost[id]` again, so an ancestor's own cost is counted twice. Cycles are malformed
input (they should never occur from a valid trace), so this is informational, but the roll-up is
silently incorrect rather than, say, marking the node degraded.
**Fix:** When the cycle guard trips, return `0.0` (treat the back-edge as contributing nothing) so
the cost is not double-counted, or surface a `degraded`/`cyclic` flag on affected nodes.

### IN-02: `readMapColumn` stringifies map values, lossily flattening non-string attribute values

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt:236-242`
**Issue:** `map.entries.associate { (k, v) -> k.toString() to v.toString() }` assumes every value
`.toString()`s meaningfully. For the declared `Map(String,String)` columns this is fine, but the
`toString()` fallback would silently mangle any non-string surface if the schema ever changes. Low
risk given the current DDL; flagged for the maintainer.
**Fix:** Since the column is `Map(String,String)`, cast values as `String` directly (or assert),
rather than `.toString()`, so a schema drift fails loudly instead of producing `"[B@1a2b3c"`-style
garbage.

### IN-03: `differentSeedsDiverge` test is probabilistic and could flake

**File:** `demo-emitter/src/test/kotlin/io/github/unityinflow/agenttracer/demo/SeededRunFactoryTest.kt:67-73`
**Issue:** `differentSeedsDiverge` asserts `(a == b) shouldBe false` for two fixed seeds. With these
two specific seeds (1 and 999) it will pass deterministically, but the test's *intent* ("overwhelmingly
likely to differ") is documented as probabilistic. Since the seeds are fixed, it is actually
deterministic — but the comment invites someone to parameterize it with random seeds later, which
would make it flaky. Affects test reliability only.
**Fix:** Either keep fixed seeds and update the comment to "deterministic for these seeds," or assert
on a specific known-divergent attribute rather than whole-projection inequality.

### IN-04: `SeededRunFactoryTest` relies on END-order equality, which is documented-but-fragile

**File:** `demo-emitter/src/test/kotlin/io/github/unityinflow/agenttracer/demo/SeededRunFactoryTest.kt:57-65`
**Issue:** `sameSeedProducesIdenticalSpanTrees` compares `project(second) shouldBe project(first)` as
an *ordered* list, depending on `SimpleSpanProcessor` exporting in END order identically for two
runs. The factory emits synchronously in post-order so this holds today, but it is an order-sensitive
assertion on an exporter-ordering implementation detail. If the emit ever becomes concurrent
(coroutines), this silently breaks.
**Fix:** Compare as multisets / sorted-by-name+attrs to decouple from exporter ordering, or assert
the determinism on the scenario tree (`nextScenario`) directly rather than through the exporter.

### IN-05: `QUERY_PORT` is hardcoded with no env override, unlike the OTLP ports

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/app/TracerApplication.kt:76`
**Issue:** `private const val QUERY_PORT = 8080`. The OTLP ports come from `ReceiverConfig.fromEnv`
(configurable), but the query/health port is a hardcoded constant. In Compose this is fine, but it
prevents running two instances or relocating the port without a recompile, and is inconsistent with
the env-driven config discipline used elsewhere (`ClickHouseConfig.fromEnv`, `ReceiverConfig.fromEnv`,
`DemoConfig.fromEnv`).
**Fix:** Source the query port from `ReceiverConfig`/env (e.g. `QUERY_PORT` env var) with `8080` as
the default, mirroring the existing config pattern.

### IN-06: `getList("FinishReasons")` result type is unchecked and may NPE/ClassCast on an unexpected column shape

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt:221`
**Issue:** `finishReasons = getList("FinishReasons")` assigns the reader's list result directly. Unlike
the `readValue<Any?>(...)?.let { ... }` pattern used for the nullable numeric columns, there is no
null/shape guard here. If the column is ever NULL or returns an unexpected element type, this can
throw inside the row mapper (which is then caught by `runCatching` and surfaced as a generic read
failure). Low risk given the non-nullable `Array(String)` DDL, but the inconsistency with the
defensive numeric handling a few lines below is worth noting.
**Fix:** Mirror the nullable-safe pattern (`readValue<Any?>("FinishReasons")?.let { ... } ?: emptyList()`)
or document why `getList` is guaranteed non-null here.

---

_Reviewed: 2026-06-24T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
