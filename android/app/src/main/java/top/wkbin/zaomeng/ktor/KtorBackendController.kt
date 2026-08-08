package top.wkbin.zaomeng.ktor

import android.content.Context
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.backend.InstallationTokenStore
import top.wkbin.zaomeng.backend.ModelApiKeyStore
import top.wkbin.zaomeng.data.api.KtorHttpClientProvider
import top.wkbin.zaomeng.data.api.KtorHealthClient
import top.wkbin.zaomeng.ktor.plugins.configureSecurity
import top.wkbin.zaomeng.ktor.plugins.configureObservability
import top.wkbin.zaomeng.ktor.routes.diagnosticsRoute
import top.wkbin.zaomeng.ktor.routes.dialogueRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueAdvancedRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueStreamRoutes
import top.wkbin.zaomeng.ktor.routes.healthRoute
import top.wkbin.zaomeng.ktor.routes.runsRoute
import top.wkbin.zaomeng.ktor.routes.sessionManagementRoutes
import top.wkbin.zaomeng.ktor.routes.runManagementRoutes
import top.wkbin.zaomeng.ktor.routes.settingsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.suggestionsRoutes
import top.wkbin.zaomeng.ktor.routes.chapterRoutes
import top.wkbin.zaomeng.ktor.routes.chapterManagementRoutes
import top.wkbin.zaomeng.ktor.routes.cardRoutes
import top.wkbin.zaomeng.ktor.routes.cardsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.personaRoutes
import top.wkbin.zaomeng.ktor.routes.pluginOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.relationsRoutes
import top.wkbin.zaomeng.ktor.routes.runOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.worldMemoryRoutes
import top.wkbin.zaomeng.ktor.routes.pluginRoutes
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.ktor.services.ModelApiKeyService
import top.wkbin.zaomeng.ktor.services.ChapterService
import top.wkbin.zaomeng.ktor.services.CardsService
import top.wkbin.zaomeng.ktor.services.PersonaService
import top.wkbin.zaomeng.ktor.services.PluginService
import java.io.File
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ktor 后端控制器
 *
 * 作为 Python 后端的替代实现，使用 Ktor 提供相同的 API
 */
class KtorBackendController(
    context: Context,
    private val tokenStore: InstallationTokenStore,
    private val modelApiKeyStore: ModelApiKeyStore,
    private val services: KtorServiceGraph,
    ktorHttp: KtorHttpClientProvider,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<BackendState>(BackendState.Idle)
    private var startJob: Job? = null
    private val healthClient = KtorHealthClient(ktorHttp)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val state: StateFlow<BackendState> = mutableState.asStateFlow()

    fun start() {
        if (startJob?.isActive == true || mutableState.value is BackendState.Ready) return
        startJob = scope.launch {
            try {
                mutableState.value = BackendState.Starting("正在启动 Ktor 服务…")

                val token = tokenStore.getOrCreate()
                val storageRoot = services.storage.getStorageRoot().apply { mkdirs() }
                val port = findAvailablePort()

                // 创建 Ktor 服务器
                server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                    configureKtorApp(token, storageRoot, applicationContext)
                }

                server?.start(wait = false)

                val baseUrl = "http://127.0.0.1:$port"

                // 等待健康检查通过
                awaitHealthy(baseUrl, token)

                mutableState.value = BackendState.Ready(baseUrl)
            } catch (error: Throwable) {
                mutableState.value = BackendState.Failed(readableMessage(error))
            }
        }
    }

    fun retry() {
        if (startJob?.isActive == true) return
        server?.stop(1000, 2000)
        server = null
        mutableState.value = BackendState.Idle
        start()
    }

    private fun Application.configureKtorApp(token: String, storageRoot: File, androidContext: Context) {
        // 配置 JSON 序列化
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        configureObservability()

        // 配置安全认证
        configureSecurity(token)

        // 创建服务层
        val storageService = services.storage
        val diagnosticsService = services.diagnostics

        // 配置路由
        routing {
            healthRoute()
            runsRoute(storageService)
            diagnosticsRoute(storageService, diagnosticsService)
            dialogueRoutes(services.dialogue, storageService)

            // Phase 4: 写入 API 和状态管理
            sessionManagementRoutes(services.sessionManagement)
            runManagementRoutes(services.runManagement, services.runPackages)
            settingsManagementRoutes(services.settingsManagement)

            // Phase 5: 流式响应和实时功能
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

    private suspend fun awaitHealthy(baseUrl: String, token: String) {
        var lastError: Throwable? = null
        repeat(STARTUP_ATTEMPTS) {
            try {
                withTimeout(HEALTH_TIMEOUT_MS.milliseconds) { healthClient.check(baseUrl) }
                return
            } catch (error: Throwable) {
                lastError = error
                kotlinx.coroutines.delay(STARTUP_RETRY_DELAY_MS.milliseconds)
            }
        }
        throw IllegalStateException("Ktor 服务启动超时。", lastError)
    }

    private fun readableMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: error::class.java.simpleName

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    companion object {
        private const val STARTUP_ATTEMPTS = 80
        private const val STARTUP_RETRY_DELAY_MS = 250L
        private const val HEALTH_TIMEOUT_MS = 1_500L
    }
}
