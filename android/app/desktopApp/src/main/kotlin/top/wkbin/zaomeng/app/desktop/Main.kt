package top.wkbin.zaomeng.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.stop
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.app.shared.ResPromptSource
import top.wkbin.zaomeng.app.shared.envVar
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.data.api.KtorHealthClient
import top.wkbin.zaomeng.data.api.KtorHttpClientProvider
import top.wkbin.zaomeng.ktor.KtorServiceGraph
import top.wkbin.zaomeng.ktor.plugins.configureObservability
import top.wkbin.zaomeng.ktor.plugins.configureSecurity
import top.wkbin.zaomeng.ktor.routes.cardRoutes
import top.wkbin.zaomeng.ktor.routes.cardsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterRoutes
import top.wkbin.zaomeng.ktor.routes.diagnosticsRoute
import top.wkbin.zaomeng.ktor.routes.dialogueAdvancedRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueStreamRoutes
import top.wkbin.zaomeng.ktor.routes.healthRoute
import top.wkbin.zaomeng.ktor.routes.personaRoutes
import top.wkbin.zaomeng.ktor.routes.pluginOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.pluginRoutes
import top.wkbin.zaomeng.ktor.routes.relationsRoutes
import top.wkbin.zaomeng.ktor.routes.runManagementRoutes
import top.wkbin.zaomeng.ktor.routes.runOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.runsRoute
import top.wkbin.zaomeng.ktor.routes.sessionManagementRoutes
import top.wkbin.zaomeng.ktor.routes.settingsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.suggestionsRoutes
import top.wkbin.zaomeng.ktor.routes.worldMemoryRoutes
import top.wkbin.zaomeng.platform.JvmServerPlatform

/**
 * Desktop 入口：渲染共享 UI（:app:shared），同时内嵌启动 Ktor 后端。
 *
 * 环境变量：
 * - ZAOMENG_PORT：后端监听端口（默认 8765）
 * - ZAOMENG_TOKEN：API token（默认 desktop-dev-token）
 * - ZAOMENG_DATA：数据根目录（默认仓库根下 zaomeng-data/）
 * - ZAOMENG_SKIP_BACKEND=1：仅启动 UI，不启动后端
 */
fun main() = application {
    val scope = rememberCoroutineScope()
    val port = System.getenv("ZAOMENG_PORT")?.toIntOrNull() ?: 8765
    val token = System.getenv("ZAOMENG_TOKEN") ?: "desktop-dev-token"
    val endpointProvider = DesktopBackendEndpointProvider(port, token)

    LaunchedEffect(Unit) {
        if (System.getenv("ZAOMENG_SKIP_BACKEND") != "1") {
            scope.launch { startBackend(port, token, endpointProvider) }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Zaomeng",
    ) {
        App()
    }
}

/** 桌面端后端端点：直接指向本进程内嵌的 Ktor 服务。 */
class DesktopBackendEndpointProvider(
    private val port: Int,
    private val token: String,
) : BackendEndpointProvider {
    override suspend fun requireKtorEndpoint(): BackendEndpoint =
        BackendEndpoint("http://127.0.0.1:$port")

    override fun currentToken(): String = token
}

private fun startBackend(
    port: Int,
    token: String,
    endpointProvider: BackendEndpointProvider,
) {
    val dataRoot = System.getenv("ZAOMENG_DATA")?.let { it.toPath() }

    val services = KtorServiceGraph(
        if (dataRoot != null) {
            JvmServerPlatform(dataRoot = dataRoot, promptSource = ResPromptSource())
        } else {
            JvmServerPlatform(promptSource = ResPromptSource())
        },
    )

    val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        configureObservability()
        configureSecurity(token)

        val storageService = services.storage
        routing {
            healthRoute()
            runsRoute(storageService)
            diagnosticsRoute(storageService, services.diagnostics)
            dialogueRoutes(services.dialogue, storageService)
            sessionManagementRoutes(services.sessionManagement)
            runManagementRoutes(services.runManagement, services.runPackages)
            settingsManagementRoutes(services.settingsManagement)
            dialogueStreamRoutes(services.dialogueStream, storageService)
            suggestionsRoutes(services.suggestions)
            dialogueAdvancedRoutes(services.dialogueAdvanced)
            chapterRoutes(services.chapter)
            chapterManagementRoutes(services.chapterManagement)
            cardRoutes(services.cards)
            cardsManagementRoutes(services.cardsManagement)
            personaRoutes(services.persona)
            relationsRoutes(services.relations)
            runOperationsRoutes(services.runOperations)
            worldMemoryRoutes(services.worldMemory)
            pluginRoutes(services.plugins)
            pluginOperationsRoutes(services.pluginOperations)
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { server.stop(1000, 2000) }
    })

    println("Zaomeng desktop backend listening on http://127.0.0.1:$port (token: $token)")
    server.start(wait = false)

    // 用共享数据层客户端自检一次，验证 desktop 端完整调用链路
    runBlocking {
        runCatching {
            val client = KtorHttpClientProvider(endpointProvider)
            KtorHealthClient(client).check("http://127.0.0.1:$port")
            println("SHARED CLIENT HEALTH OK")
        }.onFailure { println("SHARED CLIENT HEALTH FAILED: ${it.message}") }
    }
}
