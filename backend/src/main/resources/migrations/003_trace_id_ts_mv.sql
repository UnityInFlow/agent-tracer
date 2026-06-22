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
CREATE TABLE IF NOT EXISTS trace_id_ts (
  TraceId  String,
  MinTime  SimpleAggregateFunction(min, DateTime64(9)),
  MaxTime  SimpleAggregateFunction(max, DateTime64(9))
) ENGINE = AggregatingMergeTree
PARTITION BY tuple()
ORDER BY (TraceId)
TTL toDateTime(MaxTime) + INTERVAL 14 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS trace_id_ts_mv TO trace_id_ts AS
SELECT
  TraceId,
  min(Timestamp) AS MinTime,
  max(Timestamp) AS MaxTime
FROM spans
GROUP BY TraceId;
