package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint

class KtorDiagnosticsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    suspend fun export(destination: Sink): Long {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/export") {
            }
        }
        val bytes: ByteArray = response.body()
        destination.buffer().use { it.write(bytes) }
        return bytes.size.toLong()
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}
