// Wave 0 stub — fails until Plan 04 creates Route.queryApi (the :8080 query REST API).
package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Wave-0 RED scaffold for the query REST API route (Plan 04 produces `Route.queryApi`).
 *
 * Pins SC3-route over a `mockk<SpanRepository>()` fake (Docker-free, the `OtlpHttpRoutesTest`
 * `testApplication {}` idiom): `ContentNegotiation { json() }` installed, `queryApi(repo)` mounted.
 *  - GET /api/runs → 200 + a JSON run list,
 *  - GET /api/runs/{id}/dag → 200 for a known run,
 *  - an unknown run id → 404,
 *  - a malformed cost filter (`costMin=NaN`) → 400.
 *
 * References `Route.queryApi` — created by Plan 04, so this file does not compile until then
 * (intentional RED; NOT @Disabled). Block-body `@Test` returning Unit (Pitfall 5).
 */
class QueryApiTest {
    private fun fakeRepo(): SpanRepository =
        mockk<SpanRepository> {
            coEvery { listRuns(any(), any(), any()) } returns Result.success(emptyList())
            coEvery { getTraceById(any()) } returns Result.success(emptyList())
        }

    @Test
    fun listRunsReturns200WithJsonBody() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { queryApi(fakeRepo()) }
            }
            val response = client.get("/api/runs")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "runs"
        }
    }

    @Test
    fun dagForKnownRunReturns200() {
        val repo =
            mockk<SpanRepository> {
                coEvery { getTraceById("known-run-000000000000000001") } returns
                    Result.success(
                        listOf(
                            io.github.unityinflow.agenttracer.storage
                                .SpanRow()
                                .apply { spanId = "1111111111111111" },
                        ),
                    )
            }
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { queryApi(repo) }
            }
            val response = client.get("/api/runs/known-run-000000000000000001/dag")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun unknownRunDagReturns404() {
        val repo =
            mockk<SpanRepository> {
                coEvery { getTraceById(any()) } returns Result.success(emptyList())
            }
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { queryApi(repo) }
            }
            val response = client.get("/api/runs/no-such-run-00000000000000001/dag")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun malformedCostFilterReturns400() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { queryApi(fakeRepo()) }
            }
            val response = client.get("/api/runs?costMin=NaN")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
}
