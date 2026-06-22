-- 002_payloads.sql — separate point-lookup payloads table (RESEARCH Pattern 1).
--
--   D-04: input/output/system payload BODIES live here, keyed (TraceId, SpanId),
--         NOT inline in spans — keeps the hot analytical spans path lean. STOR-03
--         "retrieved individually" → ORDER BY (TraceId, SpanId) point lookup.
--   D-05: same 14-day TTL window as spans so blobs expire in lockstep — no orphans.
--   Phase-1 D-13: payload bodies are ≤256 KB per field (cap enforced at write time);
--         ZSTD(3) since text compresses well. Truncation metadata mirrors spans.
-- Timestamp is carried solely to align the TTL with spans.
CREATE TABLE IF NOT EXISTS payloads (
  TraceId                  String        CODEC(ZSTD(1)),
  SpanId                   String        CODEC(ZSTD(1)),
  Timestamp                DateTime64(9) CODEC(Delta(8), ZSTD(1)),
  InputPayload             String        CODEC(ZSTD(3)),
  OutputPayload            String        CODEC(ZSTD(3)),
  SystemInstructions       String        CODEC(ZSTD(3)),
  PayloadTruncated         UInt8         DEFAULT 0,
  PayloadOriginalSizeBytes Nullable(UInt64)
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (TraceId, SpanId)
TTL toDateTime(Timestamp) + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1;
