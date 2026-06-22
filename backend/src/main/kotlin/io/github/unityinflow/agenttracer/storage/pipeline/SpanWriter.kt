package io.github.unityinflow.agenttracer.storage.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.unityinflow.agenttracer.domain.AgentSpan
import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.PayloadRow
import io.github.unityinflow.agenttracer.storage.SpanContext
import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.github.unityinflow.agenttracer.storage.SpanRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The batching write pipeline (D-08): a single producer-facing seam the Phase-3 OTLP receiver feeds
 * via [send], a bounded intake [Channel], and one writer coroutine that flushes by count OR time,
 * retries a failed flush with bounded exponential backoff, applies backpressure (the producer
 * suspends, never drops) when the buffer fills, and flushes the in-flight buffer on shutdown.
 *
 * **Per-request inserts are the cardinal anti-pattern (Pitfall 1 → `TOO_MANY_PARTS`).** ClickHouse
 * only ever sees batched inserts because every span flows through this batcher. The intake carries
 * `(AgentSpan, SpanContext)` pairs ([SpanEnvelope]); the writer maps each pair via
 * [AgentSpanRowMapper.map] (256 KB cap + identity from the context) and calls
 * [SpanRepository.writeBatch].
 *
 * **Backpressure (D-08, T-02-10):** the intake is a BOUNDED `Channel(capacity)` — never
 * `Channel.UNLIMITED`. [send] delegates to `channel.send`, which *suspends* when the buffer is full;
 * that suspension IS the backpressure. The saturated state is exposed as a first-class
 * [BackpressureSignal] the Phase-3 receiver observes (to map to OTLP `RESOURCE_EXHAUSTED`).
 *
 * **No-drop on failure (D-08):** when a flush exhausts its retries the batch is NOT discarded — it
 * is retained and prepended to the next flush, so the buffer keeps filling and backpressure
 * propagates upstream. There is no drop/discard path anywhere.
 *
 * All async is coroutines — `delay` for backoff, never `Thread.sleep`, no raw threads. No `var`
 * (mutable state lives in the [Channel], the [BackpressureSignal], and the local writer-loop state).
 *
 * @param repo the storage seam the flush writes through.
 * @param mapper projects each `(AgentSpan, SpanContext)` pair into a `(SpanRow, PayloadRow)`.
 * @param scope the coroutine scope the single writer is launched on (owns its lifecycle).
 * @param config flush/retry/capacity thresholds (env-overridable per D-discretion).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpanWriter(
    private val repo: SpanRepository,
    private val mapper: AgentSpanRowMapper,
    scope: CoroutineScope,
    private val config: Config = Config(),
) {
    /** Immutable intake record: an [AgentSpan] plus the [SpanContext] envelope identity. */
    data class SpanEnvelope(
        val span: AgentSpan,
        val context: SpanContext,
    )

    /**
     * Pipeline thresholds (D-08 defaults; env-overridable via [fromEnv]).
     *
     * @property capacity bounded intake size — the backpressure boundary (default 50_000).
     * @property batchSize flush-by-count trigger (default 5_000).
     * @property flushInterval flush-by-time trigger (default 2s).
     * @property maxRetries bounded flush-retry attempts before backpressure (default 4).
     * @property baseBackoff initial backoff before exponential growth + jitter (default 100ms).
     */
    data class Config(
        val capacity: Int = 50_000,
        val batchSize: Int = 5_000,
        val flushInterval: Duration = 2.seconds,
        val maxRetries: Int = 4,
        val baseBackoff: Duration = 100.milliseconds,
    ) {
        init {
            require(capacity > 0) { "capacity must be > 0, got $capacity" }
            require(batchSize > 0) { "batchSize must be > 0, got $batchSize" }
            require(batchSize <= capacity) { "batchSize ($batchSize) must be <= capacity ($capacity)" }
            require(maxRetries >= 0) { "maxRetries must be >= 0, got $maxRetries" }
        }

        companion object {
            /** Builds a [Config], overriding any threshold from `AGENT_TRACER_*` env vars. */
            fun fromEnv(env: Map<String, String> = System.getenv()): Config {
                val default = Config()
                return Config(
                    capacity = env["AGENT_TRACER_WRITER_CAPACITY"]?.toIntOrNull() ?: default.capacity,
                    batchSize = env["AGENT_TRACER_WRITER_BATCH_SIZE"]?.toIntOrNull() ?: default.batchSize,
                    flushInterval =
                        env["AGENT_TRACER_WRITER_FLUSH_MS"]?.toLongOrNull()?.milliseconds
                            ?: default.flushInterval,
                    maxRetries = env["AGENT_TRACER_WRITER_MAX_RETRIES"]?.toIntOrNull() ?: default.maxRetries,
                )
            }
        }
    }

    /** Bounded intake — suspend-on-full is the backpressure boundary (never UNLIMITED). */
    private val intake = Channel<SpanEnvelope>(config.capacity)

    private val signal = BackpressureSignal()

    /** First-class saturation signal observed by the Phase-3 receiver. */
    val backpressure: BackpressureSignal get() = signal

    private val writer: Job =
        scope.launch {
            runWriteLoop()
        }

    /**
     * Submits one span + its envelope identity to the pipeline. SUSPENDS when the bounded buffer is
     * full — that suspension is the backpressure (D-08): never drops, never grows unbounded. When a
     * non-suspending offer fails (buffer full), the [BackpressureSignal] flips to saturated before
     * the suspending send parks the producer; it is relieved once the writer drains below capacity.
     */
    suspend fun send(
        span: AgentSpan,
        context: SpanContext,
    ) {
        val envelope = SpanEnvelope(span, context)
        // Fast path: if the buffer has room, enqueue without suspending and stay un-saturated.
        val offered = intake.trySend(envelope).isSuccess
        if (offered) return
        // Buffer is full → mark saturated and SUSPEND on send (backpressure; never drop).
        signal.set(true)
        intake.send(envelope)
    }

    /**
     * Closes the intake and joins the writer so the in-flight buffer is flushed on shutdown — no
     * span is lost on container stop / `docker compose down` (T-02-14). Idempotent.
     */
    suspend fun drainAndClose() {
        intake.close()
        writer.join()
    }

    /**
     * The single writer coroutine: accumulate envelopes until [Config.batchSize] OR
     * [Config.flushInterval] (whichever first), flush, repeat; on channel close flush the tail and
     * exit. A flush that exhausts retries keeps its batch (prepended next round) so nothing drops.
     */
    private suspend fun runWriteLoop() {
        var carried = emptyList<SpanEnvelope>()
        while (true) {
            val batch = ArrayList<SpanEnvelope>(config.batchSize)
            batch.addAll(carried)
            carried = emptyList()

            val closed = accumulate(batch)
            if (batch.isNotEmpty()) {
                val flushed = flush(batch)
                carried = if (flushed) emptyList() else batch
                // Relieve backpressure once a flush succeeds and the buffer has drained below cap.
                if (flushed && intake.isEmpty) signal.set(false)
            }
            if (closed) {
                // Channel closed: keep retrying any carried (un-flushed) batch until it lands, so
                // drainAndClose never loses the in-flight buffer.
                drainCarried(carried)
                return
            }
        }
    }

    /**
     * Fills [batch] up to [Config.batchSize] from the intake, returning when the count threshold is
     * hit, [Config.flushInterval] elapses, or the channel is closed. Returns `true` iff the channel
     * is closed (no more input will arrive).
     */
    private suspend fun accumulate(batch: ArrayList<SpanEnvelope>): Boolean {
        while (batch.size < config.batchSize) {
            val result =
                select {
                    intake.onReceiveCatching { it }
                    onTimeout(config.flushInterval) { null }
                }
            when {
                result == null -> return false // flush-by-time trigger fired
                result.isClosed -> return true // channel closed → flush tail + stop
                else -> {
                    batch.add(result.getOrThrow())
                    if (!intake.isEmpty) signal.set(false)
                }
            }
        }
        return false // flush-by-count trigger fired
    }

    /**
     * Flushes [batch] via [SpanRepository.writeBatch], retrying transient failures with bounded
     * exponential backoff + jitter ([delay], never `Thread.sleep`). Returns `true` on success;
     * `false` after exhausting [Config.maxRetries] — the caller retains the batch (no drop) and
     * backpressure stays asserted.
     */
    private suspend fun flush(batch: List<SpanEnvelope>): Boolean {
        val spanRows = ArrayList<SpanRow>(batch.size)
        val payloadRows = ArrayList<PayloadRow>(batch.size)
        for (envelope in batch) {
            val (spanRow, payloadRow) = mapper.map(envelope.span, envelope.context)
            spanRows.add(spanRow)
            payloadRows.add(payloadRow)
        }

        repeat(config.maxRetries + 1) { attempt ->
            val result = repo.writeBatch(spanRows, payloadRows)
            if (result.isSuccess) {
                logger.debug { "Flushed batch of ${batch.size} spans (attempt ${attempt + 1})" }
                return true
            }
            // Log the failure WITHOUT payload bodies (T-02-13) — only counts + attempt.
            logger.warn { "Flush failed (${batch.size} spans, attempt ${attempt + 1}/${config.maxRetries + 1})" }
            if (attempt < config.maxRetries) {
                delay(backoffFor(attempt))
            }
        }
        // Retries exhausted: assert backpressure and DO NOT drop — the batch is carried forward.
        signal.set(true)
        logger.warn { "Flush retries exhausted (${batch.size} spans) — retaining batch, asserting backpressure" }
        return false
    }

    /** Keeps re-flushing the carried tail on shutdown until it lands (graceful drain, no loss). */
    private suspend fun drainCarried(carried: List<SpanEnvelope>) {
        var pending = carried
        while (pending.isNotEmpty()) {
            if (flush(pending)) {
                pending = emptyList()
            } else {
                // Stop if the scope is being cancelled; otherwise loop (bounded backoff already applied).
                if (!currentlyActive()) return
            }
        }
        signal.set(false)
    }

    private fun currentlyActive(): Boolean = writer.isActive

    /** Exponential backoff (base · 2^attempt) plus up to 50% jitter to avoid thundering retries. */
    private fun backoffFor(attempt: Int): Duration {
        val exp = config.baseBackoff * (1 shl attempt)
        val jitterMillis = (exp.inWholeMilliseconds * Random.nextDouble(0.0, 0.5)).toLong()
        return exp + jitterMillis.milliseconds
    }
}
