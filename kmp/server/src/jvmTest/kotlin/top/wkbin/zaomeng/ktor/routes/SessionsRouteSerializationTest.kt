package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.data.api.SessionListItem
import top.wkbin.zaomeng.data.api.SessionsPageResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试：会话列表响应必须走类型化 DTO。
 *
 * 之前用 mapOf("items" to List<SessionListItem>) 响应时，Ktor 对 Map<String, Any>
 * 中的自定义类型无法序列化，直接抛异常 → StatusPages 兜底 500。
 */
class SessionsRouteSerializationTest {
    private fun Application.configure() {
        install(ServerContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        routing {
            get("/typed") {
                call.respond(
                    SessionsPageResponse(
                        items = listOf(SessionListItem(sessionId = "s1", runId = "run-1")),
                        total = 1,
                        hasMore = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `typed sessions page response serializes and round-trips`() = testApplication {
        application { configure() }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val response = client.get("/typed")
        assertEquals(HttpStatusCode.OK, response.status)
        val body: SessionsPageResponse = response.body()
        assertEquals("s1", body.items.first().sessionId)
        assertEquals(1, body.total)
        assertTrue(!body.hasMore)
    }

    @Test
    fun `typed response includes wire compatible fields`() = testApplication {
        application { configure() }
        val client = createClient { }

        val text = client.get("/typed").body<String>()

        assertTrue(text.contains("\"items\""))
        assertTrue(text.contains("\"session_id\":\"s1\""))
        assertTrue(text.contains("\"has_more\":false"))
    }

    @Test
    fun `session list content type is json`() = testApplication {
        application { configure() }
        val client = createClient { }

        val response = client.get("/typed")

        assertTrue(response.headers["Content-Type"].orEmpty().startsWith(ContentType.Application.Json.toString()))
    }
}
