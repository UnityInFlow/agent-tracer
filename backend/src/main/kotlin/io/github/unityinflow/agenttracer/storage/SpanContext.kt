package io.github.unityinflow.agenttracer.storage

import java.time.Instant

/**
 * OTLP-envelope identity for a single span — the four fields [AgentSpan] deliberately omits.
 *
 * [io.github.unityinflow.agenttracer.domain.AgentSpan] is a typed view of OTLP *attributes*
 * only; it carries no `traceId`/`spanId`/`parentSpanId`/`timestamp`. But the Phase-2 ClickHouse
 * schema makes that identity structurally mandatory: `Timestamp`/`TraceId` in the spans
 * `ORDER BY`, `toDate(Timestamp)` partition, `SpanId`/`ParentSpanId` columns, the
 * `(TraceId, SpanId)` payload key, and `GROUP BY TraceId` in both materialized views.
 *
 * [SpanContext] is the seam that supplies that identity. In Phase 2 it is minted by test
 * fixtures; in Phase 3 the OTLP receiver populates the very same seam from the real OTLP
 * envelope. It is the sole source of the mandatory `TraceId`/`SpanId`/`ParentSpanId`/`Timestamp`
 * schema-identity columns and never duplicates any [AgentSpan] attribute field.
 *
 * @property traceId the OTLP trace id (hex string); groups all spans of one agent run.
 * @property spanId the OTLP span id (hex string); unique within a trace.
 * @property parentSpanId the OTLP parent span id (hex string); empty for a root span.
 * @property timestamp the span start time (the `Timestamp` order/partition key).
 */
data class SpanContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val timestamp: Instant,
)
