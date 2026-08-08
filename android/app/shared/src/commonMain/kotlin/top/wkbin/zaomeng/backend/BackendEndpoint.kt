package top.wkbin.zaomeng.backend

/** 内嵌 Ktor 后端端点（shared 版，旧 :app 的 BackendManager.BackendEndpoint 退役后统一）。 */
data class BackendEndpoint(val baseUrl: String)

/**
 * 内嵌后端端点提供者：Android 由 LocalBackendController（启动内嵌服务）实现，
 * 桌面由 desktopApp 直接指向本地端口。
 */
interface BackendEndpointProvider {
    /** 确保后端就绪并返回端点（Android 会先启动内嵌 Ktor 服务）。 */
    suspend fun requireKtorEndpoint(): BackendEndpoint

    /** 当前 API token（HttpClient defaultRequest 同步读取）。 */
    fun currentToken(): String
}
