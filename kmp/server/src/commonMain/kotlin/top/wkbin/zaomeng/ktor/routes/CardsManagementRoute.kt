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
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.RecommendSceneCardsRequest
import top.wkbin.zaomeng.ktor.services.CardsManagementService

/**
 * 可复用卡片管理路由
 *
 * 对应 Python src/web/api/routes/{scene_cards,self_cards,opening_presets}.py：
 * 三类卡片的列表/读取/创建/更新/删除 + 场景卡推荐。
 */
fun Route.cardsManagementRoutes(service: CardsManagementService) {
    cardKindRoutes(service, "scene", "/api/web/scene-cards")
    cardKindRoutes(service, "self", "/api/web/self-cards")
    cardKindRoutes(service, "opening", "/api/web/opening-presets")
}

private fun Route.cardKindRoutes(service: CardsManagementService, kind: String, base: String) {
    // 列表
    get(base) {
        cardsCall(call) { buildJsonObject { put("items", service.list(kind)) } }
    }

    // 单卡读取
    get("$base/{card_id}") {
        val cardId = call.parameters["card_id"].orEmpty()
        cardsCall(call) { service.get(kind, cardId) }
    }

    // 创建
    post(base) {
        val fields = call.receive<JsonObject>()
        cardsCall(call) { service.save(kind, "", fields) }
    }

    // 更新
    put("$base/{card_id}") {
        val cardId = call.parameters["card_id"].orEmpty()
        val fields = call.receive<JsonObject>()
        cardsCall(call) { service.save(kind, cardId, fields) }
    }

    // 删除
    delete("$base/{card_id}") {
        val cardId = call.parameters["card_id"].orEmpty()
        cardsCall(call) { service.delete(kind, cardId) }
    }

    // 场景卡推荐
    if (kind == "scene") {
        post("$base/recommend") {
            val request = call.receive<RecommendSceneCardsRequest>()
            cardsCall(call) { service.recommend(request.mode, request.participants) }
        }
    }
}

private suspend fun cardsCall(call: ApplicationCall, block: suspend () -> JsonObject) {
    try {
        call.respond(HttpStatusCode.OK, block())
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
    } catch (e: Exception) {
        call.application.log.error("Cards route failed", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
    }
}
