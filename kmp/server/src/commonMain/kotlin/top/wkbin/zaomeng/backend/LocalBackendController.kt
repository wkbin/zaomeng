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
import kotlin.concurrent.Volatile
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
import top.wkbin.zaomeng.ktor.routes.pluginBuilderRoutes
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
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.randomUuid

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
    /**
     * 实际生效的鉴权 token。
     * 历史上三端都传死值（dev-token / desktop-dev-token），等于没有鉴权；
     * 这里把这类遗留值视为"未配置"，改用进程内随机 token，只在本机进程内共享。
     */
    val authToken: String = token.takeIf { it.isNotBlank() && it !in LEGACY_INSECURE_TOKENS }
        ?: "local-" + randomUuid().replace("-", "") + randomUuid().replace("-", "").take(8)

    private val mutableState = MutableStateFlow<BackendState>(BackendState.Idle)

    override val state: StateFlow<BackendState> = mutableState.asStateFlow()

    @Volatile
    private var started = false
    @Volatile
    private var boundPort: Int? = null

    /** 启动闸门：commonMain 里没有 java 的 `synchronized`，统一用跨平台锁保证幂等。 */
    private val startLock = SimpleLock()

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
        // 只有抢到「从未启动」的那一方真正拉起服务器；其余调用直接返回。
        val firstStart = startLock.withLock {
            if (started) {
                false
            } else {
                started = true
                true
            }
        }
        if (!firstStart) return
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
                configureSecurity(authToken)

                val storage = services.storage
                routing {
                    healthRoute()
                    runsRoute(storage)
                    diagnosticsRoute(storage, services.diagnostics)
                    dialogueRoutes(services.dialogue, storage)
                    sessionManagementRoutes(services.sessionManagement)
                    runManagementRoutes(services.runManagement, services.runPackages)
                    settingsManagementRoutes(services.settingsManagement)
                    dialogueStreamRoutes(services.dialogueStream)
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
                    pluginBuilderRoutes(services.pluginBuilder)
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

    private companion object {
        /** 旧版本写死的默认值：视为未配置，改用随机 token。 */
        val LEGACY_INSECURE_TOKENS = setOf("dev-token", "desktop-dev-token")
    }
}
