-- 003_trace_id_ts_mv.sql — trace-by-id lookup MV (RESEARCH Pattern 2 / STOR-02).
--
-- Maps TraceId → (min(Timestamp), max(Timestamp)). Trace fetch is two steps:
--   1. read the time range for the trace from this MV,
--   2. SELECT ... FROM spans WHERE TraceId=? AND Timestamp BETWEEN ? AND ?
-- so the spans primary key + partition prune granules instead of full-scanning
-- (proven via EXPLAIN indexes=1 in ClickHouseSchemaIT).
--
-- Explicit TARGET table (Decision 2): the engine/ORDER BY/TTL are first-class and
-- testable rather than hidden in a generated .inner. table. SimpleAggregateFunction
-- collapses to one row per TraceId across merges with no -Merge needed at read time.
-- TTL expires lookup rows in lockstep with spans (no orphan index rows).
--
-- ClickHouse forbids a SimpleAggregateFunction column in a key/TTL expression
-- (Code 549 DATA_TYPE_CANNOT_BE_USED_IN_KEY). The TTL therefore keys off a dedicated
-- plain DateTime column TtlTime (= max span Timestamp per trace, fed by the MV via
-- max()), NOT off the aggregate MaxTime. In an AggregatingMergeTree a non-aggregate
-- column takes the last-merged value, which for a per-trace bounded set equals the
-- trace's max timestamp — so retention still expires lookup rows in lockstep with
-- spans, and reads keep using the aggregate MinTime/MaxTime unchanged.
CREATE TABLE IF NOT EXISTS trace_id_ts (
  TraceId  String,
  MinTime  SimpleAggregateFunction(min, DateTime64(9)),
  MaxTime  SimpleAggregateFunction(max, DateTime64(9)),
  TtlTime  DateTime
) ENGINE = AggregatingMergeTree
PARTITION BY tuple()
ORDER BY (TraceId)
TTL TtlTime + INTERVAL 14 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS trace_id_ts_mv TO trace_id_ts AS
SELECT
  TraceId,
  min(Timestamp)             AS MinTime,
  max(Timestamp)             AS MaxTime,
  toDateTime(max(Timestamp)) AS TtlTime
FROM spans
GROUP BY TraceId;
