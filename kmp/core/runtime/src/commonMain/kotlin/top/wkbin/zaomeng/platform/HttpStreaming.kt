package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine

expect fun createHttpClientEngine(): HttpClientEngine

/** A response body that remains incremental instead of being buffered until EOF. */
interface PlatformStreamingResponse {
    val statusCode: Int
    val statusDescription: String

    suspend fun readUtf8Line(): String?
    suspend fun readRemainingText(): String
}

/** Consume the POST response incrementally within the lifetime of the request. */
expect suspend fun <T> withStreamingHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
    consume: suspend (PlatformStreamingResponse) -> T,
): T
