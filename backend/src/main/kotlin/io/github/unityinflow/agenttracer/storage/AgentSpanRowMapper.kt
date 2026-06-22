package io.github.unityinflow.agenttracer.storage

import io.github.unityinflow.agenttracer.domain.AgentSpan
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Pure projection of an `(AgentSpan, SpanContext)` pair into a `(SpanRow, PayloadRow)`, enforcing
 * the Phase-1 D-13 256 KB-per-field payload cap + truncation metadata at write time.
 *
 * Identity ([SpanRow.traceId]/[SpanRow.spanId]/[SpanRow.parentSpanId]/[SpanRow.timestamp] and the
 * matching [PayloadRow] columns) is sourced solely from the [SpanContext] argument — [AgentSpan]
 * carries only OTLP *attributes* and never the envelope identity. The typed agent dimensions follow
 * D-02 (null strings → `''` sentinel) and D-01 (the five metric columns stay nullable — `null` is
 * "unmeasured", never coerced to 0 at the row level; the SUM coercion lives in the `agent_runs` MV).
 * Payload bodies go to [PayloadRow] (D-04).
 *
 * **256 KB cap (D-13):** each payload field's *encoded byte* length is measured (not char count).
 * A field over [PAYLOAD_CAP_BYTES] is truncated on a UTF-8 char boundary (never splitting a
 * multi-byte code point) and flagged `PayloadTruncated=1` with `PayloadOriginalSizeBytes` = the
 * original byte length. If the incoming [AgentSpan] is already emitter-truncated
 * ([AgentSpan.payloadTruncated] = `true` with a recorded [AgentSpan.payloadOriginalSizeBytes]),
 * the LARGER original size and the truncated flag are preserved (no double-truncation, no loss of
 * the emitter's original-size figure).
 *
 * Pure / deterministic: no I/O, no coroutines, no `var`.
 */
class AgentSpanRowMapper {
    /**
     * Projects [span] + [context] into a [SpanRow] (typed dimensions + truncation metadata, no
     * bodies) and a [PayloadRow] (the capped bodies + truncation metadata). Identity on both rows
     * comes from [context].
     */
    fun map(
        span: AgentSpan,
        context: SpanContext,
    ): Pair<SpanRow, PayloadRow> {
        val input = cap(span.inputPayload)
        val output = cap(span.outputPayload)
        val system = cap(span.systemInstructions)

        // A field truncated locally OR an emitter-reported truncation both count as "truncated".
        val emitterTruncated = span.payloadTruncated == true
        val locallyTruncated = input.truncated || output.truncated || system.truncated
        val truncated = emitterTruncated || locallyTruncated

        // Preserve the LARGER original byte size: the emitter's recorded figure (when present) vs.
        // the largest field we truncated here. Never lose the emitter's original size.
        val localOriginalSize =
            listOf(input, output, system)
                .filter { it.truncated }
                .maxOfOrNull { it.originalSizeBytes }
        val originalSizeBytes =
            when {
                span.payloadOriginalSizeBytes != null && localOriginalSize != null ->
                    maxOf(span.payloadOriginalSizeBytes, localOriginalSize)
                span.payloadOriginalSizeBytes != null -> span.payloadOriginalSizeBytes
                else -> localOriginalSize
            }

        val spanRow =
            SpanRow.from(span, context).apply {
                payloadTruncated = truncated
                payloadOriginalSizeBytes = originalSizeBytes
            }

        val payloadRow =
            PayloadRow().apply {
                traceId = context.traceId
                spanId = context.spanId
                timestamp = context.timestamp
                inputPayload = input.body
                outputPayload = output.body
                systemInstructions = system.body
                payloadTruncated = truncated
                payloadOriginalSizeBytes = originalSizeBytes
            }

        return spanRow to payloadRow
    }

    /** Result of applying the byte cap to a single payload field. */
    private data class Capped(
        val body: String,
        val truncated: Boolean,
        val originalSizeBytes: Long,
    )

    /**
     * Applies the [PAYLOAD_CAP_BYTES] byte cap to [field]. A `null` field becomes an empty,
     * non-truncated body (D-02 sentinel for the non-Nullable payload columns). A field at/under the
     * cap is returned verbatim; an oversized field is truncated on a UTF-8 char boundary.
     */
    private fun cap(field: String?): Capped {
        if (field == null) return Capped(body = "", truncated = false, originalSizeBytes = 0L)
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= PAYLOAD_CAP_BYTES) {
            return Capped(body = field, truncated = false, originalSizeBytes = bytes.size.toLong())
        }
        return Capped(
            body = truncateToBytes(bytes, PAYLOAD_CAP_BYTES),
            truncated = true,
            originalSizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * Decodes the first [limit] bytes of [bytes] as UTF-8, dropping any trailing partial multi-byte
     * code point (CodingErrorAction.IGNORE) so the result is valid UTF-8 ≤ [limit] bytes — never a
     * mojibake replacement char from a mid-codepoint split.
     */
    private fun truncateToBytes(
        bytes: ByteArray,
        limit: Int,
    ): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
        return decoder.decode(ByteBuffer.wrap(bytes, 0, limit)).toString()
    }

    companion object {
        /** Phase-1 D-13 per-field payload cap: 256 KB, measured in encoded UTF-8 bytes. */
        const val PAYLOAD_CAP_BYTES: Int = 256 * 1024
    }
}
