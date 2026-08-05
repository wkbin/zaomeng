package top.wkbin.zaomeng.backend

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import top.wkbin.zaomeng.data.api.LocalApiFactory
import top.wkbin.zaomeng.data.api.ZaomengApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

sealed interface BackendState {
    data object Idle : BackendState
    data class Starting(val message: String) : BackendState
    data class Ready(val baseUrl: String) : BackendState
    data class Failed(val message: String) : BackendState
}

class EmbeddedBackendController(
    context: Context,
    private val tokenStore: InstallationTokenStore,
    private val modelApiKeyStore: ModelApiKeyStore,
    private val apiFactory: LocalApiFactory,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<BackendState>(BackendState.Idle)
    private var startJob: Job? = null

    @Volatile
    private var activeApi: ZaomengApi? = null

    val state: StateFlow<BackendState> = mutableState.asStateFlow()

    fun start() {
        if (startJob?.isActive == true || mutableState.value is BackendState.Ready) return
        startJob = scope.launch {
            try {
                mutableState.value = BackendState.Starting("正在载入本地 Python 运行时…")
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }

                mutableState.value = BackendState.Starting("正在启动手机内的 FastAPI 服务…")
                val token = tokenStore.getOrCreate()
                val storageRoot = File(applicationContext.filesDir, "zaomeng").apply { mkdirs() }
                val serverModule = Python.getInstance().getModule("zaomeng_android.server")
                val legacySecrets = serverModule
                    .callAttr("read_legacy_model_secrets", storageRoot.absolutePath)
                    .toString()
                modelApiKeyStore.importLegacyJson(legacySecrets)
                serverModule.callAttr("purge_legacy_model_secrets", storageRoot.absolutePath)
                val port = serverModule
                    .callAttr("start", storageRoot.absolutePath, token, modelApiKeyStore.snapshotJson())
                    .toInt()
                val baseUrl = "http://127.0.0.1:$port"
                val api = apiFactory.create(baseUrl, token)
                awaitHealthy(api) {
                    serverModule.callAttr("startup_error").toString().trim()
                }
                activeApi = api
                mutableState.value = BackendState.Ready(baseUrl)
            } catch (error: Throwable) {
                activeApi = null
                mutableState.value = BackendState.Failed(readableMessage(error))
            }
        }
    }

    fun retry() {
        if (startJob?.isActive == true) return
        activeApi = null
        mutableState.value = BackendState.Idle
        start()
    }

    suspend fun requireApi(): ZaomengApi {
        start()
        val terminal = state.first { it is BackendState.Ready || it is BackendState.Failed }
        if (terminal is BackendState.Failed) {
            throw IllegalStateException(terminal.message)
        }
        return checkNotNull(activeApi) { "本地接口尚未就绪。" }
    }

    private suspend fun awaitHealthy(api: ZaomengApi, serverError: () -> String) {
        var lastError: Throwable? = null
        repeat(STARTUP_ATTEMPTS) {
            try {
                withTimeout(HEALTH_TIMEOUT_MS.milliseconds) { api.health() }
                return
            } catch (error: Throwable) {
                lastError = error
                val startupError = serverError()
                if (startupError.isNotEmpty()) {
                    throw IllegalStateException("手机内的 Python 服务启动失败：$startupError", error)
                }
                delay(STARTUP_RETRY_DELAY_MS.milliseconds)
            }
        }
        throw IllegalStateException("手机内的接口服务启动超时。", lastError)
    }

    private fun readableMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: error::class.java.simpleName

    private companion object {
        const val STARTUP_ATTEMPTS = 80
        const val STARTUP_RETRY_DELAY_MS = 250L
        const val HEALTH_TIMEOUT_MS = 1_500L
    }
}
