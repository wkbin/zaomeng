package top.wkbin.zaomeng.data.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

class KtorHealthClient(private val http: KtorHttpClientProvider) {
    suspend fun check(baseUrl: String) {
        val response = http.client.get("${baseUrl.trimEnd('/')}/api/web/health")
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Health check failed: ${response.status.value} ${response.bodyAsText()}")
        }
    }
}
