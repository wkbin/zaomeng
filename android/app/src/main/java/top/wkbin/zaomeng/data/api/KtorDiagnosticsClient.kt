package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.OutputStream
import top.wkbin.zaomeng.backend.BackendManager

class KtorDiagnosticsClient(
    private val http: KtorHttpClientProvider,
    private val backend: BackendManager,
) {
    suspend fun export(destination: OutputStream): Long {
        val response = request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/diagnostics/export") {
            }
        }
        val bytes: ByteArray = response.body()
        destination.write(bytes)
        return bytes.size.toLong()
    }

    private suspend fun request(block: suspend (BackendManager.BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(backend.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}
