package top.wkbin.zaomeng.backend

/** 本地内嵌后端端点：三端统一指向 127.0.0.1:port。 */
class LocalBackendEndpointProvider(
    private val port: Int,
    private val token: String,
) : BackendEndpointProvider {
    override suspend fun requireKtorEndpoint(): BackendEndpoint =
        BackendEndpoint("http://127.0.0.1:$port")

    override fun currentToken(): String = token
}
