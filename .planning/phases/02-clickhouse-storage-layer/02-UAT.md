---
status: testing
phase: 02-clickhouse-storage-layer
source: [02-VERIFICATION.md]
started: 2026-06-22T00:00:00Z
updated: 2026-06-22T00:00:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Storage integration suite on native-Docker CI
expected: All three IT classes (ClickHouseSchemaIT, SpanRepositoryIT, PartCountIT) instantiate and pass on a native-Docker runner — closing the live round-trip evidence for SC1, the EXPLAIN granule-pruning proof for SC2, the payload round-trip for SC3, and the bounded-parts assertion for SC4.
result: issue
reported: "Ran the suite locally against Docker Desktop (native, healthy — NOT the broken Colima/QEMU context the executors used). Containers started fine; all 3 ITs FAILED at schema setup with ClickHouse 'Code: 62 ... Unmatched parentheses (SYNTAX_ERROR)'. The schema never applies."
severity: blocker
root_cause: "MigrationRunner.splitStatements() splits the .sql on ';' BEFORE stripping '--' comments. Header comment line 13 of 001_spans.sql ('-- Pitfall 6: retention is TTL-only; never ALTER UPDATE/DELETE.') contains a ';'. The split cuts the comment; its second half 'never ALTER UPDATE/DELETE.' no longer starts with '--', escapes the comment filter, and is prepended as garbage to the CREATE TABLE statement → ClickHouse syntax error → schema creation fails → every IT fails at setup. The 'Testcontainers can't run under Colima/QEMU' note in all four SUMMARYs was a false-negative that masked this real defect (the executors never ran against healthy Docker)."

## Summary

total: 1
passed: 0
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "Spans persist in ClickHouse via a schema that applies cleanly; STOR-01/02/03 live round-trips (typed columns, MV trace lookup, payloads) and SC4 bounded-parts hold against real ClickHouse."
  status: failed
  reason: "User reported: schema DDL fails to apply against real ClickHouse (Code 62 SYNTAX_ERROR). Confirmed reproduction against Docker Desktop."
  severity: blocker
  test: 1
  root_cause: "MigrationRunner.splitStatements() splits on ';' before stripping '--' comments; a semicolon inside a header comment in 001_spans.sql leaks comment text into the CREATE TABLE statement."
  artifacts:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunner.kt
    - backend/src/main/resources/migrations/001_spans.sql
  missing:
    - "Comment-safe SQL statement splitting (strip line comments before splitting on ';', or split outside comments/string literals)"
    - "An IT or unit test that asserts migrations actually apply against real ClickHouse (the existing 'structural grep' fallback cannot catch runtime SQL-assembly bugs)"
    - "Re-run all three storage ITs green against a real ClickHouse to genuinely close SC1–SC4"
