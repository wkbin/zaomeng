package top.wkbin.zaomeng.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.contentType
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.Source
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
    check(response.status.isSuccess()) { "Streaming request failed: ${response.status}" }
    val channel = runBlocking { response.bodyAsChannel() }
    return ByteReadChannelSource(channel).buffer()
}

/** 把 Ktor 的 ByteReadChannel 桥接为 okio.Source（阻塞读取，调用方应在后台线程使用）。 */
private class ByteReadChannelSource(
    private val channel: ByteReadChannel,
) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (channel.isClosedForRead && channel.availableForRead == 0) return -1L
        val size = minOf(byteCount, 8192L).toInt()
        val bytes = ByteArray(size)
        val read = runBlocking { channel.readAvailable(bytes, 0, size) }
        if (read < 0) return -1L
        if (read == 0) return if (channel.isClosedForRead) -1L else 0L
        sink.write(bytes, 0, read)
        return read.toLong()
    }

    override fun close() {
        channel.cancel()
    }
}
