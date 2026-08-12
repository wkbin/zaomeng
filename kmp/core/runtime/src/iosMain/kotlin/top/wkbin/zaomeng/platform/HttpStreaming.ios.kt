package top.wkbin.zaomeng.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readUTF8Line

actual fun createHttpClientEngine(): HttpClientEngine = Darwin.create()

private val streamingHttpClient by lazy {
    HttpClient(Darwin) { expectSuccess = false }
}

actual suspend fun openStreamingHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
): PlatformStreamingResponse {
    val requestHeaders = headers
    val response = streamingHttpClient.preparePost(url) {
        contentType(ContentType.Application.Json)
        headers {
            requestHeaders.forEach { (name, value) -> append(name, value) }
        }
        setBody(body)
    }.execute()
    return DarwinStreamingResponse(
        statusCode = response.status.value,
        statusDescription = response.status.description,
        channel = response.bodyAsChannel(),
    )
}

private class DarwinStreamingResponse(
    override val statusCode: Int,
    override val statusDescription: String,
    private val channel: ByteReadChannel,
) : PlatformStreamingResponse {
    override suspend fun readUtf8Line(): String? = channel.readUTF8Line()

    override suspend fun readRemainingText(): String = buildString {
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (isNotEmpty()) append('\n')
            append(line)
        }
    }

    override fun close() = channel.cancel()
}
