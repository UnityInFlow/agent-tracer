package io.github.unityinflow.agentsemconv

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GenAiOperationTest {
    @Test
    fun `fromOtel maps invoke_agent to INVOKE_AGENT`() {
        GenAiOperation.fromOtel("invoke_agent") shouldBe GenAiOperation.INVOKE_AGENT
    }

    @Test
    fun `fromOtel maps execute_tool to EXECUTE_TOOL`() {
        GenAiOperation.fromOtel("execute_tool") shouldBe GenAiOperation.EXECUTE_TOOL
    }

    @Test
    fun `fromOtel maps chat to CHAT`() {
        GenAiOperation.fromOtel("chat") shouldBe GenAiOperation.CHAT
    }

    @Test
    fun `fromOtel maps generate_content to GENERATE_CONTENT`() {
        GenAiOperation.fromOtel("generate_content") shouldBe GenAiOperation.GENERATE_CONTENT
    }

    @Test
    fun `fromOtel maps text_completion to TEXT_COMPLETION`() {
        GenAiOperation.fromOtel("text_completion") shouldBe GenAiOperation.TEXT_COMPLETION
    }

    @Test
    fun `fromOtel maps embeddings to EMBEDDINGS`() {
        GenAiOperation.fromOtel("embeddings") shouldBe GenAiOperation.EMBEDDINGS
    }

    @Test
    fun `fromOtel maps plan to PLAN`() {
        GenAiOperation.fromOtel("plan") shouldBe GenAiOperation.PLAN
    }

    @Test
    fun `fromOtel maps create_agent to CREATE_AGENT`() {
        GenAiOperation.fromOtel("create_agent") shouldBe GenAiOperation.CREATE_AGENT
    }

    @Test
    fun `fromOtel returns null for an unknown operation without throwing`() {
        GenAiOperation.fromOtel("not_a_real_operation").shouldBeNull()
    }
}
