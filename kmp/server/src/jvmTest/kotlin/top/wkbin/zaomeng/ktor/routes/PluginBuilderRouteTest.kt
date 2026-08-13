package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.data.api.PackagePluginDraftRequest
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.ValidatePluginDraftRequest
import top.wkbin.zaomeng.ktor.services.PluginBuilderService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginBuilderRouteTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `validate and package endpoints expose the same valid manifest`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { pluginBuilderRoutes(PluginBuilderService()) }
        }
        val draft = PluginDraft(
            name = "测试插件",
            title = "测试动作",
            prompt = "结合当前场景生成下一句：{{seed_text}}",
        )
        val validationResponse = client.post("/api/web/plugins/builder/validate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(ValidatePluginDraftRequest(draft)))
        }
        assertEquals(HttpStatusCode.OK, validationResponse.status)
        val validation = json.decodeFromString<PluginBuilderValidationDto>(validationResponse.bodyAsText())
        assertTrue(validation.valid, validation.issues.joinToString { it.message })

        val packageResponse = client.post("/api/web/plugins/builder/package") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(PackagePluginDraftRequest(validation.draft)))
        }
        assertEquals(HttpStatusCode.OK, packageResponse.status)
        assertEquals(ContentType.Application.Zip, packageResponse.contentType()?.withoutParameters())
        assertTrue(packageResponse.headers[HttpHeaders.ContentDisposition].orEmpty().contains("zaomeng-plugin.zip"))
        assertTrue(packageResponse.body<ByteArray>().isNotEmpty())
    }
}
