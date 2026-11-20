package top.wkbin.zaomeng.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * iOS SSE 流式读取：Darwin 引擎（NSURLSession delegate）支持分块下发，
 * 用 preparePost + bodyAsChannel 拿 ByteReadChannel，再桥接成 okio.BufferedSource。
 */
private val darwinStreamingClient: HttpClient by lazy {
    HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 0
            socketTimeoutMillis = 5 * 60 * 1000
        }
    }
}

actual fun openStreamingResponse(url: String, jsonBody: String, token: String): BufferedSource {
    val response = runBlocking {
        darwinStreamingClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
            header(HttpHeaders.Authorization, "Bearer $token")
        }.execute()
    }
    if (!response.status.isSuccess()) {
        val body = runBlocking { response.bodyAsText() }
        throw StreamingHttpException(
            response.status.value,
            "Streaming request failed: ${response.status}",
            body,
        )
    }
    val channel = runBlocking { response.bodyAsChannel() }
    return ByteReadChannelSource(channel).buffer()
}

/** 把 Ktor 的 ByteReadChannel 桥接为 okio.Source（阻塞读取，调用方应在后台线程使用）。 */
private class ByteReadChannelSource(
    private val channel: ByteReadChannel,
) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        val size = minOf(byteCount, 8192L).toInt()
        val bytes = ByteArray(size)
        // readAvailable 会挂起到有数据或结束为止；-1 表示流结束，0 表示本次没读到内容。
        val read = runBlocking { channel.readAvailable(bytes, 0, size) }
        if (read < 0) return -1L
        if (read == 0) return 0L
        sink.write(bytes, 0, read)
        return read.toLong()
    }

    /** 阻塞式读取，没有超时语义（由外层 HttpTimeout 管）。 */
    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        channel.cancel(null)
    }
}
