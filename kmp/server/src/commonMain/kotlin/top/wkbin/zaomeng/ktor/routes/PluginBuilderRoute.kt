package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import top.wkbin.zaomeng.data.api.PackagePluginDraftRequest
import top.wkbin.zaomeng.data.api.GeneratePluginDraftRequest
import top.wkbin.zaomeng.data.api.ValidatePluginDraftRequest
import top.wkbin.zaomeng.ktor.services.PluginBuilderService

fun Route.pluginBuilderRoutes(service: PluginBuilderService) {
    post("/api/web/plugins/builder/generate") {
        try {
            val request = call.receive<GeneratePluginDraftRequest>()
            call.respond(service.generate(request.description))
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (error.message ?: "插件需求不完整。")))
        } catch (error: Exception) {
            call.application.log.error("Plugin builder generation failed", error)
            call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (error.message ?: "生成插件草稿失败。")))
        }
    }

    post("/api/web/plugins/builder/validate") {
        val request = call.receive<ValidatePluginDraftRequest>()
        call.respond(service.validate(request.draft))
    }

    post("/api/web/plugins/builder/package") {
        try {
            val request = call.receive<PackagePluginDraftRequest>()
            val result = service.packagePlugin(request.draft)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, result.filename)
                    .toString(),
            )
            call.respondBytes(result.bytes, ContentType.Application.Zip)
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (error.message ?: "插件草稿未通过校验。")))
        } catch (error: Exception) {
            call.application.log.error("Plugin builder package failed", error)
            call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (error.message ?: "插件打包失败。")))
        }
    }
}
