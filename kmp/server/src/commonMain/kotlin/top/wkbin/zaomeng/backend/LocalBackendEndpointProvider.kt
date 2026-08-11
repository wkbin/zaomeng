package top.wkbin.zaomeng.backend

/** 本地内嵌后端端点：随机端口模式下从控制器读取实际绑定端口。 */
class LocalBackendEndpointProvider(
    private val controller: LocalBackendController,
    private val token: String,
) : BackendEndpointProvider {
    override suspend fun requireKtorEndpoint(): BackendEndpoint {
        controller.ensureStarted()
        return BackendEndpoint("http://127.0.0.1:${controller.awaitPort()}")
    }

    override fun currentToken(): String = token
}
