// Wave 0 stub — fails until Plan 02 creates SaturationAssembler / SaturationResponse / SaturationPoint.
package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.SpanRow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Wave-0 RED scaffold for the context-saturation timeline assembler (Plan 02 produces the symbols).
 *
 * Pins SC3b: given spans with mixed null / non-null `contextSaturationPct`, the produced timeline
 * contains ONLY the non-null points, ordered by `timestamp` ascending (regardless of input order).
 * Saturation is emitter-gated (Pitfall 6) — spans that never reported a value contribute nothing.
 *
 * References `SaturationAssembler`, `SaturationResponse`, `SaturationPoint` — created by Plan 02, so
 * this file does not compile until then (intentional RED; NOT @Disabled). Block-body `@Test`
 * methods returning Unit (Pitfall 5).
 */
class SaturationAssemblerTest {
    private fun span(
        spanId: String,
        saturationPct: Float?,
        offsetMillis: Long,
    ): SpanRow =
        SpanRow().apply {
            this.spanId = spanId
            this.contextSaturationPct = saturationPct
            this.traceId = "sat-test-trace-00000000000000001"
            this.timestamp = BASE_TIME.plusMillis(offsetMillis)
        }

    @Test
    fun timelineContainsOnlyNonNullSaturationPointsSortedByTimestamp() {
        val spans =
            listOf(
                span(spanId = "1111111111111111", saturationPct = 40.0f, offsetMillis = 30),
                span(spanId = "2222222222222222", saturationPct = null, offsetMillis = 10),
                span(spanId = "3333333333333333", saturationPct = 12.5f, offsetMillis = 0),
            )
        val response: SaturationResponse = SaturationAssembler().assemble(spans)

        // Only the two non-null points survive, sorted by timestamp ascending.
        response.points shouldHaveSize 2
        response.points.map(SaturationPoint::saturationPct) shouldBe listOf(12.5f, 40.0f)
        response.points.map { it.timestamp } shouldBe listOf(BASE_TIME, BASE_TIME.plusMillis(30))
    }

    @Test
    fun allNullSaturationYieldsEmptyTimeline() {
        val spans =
            listOf(
                span(spanId = "4444444444444444", saturationPct = null, offsetMillis = 0),
                span(spanId = "5555555555555555", saturationPct = null, offsetMillis = 5),
            )
        val response: SaturationResponse = SaturationAssembler().assemble(spans)

        response.points shouldHaveSize 0
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-23T12:00:00Z")
    }
}
