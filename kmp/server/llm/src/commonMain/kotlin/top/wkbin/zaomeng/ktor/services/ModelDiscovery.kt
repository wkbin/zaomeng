package top.wkbin.zaomeng.ktor.services

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.data.api.DEFAULT_MODEL_REQUEST_TIMEOUT_SECONDS
import top.wkbin.zaomeng.data.api.MAX_MODEL_REQUEST_TIMEOUT_SECONDS

class ModelDiscoveryException(message: String) : RuntimeException(message)

/** 单次请求超时毫秒数：缺省/非法值回退默认值，并夹在允许区间内。 */
fun modelRequestTimeoutMillis(rawSeconds: String?): Long {
    val seconds = rawSeconds?.trim()?.toIntOrNull() ?: DEFAULT_MODEL_REQUEST_TIMEOUT_SECONDS
    return seconds.coerceIn(1, MAX_MODEL_REQUEST_TIMEOUT_SECONDS) * 1000L
}

fun modelDiscoveryEndpoint(baseUrl: String): Url {
    val base = baseUrl.trim().ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
    val address = Regex("https?://([^/?#]+)(/[^?#]*)?", RegexOption.IGNORE_CASE).matchEntire(base)
    require(address != null && '@' !in address.groupValues[1] && base.none(Char::isWhitespace)) {
        "请填写有效的 HTTP 或 HTTPS 接口地址。"
    }
    val endpoint = runCatching { Url("$base/models") }.getOrNull()
    require(endpoint != null && endpoint.host.isNotBlank()) { "请填写有效的 HTTP 或 HTTPS 接口地址。" }
    return endpoint
}

@Serializable
private data class ProviderModelList(val data: List<ProviderModel>)

@Serializable
private data class ProviderModel(val id: String)

internal fun parseDiscoveredModels(json: Json, body: String): List<String> =
    json.decodeFromString<ProviderModelList>(body).data
        .map { it.id.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
