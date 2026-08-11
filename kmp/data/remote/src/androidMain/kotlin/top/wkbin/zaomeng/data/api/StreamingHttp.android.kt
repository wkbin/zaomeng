package top.wkbin.zaomeng.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val streamingClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

actual fun openStreamingResponse(url: String, jsonBody: String, token: String): okio.BufferedSource {
    val request = Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("Authorization", "Bearer $token")
        .build()
    val response = streamingClient.newCall(request).execute()
    check(response.isSuccessful) { "Streaming request failed: ${response.code} ${response.message}" }
    return response.body.source()
}
