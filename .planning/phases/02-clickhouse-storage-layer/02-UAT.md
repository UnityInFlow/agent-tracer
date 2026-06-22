---
status: testing
phase: 02-clickhouse-storage-layer
source: [02-VERIFICATION.md]
started: 2026-06-22T00:00:00Z
updated: 2026-06-22T00:00:00Z
---

## Current Test

number: 1
name: Storage integration suite on native-Docker CI
expected: |
  On `arc-runner-unityinflow` (native Docker), run:
  `./gradlew :backend:test --tests "*ClickHouseSchemaIT" --tests "*SpanRepositoryIT" --tests "*PartCountIT"`
  All three IT classes instantiate (container starts) and pass:
  - ClickHouseSchemaIT: all 4 objects + MVs + schema_migrations created; second runMigrations() is a no-op (idempotent); EXPLAIN indexes=1 shows granule pruning (SC2); trace_id_ts returns correct (Min,Max).
  - SpanRepositoryIT: typed agent columns round-trip directly (SC1); getTraceById via MV path; payload round-trip incl. truncated + absent→null (SC3); agent_runs rollup + dedup correct; filtered listRuns honors agent/model/cost/outcome/date.
  - PartCountIT: `SELECT count() FROM system.parts WHERE table='spans' AND active=1` stays < 300 after 30k sustained spans across many partitions (SC4).
awaiting: user response

## Tests

### 1. Storage integration suite on native-Docker CI
expected: All three IT classes (ClickHouseSchemaIT, SpanRepositoryIT, PartCountIT) instantiate and pass on a native-Docker runner — closing the live round-trip evidence for SC1, the EXPLAIN granule-pruning proof for SC2, the payload round-trip for SC3, and the bounded-parts assertion for SC4.
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
