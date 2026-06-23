// Wave 0 stub — fails until Plan 02 creates DagAssembler / DagResponse / DagNode / DagEdge.
package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.SpanRow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Wave-0 RED scaffold for the server-side DAG assembler (Plan 02 produces the symbols).
 *
 * Pins the contracts Plan 02 must satisfy:
 *  - SC1: an orphaned span (its `parentSpanId` names a span absent from the trace) is RETURNED,
 *    reparented under a synthetic root — never silently dropped; late / out-of-order spans are
 *    placed by parent membership, not by arrival order.
 *  - SC2: a group / agent node's subtree cost equals the sum of its own + every descendant's
 *    `costUsd`, computed with a cycle guard (a self/ancestor parent reference must not loop).
 *
 * References `DagAssembler`, `DagResponse`, `DagNode`, `DagEdge` — all created by Plan 02, so this
 * file does not compile until then (intentional RED; NOT @Disabled). Block-body `@Test` methods
 * returning Unit (Pitfall 5 — no expression-body silent skip).
 */
class DagAssemblerTest {
    private fun span(
        spanId: String,
        parentSpanId: String,
        operation: String = "invoke_agent",
        costUsd: Double? = null,
        offsetMillis: Long = 0,
    ): SpanRow =
        SpanRow().apply {
            this.spanId = spanId
            this.parentSpanId = parentSpanId
            this.operation = operation
            this.costUsd = costUsd
            this.traceId = "dag-test-trace-0000000000000001"
            this.timestamp = BASE_TIME.plusMillis(offsetMillis)
        }

    @Test
    fun orphanedSpanIsReparentedToSyntheticRootAndNeverDropped() {
        // Parent "ffffffffffffffff" is absent from the trace → "child" is orphaned.
        val orphan = span(spanId = "1111111111111111", parentSpanId = "ffffffffffffffff")
        val response: DagResponse = DagAssembler().assemble(listOf(orphan))

        // The orphan survives, reparented under a synthetic root rather than dropped.
        val nodeIds = response.nodes.map(DagNode::id)
        nodeIds shouldContain "1111111111111111"
        val syntheticRoot = response.nodes.single { it.isSyntheticRoot }
        response.edges shouldContain DagEdge(from = syntheticRoot.id, to = "1111111111111111")
    }

    @Test
    fun lateAndOutOfOrderSpansArePlacedByMembershipNotArrival() {
        // Child arrives BEFORE its parent in the input list; placement must follow parentSpanId.
        val child = span(spanId = "2222222222222222", parentSpanId = "3333333333333333", offsetMillis = 5)
        val parent = span(spanId = "3333333333333333", parentSpanId = "", offsetMillis = 10)
        val response: DagResponse = DagAssembler().assemble(listOf(child, parent))

        response.edges shouldContain DagEdge(from = "3333333333333333", to = "2222222222222222")
        response.nodes shouldHaveSize 2
    }

    @Test
    fun subtreeCostRollupSumsOwnPlusDescendantCosts() {
        val root = span(spanId = "4444444444444444", parentSpanId = "", costUsd = 0.10)
        val mid = span(spanId = "5555555555555555", parentSpanId = "4444444444444444", costUsd = 0.20)
        val leaf = span(spanId = "6666666666666666", parentSpanId = "5555555555555555", costUsd = 0.05)
        val response: DagResponse = DagAssembler().assemble(listOf(root, mid, leaf))

        val rootNode = response.nodes.single { it.id == "4444444444444444" }
        // Own 0.10 + descendants 0.20 + 0.05 = 0.35.
        rootNode.subtreeCostUsd shouldBeExactly 0.35
    }

    @Test
    fun cycleInParentReferencesDoesNotInfiniteLoopDuringRollup() {
        // a → b → a forms a cycle; the assembler must terminate (cycle-guarded), not hang.
        val a = span(spanId = "7777777777777777", parentSpanId = "8888888888888888", costUsd = 0.01)
        val b = span(spanId = "8888888888888888", parentSpanId = "7777777777777777", costUsd = 0.02)
        val response: DagResponse = DagAssembler().assemble(listOf(a, b))

        // Both nodes survive; the call returns (no StackOverflow / infinite loop).
        response.nodes.map(DagNode::id) shouldContain "7777777777777777"
        (response.nodes.size >= 2) shouldBe true
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-06-23T12:00:00Z")
    }
}
