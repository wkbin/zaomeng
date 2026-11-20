package top.wkbin.zaomeng.platform

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpStreamingTest {
    @Test
    fun `first line is visible before the server finishes the response`() = runBlocking {
        val firstLineReceived = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stream") { exchange ->
            exchange.use {
                it.requestBody.readBytes()
                it.sendResponseHeaders(200, 0)
                it.responseBody.write("data: first\n".encodeToByteArray())
                it.responseBody.flush()
                if (firstLineReceived.await(5, TimeUnit.SECONDS)) {
                    it.responseBody.write("data: second\n".encodeToByteArray())
                }
            }
        }
        server.start()
        try {
            withStreamingHttpPost("http://127.0.0.1:${server.address.port}/stream", emptyMap(), "{}") { response ->
                assertEquals(200, response.statusCode)
                assertEquals("data: first", response.readUtf8Line())
                firstLineReceived.countDown()
                assertEquals("data: second", response.readUtf8Line())
            }
        } finally {
            firstLineReceived.countDown()
            server.stop(0)
        }
    }
}
