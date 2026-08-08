package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import top.wkbin.zaomeng.data.api.BranchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.BranchDialogueTurnRequest
import top.wkbin.zaomeng.data.api.DialogueDirectorRequest
import top.wkbin.zaomeng.data.api.DialogueSuggestionRequest
import top.wkbin.zaomeng.data.api.SwitchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueBranchMetaRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueRelationLockRequest
import top.wkbin.zaomeng.data.api.UpsertDialogueMemoryRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.ktor.services.DialogueAdvancedService

/**
 * 对话高级功能路由
 *
 * 对应 Python src/web/api/routes/dialogue.py 中的高级对话端点：
 * search / recover / branch / branch-turn / branch-meta / relation-lock /
 * memories / suggest / correct-latest / deep-review / director-options /
 * scene-card / scene-card/recommend
 */
fun Route.dialogueAdvancedRoutes(service: DialogueAdvancedService) {
    // GET .../sessions/{session_id}/search?q=&limit=
    get("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/search") {
        val (runId, sessionId) = requireRunAndSession()
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.isEmpty() || query.length > 120) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "q 需为 1-120 字"))
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        runRoute(call) { buildJsonObject { put("items", service.search(runId, sessionId, query, limit)) } }
    }

    // POST .../sessions/{session_id}/recover?force=
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/recover") {
        val (runId, sessionId) = requireRunAndSession()
        val force = call.request.queryParameters["force"]?.toBoolean() ?: false
        runRoute(call) { service.recover(runId, sessionId, force) }
    }

    // POST .../sessions/{session_id}/branch
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<BranchDialogueSceneRequest>()
        runRoute(call) { service.branchFromScene(runId, sessionId, request.sceneIndex) }
    }

    // POST .../sessions/{session_id}/branch-turn
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-turn") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<BranchDialogueTurnRequest>()
        runRoute(call) { service.branchFromTurn(runId, sessionId, request.turnId) }
    }

    // PATCH .../sessions/{session_id}/branch-meta
    patch("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-meta") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<UpdateDialogueBranchMetaRequest>()
        runRoute(call) {
            service.updateBranchMeta(
                runId,
                sessionId,
                label = request.label,
                isMainline = request.isMainline,
                lockedEventIds = request.lockedEventIds,
            )
        }
    }

    // PUT .../sessions/{session_id}/relation-lock
    put("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/relation-lock") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<UpdateDialogueRelationLockRequest>()
        runRoute(call) { service.setRelationLock(runId, sessionId, request.pairKey, request.locked) }
    }

    // POST .../sessions/{session_id}/memories
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<UpsertDialogueMemoryRequest>()
        runRoute(call) {
            service.saveMemory(
                runId,
                sessionId,
                memoryId = "",
                text = request.text,
                category = request.category,
                pinned = request.pinned,
                enabled = request.enabled,
            )
        }
    }

    // PUT .../sessions/{session_id}/memories/{memory_id}
    put("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}") {
        val (runId, sessionId) = requireRunAndSession()
        val memoryId = call.parameters["memory_id"].orEmpty()
        val request = call.receive<UpsertDialogueMemoryRequest>()
        runRoute(call) {
            service.saveMemory(
                runId,
                sessionId,
                memoryId = memoryId,
                text = request.text,
                category = request.category,
                pinned = request.pinned,
                enabled = request.enabled,
            )
        }
    }

    // DELETE .../sessions/{session_id}/memories/{memory_id}
    delete("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}") {
        val (runId, sessionId) = requireRunAndSession()
        val memoryId = call.parameters["memory_id"].orEmpty()
        runRoute(call) { service.deleteMemory(runId, sessionId, memoryId) }
    }

    // POST .../sessions/{session_id}/suggest
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggest") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<DialogueSuggestionRequest>()
        runRoute(call) { service.suggestDialogue(runId, sessionId, request.seedText, request.direction) }
    }

    // POST .../sessions/{session_id}/correct-latest
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/correct-latest") {
        val (runId, sessionId) = requireRunAndSession()
        runRoute(call) { service.correctLatest(runId, sessionId) }
    }

    // POST .../sessions/{session_id}/deep-review
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/deep-review") {
        val (runId, sessionId) = requireRunAndSession()
        runRoute(call) { service.deepReview(runId, sessionId) }
    }

    // POST .../sessions/{session_id}/director-options
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director-options") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<DialogueDirectorRequest>()
        runRoute(call) {
            service.directDialogue(
                runId,
                sessionId,
                goal = request.goal,
                action = request.action,
                optionCount = request.optionCount,
            )
        }
    }

    // PUT .../sessions/{session_id}/scene-card
    put("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card") {
        val (runId, sessionId) = requireRunAndSession()
        val request = call.receive<SwitchDialogueSceneRequest>()
        runRoute(call) {
            service.switchScene(
                runId,
                sessionId,
                sceneCardId = request.sceneCardId,
                sceneProfile = request.sceneProfile,
                transitionMessage = request.transitionMessage,
                autoContinue = request.autoContinue,
            )
        }
    }

    // POST .../sessions/{session_id}/scene-card/recommend
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card/recommend") {
        val (runId, sessionId) = requireRunAndSession()
        runRoute(call) { service.recommendScene(runId, sessionId) }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.requireRunAndSession(): Pair<String, String> =
    call.parameters["run_id"].orEmpty() to call.parameters["session_id"].orEmpty()

private suspend fun runRoute(
    call: ApplicationCall,
    block: suspend () -> JsonObject,
) {
    try {
        call.respond(HttpStatusCode.OK, block())
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
    } catch (e: Exception) {
        call.application.log.error("Dialogue advanced route failed", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
    }
}
