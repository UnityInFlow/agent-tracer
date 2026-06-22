package io.github.unityinflow.agenttracer.storage.pipeline

import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.PayloadRow
import io.github.unityinflow.agenttracer.storage.SpanContext
import io.github.unityinflow.agenttracer.storage.SpanFixtures
import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.github.unityinflow.agenttracer.storage.SpanRow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM unit test for the [SpanWriter] D-08 semantics (no Docker). Uses kotlinx-coroutines-test
 * `runTest` + virtual time so flush-by-time is asserted WITHOUT real sleeps, and a MockK fake
 * [SpanRepository] so [SpanWriter.send] → flush → `writeBatch` is observed directly.
 *
 * Proves: (1) flush triggers at batchSize; (2) flush triggers at flushInterval for a partial batch;
 * (3) a failed flush retries up to maxRetries and the batch is NOT dropped; (4) backpressure — a
 * full bounded channel suspends a further send and flips the saturated signal; (5) drainAndClose
 * flushes the in-flight tail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpanWriterTest {
    private fun ctx(spanId: String): SpanContext = SpanContext("trace-1", spanId, "", Instant.parse("2026-06-22T10:00:00Z"))

    private val mapper = AgentSpanRowMapper()

    private fun aSpan(spanId: String) = SpanFixtures.full(spanId = spanId).first to ctx(spanId)

    @Test
    fun flushTriggersAtBatchSize() =
        runTest {
            val repo = mockk<SpanRepository>()
            val seen = AtomicInteger(0)
            val spanSlot = slot<List<SpanRow>>()
            coEvery { repo.writeBatch(capture(spanSlot), any()) } answers
                {
                    seen.addAndGet(spanSlot.captured.size)
                    Result.success(Unit)
                }

            val writer =
                SpanWriter(
                    repo = repo,
                    mapper = mapper,
                    scope = backgroundScope,
                    config = SpanWriter.Config(capacity = 100, batchSize = 5, maxRetries = 1),
                )

            repeat(5) { writer.send(aSpan("%016x".format(it.toLong())).first, ctx("%016x".format(it.toLong()))) }
            runCurrent()

            // A full batch of 5 must flush on the count trigger (no time advance needed).
            coVerify(atLeast = 1) { repo.writeBatch(any(), any()) }
            seen.get() shouldBeGreaterThanOrEqual 5
        }

    @Test
    fun flushTriggersAtFlushIntervalForPartialBatch() =
        runTest {
            val repo = mockk<SpanRepository>()
            val calls = AtomicInteger(0)
            coEvery { repo.writeBatch(any(), any()) } answers
                {
                    calls.incrementAndGet()
                    Result.success(Unit)
                }

            val writer =
                SpanWriter(
                    repo = repo,
                    mapper = mapper,
                    scope = backgroundScope,
                    config =
                        SpanWriter.Config(
                            capacity = 10_000,
                            batchSize = 5_000,
                            flushInterval = kotlin.time.Duration.parse("2s"),
                            maxRetries = 1,
                        ),
                )

            // Only 3 spans — well below batchSize; the only way they flush is the TIME trigger.
            repeat(3) { writer.send(aSpan("%016x".format(it.toLong())).first, ctx("%016x".format(it.toLong()))) }
            runCurrent()
            calls.get() shouldBe 0 // not yet — interval has not elapsed (virtual time)

            advanceTimeBy(2_500) // cross the 2s flush interval in virtual time (no real wait)
            runCurrent()

            coVerify(atLeast = 1) { repo.writeBatch(any(), any()) }
            calls.get() shouldBeGreaterThanOrEqual 1
        }

    @Test
    fun failedFlushRetriesUpToMaxRetriesAndDoesNotDrop() =
        runTest {
            val repo = mockk<SpanRepository>()
            val attempts = AtomicInteger(0)
            // Fail the first 2 attempts, then succeed → proves retry AND that the batch is not dropped.
            coEvery { repo.writeBatch(any(), any()) } answers
                {
                    val n = attempts.incrementAndGet()
                    if (n < 3) Result.failure(RuntimeException("transient ClickHouse failure")) else Result.success(Unit)
                }

            val writer =
                SpanWriter(
                    repo = repo,
                    mapper = mapper,
                    scope = backgroundScope,
                    config = SpanWriter.Config(capacity = 100, batchSize = 2, maxRetries = 4),
                )

            repeat(2) { writer.send(aSpan("%016x".format(it.toLong())).first, ctx("%016x".format(it.toLong()))) }
            // Advance virtual time generously to let the bounded backoff delays elapse.
            advanceTimeBy(5_000)
            runCurrent()

            // Retried until success (3 calls); the batch was never silently discarded.
            attempts.get() shouldBeGreaterThanOrEqual 3
            coVerify(atLeast = 3) { repo.writeBatch(any(), any()) }
        }

    @Test
    fun sendSuspendsWhenChannelFullAndSaturatedTurnsTrue() =
        runTest {
            val repo = mockk<SpanRepository>()
            // Block the writer forever on the very first flush so the buffer cannot drain.
            coEvery { repo.writeBatch(any(), any()) } coAnswers
                {
                    delay(Long.MAX_VALUE / 2)
                    Result.success(Unit)
                }

            val writer =
                SpanWriter(
                    repo = repo,
                    mapper = mapper,
                    scope = backgroundScope,
                    // capacity 1, batchSize 1: the writer pulls one, blocks flushing it forever;
                    // the channel then fills with one buffered item; the NEXT send must suspend.
                    config = SpanWriter.Config(capacity = 1, batchSize = 1, maxRetries = 0),
                )

            // 1st send: consumed by writer (then blocks on the eternal flush).
            writer.send(aSpan("0000000000000001").first, ctx("0000000000000001"))
            runCurrent()
            // 2nd send: fills the capacity-1 buffer.
            writer.send(aSpan("0000000000000002").first, ctx("0000000000000002"))
            runCurrent()

            // 3rd send MUST suspend (buffer full) — it never completes within the timeout.
            val thirdCompleted = AtomicInteger(0)
            val job =
                launch {
                    writer.send(aSpan("0000000000000003").first, ctx("0000000000000003"))
                    thirdCompleted.incrementAndGet()
                }
            runCurrent()

            thirdCompleted.get() shouldBe 0 // suspended, did not complete
            // Saturated flips true once the buffer is full and a send is parked.
            withTimeoutOrNull(1_000) { writer.backpressure.saturated.first { it } } shouldBe true
            job.cancel()
        }

    @Test
    fun drainAndCloseFlushesTheTail() =
        runTest {
            val repo = mockk<SpanRepository>()
            val totalSpans = AtomicInteger(0)
            val spanSlot = slot<List<SpanRow>>()
            val payloadSlot = slot<List<PayloadRow>>()
            coEvery { repo.writeBatch(capture(spanSlot), capture(payloadSlot)) } answers
                {
                    totalSpans.addAndGet(spanSlot.captured.size)
                    Result.success(Unit)
                }

            val writer =
                SpanWriter(
                    repo = repo,
                    mapper = mapper,
                    scope = backgroundScope,
                    // batchSize larger than the input so ONLY drainAndClose can flush the tail.
                    config = SpanWriter.Config(capacity = 10_000, batchSize = 5_000, maxRetries = 1),
                )

            repeat(3) { writer.send(aSpan("%016x".format(it.toLong())).first, ctx("%016x".format(it.toLong()))) }
            runCurrent()
            // Tail still buffered (below batchSize, interval not advanced).
            writer.drainAndClose()
            runCurrent()

            // The 3 buffered spans were flushed on shutdown — none lost.
            totalSpans.get() shouldBe 3
        }
}
