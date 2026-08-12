package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine

expect fun createHttpClientEngine(): HttpClientEngine

/** A response body that remains incremental instead of being buffered until EOF. */
interface PlatformStreamingResponse {
    val statusCode: Int
    val statusDescription: String

    suspend fun readUtf8Line(): String?
    suspend fun readRemainingText(): String
    fun close()
}

/** Open a POST request whose response body must be consumed as bytes arrive. */
expect suspend fun openStreamingHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
): PlatformStreamingResponse
