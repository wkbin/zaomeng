package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.ktor.services.*

/**
 * 设置管理路由
 *
 * Phase 4: 写入 API 和状态管理
 */
fun Route.settingsManagementRoutes(settingsService: SettingsManagementService) {
    post("/api/web/settings/model/profiles/{profile_id}/activate") {
        val profileId = call.parameters["profile_id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing profile_id")
        try {
            call.respond(HttpStatusCode.OK, settingsService.activateProfile(profileId))
        } catch (e: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, (e.message ?: "Profile not found"))
        }
    }

    delete("/api/web/settings/model/profiles/{profile_id}") {
        val profileId = call.parameters["profile_id"] ?: return@delete call.respondError(HttpStatusCode.BadRequest, "Missing profile_id")
        try {
            call.respond(HttpStatusCode.OK, settingsService.deleteProfile(profileId))
        } catch (e: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, (e.message ?: "Profile not found"))
        }
    }

    // 获取模型设置
    get("/api/web/settings/model") {
        try {
            val result = settingsService.getModelSettings()
            call.respond(HttpStatusCode.OK, result)
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, "Failed to get settings")
        }
    }

    // 保存模型设置
    put("/api/web/settings/model") {
        try {
            val request = call.receive<SaveModelSettingsRequest>()
            val result = settingsService.saveModelSettings(
                provider = request.provider,
                model = request.model,
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                maxTokens = request.maxTokens,
                reasoningEffort = request.reasoningEffort,
                tokenParameter = request.tokenParameter,
                responseFormatMode = request.responseFormatMode,
                profileId = request.profileId,
                profileName = request.profileName,
                createProfile = request.createProfile,
                activateProfile = request.activateProfile
            )
            call.respond(HttpStatusCode.OK, result)
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, "Failed to save settings")
        }
    }

    // 测试模型设置
    post("/api/web/settings/model/test") {
        try {
            val request = call.receive<TestModelSettingsRequest>()
            val result = settingsService.testModelSettings(
                provider = request.provider,
                model = request.model,
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                maxTokens = request.maxTokens,
                reasoningEffort = request.reasoningEffort,
                tokenParameter = request.tokenParameter,
                profileId = request.profileId
            )
            call.respond(HttpStatusCode.OK, result)
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, "Failed to test settings")
        }
    }

    post("/api/web/settings/model/detect-capabilities") {
        try {
            val request = call.receive<TestModelSettingsRequest>()
            call.respond(
                HttpStatusCode.OK,
                settingsService.detectModelCapabilities(
                    provider = request.provider,
                    model = request.model,
                    baseUrl = request.baseUrl,
                    apiKey = request.apiKey,
                    maxTokens = request.maxTokens,
                    reasoningEffort = request.reasoningEffort,
                    tokenParameter = request.tokenParameter,
                    profileId = request.profileId,
                ),
            )
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Capability detection failed"))
        }
    }
}
