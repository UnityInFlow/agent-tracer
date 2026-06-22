-- 004_agent_runs_mv.sql — per-run (= per-trace, Phase-1 D-08) rollup MV (RESEARCH Pattern 3).
--
--   D-06 resolved (Decision 1): AggregatingMergeTree + SimpleAggregateFunction, NOT
--         SummingMergeTree. Summing only collapses additively and cannot express
--         max(saturation) / min(start) / max(end) / anyLast(outcome) — the merge-friendly
--         max-wins semantics Pitfall 5 wants kept reachable for the Phase-4 late-span fix.
--         SimpleAggregateFunction (vs full AggregateFunction) stores the final value, so
--         reads need no finalizeAggregation — only an occasional FINAL / GROUP BY.
--   D-07: anyLast(Outcome) + the SUMs are correct for complete/in-order traces (the
--         Phase-2 scope). Late/out-of-order/never-arriving spans are deferred to Phase 4;
--         the engine choice keeps that fix reachable without a table rewrite.
--
-- Every Phase-4 run-list filter (agent, model, cost range, outcome, date range) becomes a
-- direct typed column. ORDER BY (StartTime, TraceId): run-list is time-ordered, TraceId
-- disambiguates. ifNull(...,0) coerces D-01 Nullable metrics to 0 for the SUM/MAX inputs
-- (aggregates skip nulls, so "unmeasured" contributes nothing).
CREATE TABLE IF NOT EXISTS agent_runs (
  TraceId       String,
  AgentName     SimpleAggregateFunction(anyLast, LowCardinality(String)),
  Model         SimpleAggregateFunction(anyLast, LowCardinality(String)),
  Outcome       SimpleAggregateFunction(anyLast, LowCardinality(String)),
  StartTime     SimpleAggregateFunction(min, DateTime64(9)),
  EndTime       SimpleAggregateFunction(max, DateTime64(9)),
  InputTokens   SimpleAggregateFunction(sum, UInt64),
  OutputTokens  SimpleAggregateFunction(sum, UInt64),
  CostUsd       SimpleAggregateFunction(sum, Float64),
  MaxSaturation SimpleAggregateFunction(max, Float32),
  SpanCount     SimpleAggregateFunction(sum, UInt64)
) ENGINE = AggregatingMergeTree
PARTITION BY toDate(StartTime)
ORDER BY (StartTime, TraceId)
TTL toDateTime(EndTime) + INTERVAL 14 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS agent_runs_mv TO agent_runs AS
SELECT
  TraceId,
  anyLast(AgentName)                              AS AgentName,
  anyLast(Model)                                  AS Model,
  anyLast(Outcome)                                AS Outcome,
  min(Timestamp)                                  AS StartTime,
  max(Timestamp)                                  AS EndTime,
  sum(ifNull(InputTokens, 0))                     AS InputTokens,
  sum(ifNull(OutputTokens, 0))                    AS OutputTokens,
  sum(ifNull(CostUsd, 0))                         AS CostUsd,
  max(ifNull(ContextSaturationPct, toFloat32(0))) AS MaxSaturation,
  toUInt64(count())                               AS SpanCount
FROM spans
GROUP BY TraceId;
