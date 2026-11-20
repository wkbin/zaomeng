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
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

actual fun createHttpClientEngine(): HttpClientEngine = Darwin.create()

private val streamingHttpClient by lazy {
    HttpClient(Darwin) { expectSuccess = false }
}

actual suspend fun <T> withStreamingHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
    consume: suspend (PlatformStreamingResponse) -> T,
): T {
    val requestHeaders = headers
    val callerDispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor])
    return streamingHttpClient.preparePost(url) {
        contentType(ContentType.Application.Json)
        headers {
            requestHeaders.forEach { (name, value) -> append(name, value) }
        }
        setBody(body)
    }.execute { response ->
        val stream = DarwinStreamingResponse(
            statusCode = response.status.value,
            statusDescription = response.status.description,
            channel = response.bodyAsChannel(),
        )
        // Ktor invokes this block on the engine dispatcher. Keep Flow emissions on the caller's dispatcher.
        withContext(callerDispatcher) { consume(stream) }
    }
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

}
