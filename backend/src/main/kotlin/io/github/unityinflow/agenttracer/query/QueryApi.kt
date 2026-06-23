package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Mounts the read-only query REST API (API-01..04) on the :8080 JSON server — the curl-able backend
 * milestone that answers every agent-debugging question before any frontend exists. Mirrors the
 * `Route.otlpTraces` extension-function shape and its guard-then-`return@<verb>` early-exit error
 * style ([io.github.unityinflow.agenttracer.ingest.otlpTraces]), but speaks JSON (via the
 * `ContentNegotiation { json() }` installed on the :8080 `embeddedServer` only, Pitfall 1) instead of
 * protobuf.
 *
 * Three routes, all `suspend`, each unwrapping the repository's [Result] with `.getOrElse`:
 *  - `GET /api/runs` — `RunListMapper.toFilter`/`limitOf`/`offsetOf` map the untrusted query params
 *    into a validated [io.github.unityinflow.agenttracer.storage.RunFilter] + bounded paging, then
 *    `repository.listRuns(filter, limit, offset)` returns a newest-first page wrapped in a
 *    [RunListResponse]. A malformed param (NaN/Inf cost, bad ISO-8601 date) throws
 *    [IllegalArgumentException] in the mapper → **400** (never a malformed filter, T-04-11).
 *  - `GET /api/runs/{id}/dag` — `repository.getTraceById(id)` → `DagAssembler.assemble(spans)`; an
 *    unknown run (empty span list) → **404**.
 *  - `GET /api/runs/{id}/saturation` — same id handling → `SaturationAssembler.assemble(spans)`;
 *    empty → **404**.
 *
 * **Error hygiene (T-04-12):** 400/404/500 carry a short generic message only — never an exception
 * stack trace, SQL, or DB internals. The repository already logs read failures server-side
 * ([SpanRepository] contract); the route never echoes them to the client.
 *
 * **Tampering hygiene (T-04-10):** the `{id}` path param is passed only as a client-v2 `{name:Type}`
 * placeholder to the repository (T-02-06) — the query layer builds no SQL; a blank/missing id is
 * rejected with **400** before any read.
 *
 * `val`-only, no `!!`, no `Thread.sleep` — every handler is suspending and never blocks.
 *
 * @param repository the storage read seam (`getTraceById` / `listRuns`); the route holds no SQL.
 */
fun Route.queryApi(repository: SpanRepository) {
    val runListMapper = RunListMapper()
    val dagAssembler = DagAssembler()
    val saturationAssembler = SaturationAssembler()

    get("/api/runs") {
        val params: Map<String, String?> =
            call.request.queryParameters
                .entries()
                .associate { (key, values) -> key to values.firstOrNull() }

        val filter =
            try {
                runListMapper.toFilter(params)
            } catch (e: IllegalArgumentException) {
                // T-04-11: a malformed cost/date param → 400, generic message only (no internals).
                call.respond(HttpStatusCode.BadRequest, "Invalid query parameter")
                return@get
            }
        val limit = runListMapper.limitOf(params)
        val offset = runListMapper.offsetOf(params)

        val rows =
            repository.listRuns(filter, limit, offset).getOrElse {
                // T-04-12: read failure → 500 generic; the repository already logged the cause.
                call.respond(HttpStatusCode.InternalServerError, "Query failed")
                return@get
            }
        call.respond(runListMapper.toResponse(rows, limit, offset))
    }

    get("/api/runs/{id}/dag") {
        val id =
            call.parameters["id"]?.takeIf { it.isNotBlank() }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Missing run id")
                    return@get
                }
        val spans =
            repository.getTraceById(id).getOrElse {
                call.respond(HttpStatusCode.InternalServerError, "Query failed")
                return@get
            }
        if (spans.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, "Run not found")
            return@get
        }
        call.respond(dagAssembler.assemble(spans))
    }

    get("/api/runs/{id}/saturation") {
        val id =
            call.parameters["id"]?.takeIf { it.isNotBlank() }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Missing run id")
                    return@get
                }
        val spans =
            repository.getTraceById(id).getOrElse {
                call.respond(HttpStatusCode.InternalServerError, "Query failed")
                return@get
            }
        if (spans.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, "Run not found")
            return@get
        }
        call.respond(saturationAssembler.assemble(spans))
    }
}
