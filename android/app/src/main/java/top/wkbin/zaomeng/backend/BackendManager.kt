package top.wkbin.zaomeng.backend

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import top.wkbin.zaomeng.data.api.KtorHttpClientProvider
import top.wkbin.zaomeng.ktor.KtorBackendController
import top.wkbin.zaomeng.ktor.KtorServiceGraph

/**
 * 后端管理器
 *
 * Ktor 后端生命周期和兼容 API 的统一入口。
 */
class BackendManager(
    context: android.content.Context,
    tokenStore: InstallationTokenStore,
    modelApiKeyStore: ModelApiKeyStore,
    ktorServices: KtorServiceGraph,
    ktorHttp: KtorHttpClientProvider,
) {
    private val ktorBackend = KtorBackendController(
        context = context,
        tokenStore = tokenStore,
        modelApiKeyStore = modelApiKeyStore,
        services = ktorServices,
        ktorHttp = ktorHttp,
    )

    val state: StateFlow<BackendState> get() = ktorBackend.state

    /**
     * 启动后端服务
     */
    fun start() {
        ktorBackend.start()
    }

    /**
     * 重试启动
     */
    fun retry() {
        ktorBackend.retry()
    }

    /**
     * 获取当前使用的后端名称（用于调试）
     */
    fun getBackendName(): String {
        return "Ktor"
    }

    suspend fun requireKtorEndpoint(): BackendEndpoint {
        start()
        val terminal = state.first { it is BackendState.Ready || it is BackendState.Failed }
        if (terminal is BackendState.Failed) throw IllegalStateException(terminal.message)
        val ready = terminal as BackendState.Ready
        return BackendEndpoint(ready.baseUrl)
    }

    data class BackendEndpoint(val baseUrl: String)
}
