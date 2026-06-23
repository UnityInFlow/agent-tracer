package io.github.unityinflow.agenttracer.query.dto

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Context-window saturation timeline for a single run (API-04).
 *
 * Only spans that actually reported a `contextSaturationPct` contribute a point — saturation is
 * emitter-gated, so a run whose emitter never set it yields an empty timeline. Points are sorted
 * ascending by timestamp. Immutable `@Serializable` (no `var`).
 */
@Serializable
data class SaturationResponse(
    val points: List<SaturationPoint>,
)

/**
 * One saturation sample.
 *
 * @property timestamp the span timestamp (serialized as epoch-millis on the wire via [InstantSerializer]).
 * @property saturationPct the reported context-window saturation percentage.
 */
@Serializable
data class SaturationPoint(
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    val saturationPct: Float,
)
