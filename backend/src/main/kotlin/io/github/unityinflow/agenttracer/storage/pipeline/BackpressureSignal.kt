package io.github.unityinflow.agenttracer.storage.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * First-class backpressure signal for the [SpanWriter] intake (D-08).
 *
 * When the bounded intake buffer is full (or a flush has exhausted its retries and the buffer is
 * filling), the writer marks the pipeline *saturated*. [saturated] is the read-only
 * [StateFlow]<[Boolean]> that the Phase-3 OTLP receiver observes so it can translate sustained
 * saturation into an OTLP `RESOURCE_EXHAUSTED`/`UNAVAILABLE` status and shed load (the translation
 * itself is Phase-3 work, deliberately deferred here). The signal never drops spans — it only tells
 * upstream that `send` is currently (or imminently) suspending.
 *
 * The mutable state is encapsulated behind a [MutableStateFlow] so no plain `var` flag is exposed
 * (CLAUDE.md `var` rule); only the writer mutates it via [set].
 */
class BackpressureSignal {
    private val state = MutableStateFlow(false)

    /** Read-only saturation signal observed by the Phase-3 receiver. `true` ⇒ intake is saturated. */
    val saturated: StateFlow<Boolean> = state.asStateFlow()

    /** Current saturation value (convenience for tests/diagnostics). */
    val isSaturated: Boolean get() = state.value

    /** Writer-internal setter: marks the intake saturated ([value] = `true`) or relieved. */
    internal fun set(value: Boolean) {
        state.value = value
    }
}
