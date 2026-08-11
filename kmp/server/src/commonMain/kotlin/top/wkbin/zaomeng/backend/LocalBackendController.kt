package top.wkbin.zaomeng.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import top.wkbin.zaomeng.ktor.routes.originalKnowledgeRoutes
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
import top.wkbin.zaomeng.platform.ServerPlatform

/**
 * 通用内嵌后端控制器：三端统一的 Ktor CIO server（数据走 Room）。
 * 由平台入口提供 ServerPlatform/端口/token，进程内启动一次。
 */
class LocalBackendController(
    private val serverPlatform: ServerPlatform,
    /** 0 = 随机端口（默认，避免固定端口被占用/多实例冲突）。 */
    private val port: Int = 0,
    private val token: String = "dev-token",
) : BackendController {
    private val mutableState = MutableStateFlow<BackendState>(BackendState.Idle)

    override val state: StateFlow<BackendState> = mutableState.asStateFlow()

    @Volatile
    private var started = false
    @Volatile
    private var boundPort: Int? = null

    /** 实际绑定端口（随机端口模式下在 start 后解析）。 */
    val actualPort: Int? get() = boundPort

    /** 幂等启动（供端点提供者在请求前确保后端就绪）。 */
    fun ensureStarted() {
        start()
    }

    /** 等待实际端口就绪（随机端口在 start 后异步解析）。 */
    suspend fun awaitPort(): Int {
        boundPort?.let { return it }
        repeat(200) {
            delay(50)
            boundPort?.let { return it }
        }
        error("内嵌后端端口未就绪")
    }

    override fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        mutableState.value = BackendState.Starting("启动内嵌后端…")
        try {
            val services = KtorServiceGraph(serverPlatform)
            val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                install(ContentNegotiation) {
                    json(
                        Json {
                            prettyPrint = true
                            isLenient = true
                            ignoreUnknownKeys = true
                            // 新格式：类型化 DTO 序列化必须写全字段（默认值也落盘）
                            encodeDefaults = true
                        },
                    )
                }
                configureObservability()
                configureSecurity(token)

                val storage = services.storage
                routing {
                    healthRoute()
                    runsRoute(storage)
                    diagnosticsRoute(storage, services.diagnostics)
                    dialogueRoutes(services.dialogue, storage)
                    sessionManagementRoutes(services.sessionManagement)
                    runManagementRoutes(services.runManagement, services.runPackages)
                    settingsManagementRoutes(services.settingsManagement)
                    dialogueStreamRoutes(services.dialogueStream, storage)
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
                    originalKnowledgeRoutes(services.originalKnowledge, storage)
                    pluginRoutes(services.plugins)
                    pluginOperationsRoutes(services.pluginOperations)
                }
            }
            server.start(wait = false)
            // CIO 在 start 后异步完成绑定，resolvedConnectors 返回实际端口（端口 0 时为随机值）。
            boundPort = runBlocking {
                runCatching {
                server.engine.resolvedConnectors().firstOrNull()?.port
                }.getOrNull()
            }
            mutableState.value = BackendState.Ready("http://127.0.0.1:${boundPort ?: port}")
        } catch (error: Throwable) {
            mutableState.value = BackendState.Failed(error.message ?: "后端启动失败")
        }
    }

    override fun retry() {
        started = false
        start()
    }
}
