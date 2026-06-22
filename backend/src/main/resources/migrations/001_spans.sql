-- 001_spans.sql — wide MergeTree spans table (RESEARCH Pattern 1).
--
-- Schema decisions pinned here are unrecoverable (a table rewrite to change):
--   D-01: the 5 aggregate-feeding metric columns + PayloadOriginalSizeBytes are the
--         ONLY Nullable columns, and they are deliberately OUT of the ORDER BY so the
--         Nullable null-map never touches the primary index.
--   D-02: every other column uses sentinel defaults ('' / LowCardinality / []),
--         never Nullable.
--   Pitfall 2: TraceId is high-cardinality/random, so it goes LAST in ORDER BY;
--         point lookup is served by the trace_id_ts MV (003) + the bloom filter below.
--   Pitfall 3: agent fields (tokens/cost/saturation/skill/model/...) are typed
--         first-class columns — the Map columns hold ONLY non-agent leftovers.
--   Pitfall 6: retention is TTL-only; never ALTER UPDATE/DELETE.
--
-- Identity columns Timestamp/TraceId/SpanId/ParentSpanId are sourced from SpanContext
-- (the envelope-identity seam, Plan 01) at write time, NOT from AgentSpan.
-- Payload BODIES live in the payloads table (002), never inline here.
CREATE TABLE IF NOT EXISTS spans (
  -- ── OTLP envelope identity (from SpanContext) ──
  Timestamp            DateTime64(9)               CODEC(Delta(8), ZSTD(1)),
  TraceId              String                      CODEC(ZSTD(1)),
  SpanId               String                      CODEC(ZSTD(1)),
  ParentSpanId         String                      CODEC(ZSTD(1)),
  SpanName             LowCardinality(String),
  ServiceName          LowCardinality(String),
  Duration             UInt64                      CODEC(ZSTD(1)),
  StatusCode           LowCardinality(String),
  -- ── promoted typed agent dimensions (D-02: sentinel '' / LowCardinality, NOT Nullable) ──
  AgentName            LowCardinality(String),
  AgentId              String                      CODEC(ZSTD(1)),
  Provider             LowCardinality(String),
  Model                LowCardinality(String),
  SkillName            LowCardinality(String),
  SkillVersion         LowCardinality(String),
  ToolCallId           String                      CODEC(ZSTD(1)),
  ConversationId       String                      CODEC(ZSTD(1)),
  Operation            LowCardinality(String),
  Outcome              LowCardinality(String),
  FinishReasons        Array(String)               DEFAULT [],
  SchemaVersion        LowCardinality(String),
  -- ── aggregate-feeding metric columns (D-01: Nullable; kept OUT of ORDER BY) ──
  InputTokens          Nullable(UInt64),
  OutputTokens         Nullable(UInt64),
  CostUsd              Nullable(Float64),
  ContextSaturationPct Nullable(Float32),
  PlanStep             Nullable(Int64),
  -- ── payload truncation metadata (D-03; payload BODIES live in the payloads table, D-04) ──
  PayloadTruncated     UInt8                       DEFAULT 0,
  PayloadOriginalSizeBytes Nullable(UInt64),
  -- ── non-agent leftovers only (Pitfall 3) ──
  SpanAttributes       Map(LowCardinality(String), String),
  ResourceAttributes   Map(LowCardinality(String), String),
  INDEX idx_trace_id   TraceId TYPE bloom_filter(0.001) GRANULARITY 1
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (ServiceName, SpanName, toUnixTimestamp(Timestamp), TraceId)
TTL toDateTime(Timestamp) + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1;
