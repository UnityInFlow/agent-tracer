package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.SpanRow

/**
 * Pure, query-time projection of a run's spans into a context-saturation timeline (API-04, SC3b/D-04).
 *
 * Saturation is emitter-gated: only spans that actually reported a `contextSaturationPct` contribute
 * a point (Pitfall 6), so a run whose emitter never set it yields an empty timeline. Points are
 * sorted ascending by timestamp regardless of input order. No `!!` — null filtering is done with
 * `mapNotNull` + `?.let`. No SQL, no coroutines.
 */
class SaturationAssembler {
    fun assemble(spans: List<SpanRow>): SaturationResponse {
        val points =
            spans
                .mapNotNull { span ->
                    span.contextSaturationPct?.let { pct ->
                        SaturationPoint(timestamp = span.timestamp, saturationPct = pct)
                    }
                }.sortedBy { it.timestamp }
        return SaturationResponse(points = points)
    }
}
