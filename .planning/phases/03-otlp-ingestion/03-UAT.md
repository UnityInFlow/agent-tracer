---
status: testing
phase: 03-otlp-ingestion
source: [03-VERIFICATION.md]
started: 2026-06-23T00:00:00Z
updated: 2026-06-23T00:00:00Z
---

## Current Test

number: 1
name: Run OtlpHttpReceiverIT on native Docker/CI and observe both tests GREEN, 0 skipped
expected: |
  A real OtlpHttpSpanExporter posting to :4318 lands the span in ClickHouse, retrievable by
  repo.getTraceById(sameHexId) (SC1, D-07 round-trip). A stock non-agent span lands through the
  same store-all path with agent columns null/default (D-03).
awaiting: user response

## Tests

### 1. OtlpHttpReceiverIT green on native Docker (SC1, D-07, D-03)
expected: `./gradlew :backend:test --tests "*OtlpHttpReceiverIT"` — both tests GREEN, 0 skipped. A real OtlpHttpSpanExporter posting to :4318 lands the span in ClickHouse, retrievable by repo.getTraceById(sameHexId); a stock non-agent span lands via the store-all path (agent columns null/default).
result: [pending]

### 2. OtlpGrpcReceiverIT green on native Docker (SC2, >4 MB)
expected: `./gradlew :backend:test --tests "*OtlpGrpcReceiverIT"` — GREEN, 0 skipped. A real OtlpGrpcSpanExporter sending a 6 MB (>4 MB) prompt-heavy span to :4317 lands it in ClickHouse, truncated-not-rejected (payloadOriginalSizeBytes == 6 MiB), proving the raised maxInboundMessageSize.
result: [pending]

### 3. TracerApplicationIT green on native Docker (D-04 boot order + drain-no-loss)
expected: `./gradlew :backend:test --tests "*TracerApplicationIT"` — both tests GREEN, 0 skipped. schemaPresentAfterBoot proves migrate-before-serve; drainOnShutdownLosesNoInFlightSpan drives N spans through the started HTTP receiver, runs shutdown(), and asserts every span persisted (CR-01/CR-02 quiescence-barrier fix).
result: [pending]

### 4. Full suite GREEN, 0 skipped on native Docker (release gate)
expected: `./gradlew :backend:test` — all unit suites (29 ingest tests) + the 3 Docker-gated ITs pass, 0 skipped, before flipping INGEST-01/02/03 to complete in REQUIREMENTS.md. (Also clears the 3 pre-existing Phase-2 storage ITs — ClickHouseSchemaIT/SpanRepositoryIT/PartCountIT — that share the same ryuk-gated limit.)
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps
