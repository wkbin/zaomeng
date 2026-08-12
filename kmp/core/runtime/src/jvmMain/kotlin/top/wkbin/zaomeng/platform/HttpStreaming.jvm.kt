package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create()

private val streamingHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

actual suspend fun openStreamingHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
): PlatformStreamingResponse = withContext(Dispatchers.IO) {
    val requestBuilder = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
    headers.forEach { (name, value) -> requestBuilder.header(name, value) }
    OkHttpStreamingResponse(streamingHttpClient.newCall(requestBuilder.build()).execute())
}

private class OkHttpStreamingResponse(
    private val response: Response,
) : PlatformStreamingResponse {
    private val source = requireNotNull(response.body).source()

    override val statusCode: Int = response.code
    override val statusDescription: String = response.message

    override suspend fun readUtf8Line(): String? = withContext(Dispatchers.IO) {
        source.readUtf8Line()
    }

    override suspend fun readRemainingText(): String = withContext(Dispatchers.IO) {
        source.readUtf8()
    }

    override fun close() = response.close()
}
