package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import top.wkbin.zaomeng.data.api.EditWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.CreateWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.SetWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.SetWorldBookRecursionRequest
import top.wkbin.zaomeng.data.api.TestWorldBooksRequest
import top.wkbin.zaomeng.ktor.http.respondError
import top.wkbin.zaomeng.ktor.services.SillyTavernImport
import top.wkbin.zaomeng.ktor.services.WorldBookChangedException
import top.wkbin.zaomeng.platform.platformIoDispatcher

internal fun Route.worldBookRoutes(service: SillyTavernImport) {
    route("/api/web/runs/{run_id}/world-books") {
        get {
            call.worldBookResponse { runId -> service.listWorldBooks(runId) }
        }
        get("/{source}/export") {
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=world-book.json")
            call.worldBookResponse { runId ->
                val source = requireNotNull(call.parameters["source"])
                service.exportWorldBook(runId, source)
            }
        }
        post("/test") {
            val request = call.receive<TestWorldBooksRequest>()
            call.worldBookResponse { runId ->
                service.testWorldBooks(runId, request.text, request.messageCount, request.generationType, request.history, request.context)
            }
        }
        post("/{source}/entries") {
            val request = call.receive<CreateWorldBookEntryRequest>()
            call.worldBookResponse { runId ->
                val source = requireNotNull(call.parameters["source"])
                service.createBookEntry(runId, source, request)
            }
        }
        patch("/{source}/entries/{index}") {
            val request = call.receive<SetWorldBookEntryRequest>()
            call.worldBookResponse { runId ->
                val source = requireNotNull(call.parameters["source"])
                val index = requireNotNull(call.parameters["index"]?.toIntOrNull()) { "条目序号无效。" }
                service.setEntryEnabled(runId, source, index, request.revision, request.enabled)
            }
        }
        patch("/{source}/recursion") {
            val request = call.receive<SetWorldBookRecursionRequest>()
            call.worldBookResponse { runId ->
                val source = requireNotNull(call.parameters["source"])
                service.setBookRecursion(runId, source, request.revision, request.enabled)
            }
        }
        patch("/{source}/entries/{index}/content") {
            val request = call.receive<EditWorldBookEntryRequest>()
            call.worldBookResponse { runId ->
                val source = requireNotNull(call.parameters["source"])
                val index = requireNotNull(call.parameters["index"]?.toIntOrNull()) { "条目序号无效。" }
                service.editBookEntry(runId, source, index, request)
            }
        }
        delete("/{source}/entries/{index}") {
            call.worldBookResponse { runId ->
                val source = requireNotNull(call.parameters["source"])
                val index = requireNotNull(call.parameters["index"]?.toIntOrNull()) { "条目序号无效。" }
                val revision = requireNotNull(call.request.queryParameters["revision"]?.takeIf { it.isNotBlank() }) {
                    "缺少世界书版本。"
                }
                service.deleteBookEntry(runId, source, index, revision)
            }
        }
        delete("/standalone") {
            call.worldBookResponse { runId ->
                val revision = requireNotNull(call.request.queryParameters["revision"]) { "缺少世界书版本。" }
                service.removeStandaloneBook(runId, revision)
            }
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.worldBookResponse(crossinline block: (String) -> T) {
    try {
        val runId = requireNotNull(parameters["run_id"]) { "缺少书卷 ID。" }
        respond(withContext(platformIoDispatcher) { block(runId) })
    } catch (error: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, error.message ?: "请求无效。")
    } catch (error: NoSuchElementException) {
        respondError(HttpStatusCode.NotFound, error.message ?: "世界书不存在。")
    } catch (error: WorldBookChangedException) {
        respondError(HttpStatusCode.Conflict, error.message.orEmpty())
    }
}
