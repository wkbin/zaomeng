package top.wkbin.zaomeng.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import top.wkbin.zaomeng.client.platform.ClientLog
import top.wkbin.zaomeng.platform.platformIoDispatcher

internal val repositoryJson: Json = Json { ignoreUnknownKeys = true }

internal suspend fun <T> repositoryRequest(block: suspend () -> T): T = withContext(platformIoDispatcher) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ApiRequestException) {
        throw error
    } catch (error: Throwable) {
        ClientLog.e("Repository", "Repository request failed", error)
        val readable = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "请求失败，请稍后重试。"
        throw ApiRequestException(readable, error)
    }
}

internal fun errorDetail(body: String?, status: Int): String {
    val detail = runCatching {
        repositoryJson.parseToJsonElement(body.orEmpty()).jsonObject["detail"]
    }.getOrNull()
    val message = when (detail) {
        is JsonPrimitive -> detail.contentOrNull.orEmpty()
        is JsonArray -> detail.mapNotNull { item ->
            val issue = item as? JsonObject ?: return@mapNotNull null
            val location = (issue["loc"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.dropWhile { it == "body" }
                ?.joinToString(".")
                .orEmpty()
            val issueMessage = (issue["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            when {
                issueMessage.isBlank() -> null
                location.isBlank() -> issueMessage
                else -> "$location: $issueMessage"
            }
        }.take(3).joinToString("；")
        is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull
            ?: (detail["detail"] as? JsonPrimitive)?.contentOrNull
            ?: ""
        else -> ""
    }
    return message.takeIf(String::isNotBlank) ?: "本地接口返回 HTTP $status。"
}

internal fun parseFilename(contentDisposition: String): String {
    val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition)?.groupValues?.getOrNull(1)
    if (!encoded.isNullOrBlank()) {
        return percentDecodeUtf8(encoded.replace("+", "%2B"))
    }
    return Regex("filename=\"?([^;\"]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition)?.groupValues?.getOrNull(1).orEmpty()
}

internal fun kindToSegment(kind: ReusableCardKind): String = when (kind) {
    ReusableCardKind.Scene -> "scene"
    ReusableCardKind.Self -> "self"
    ReusableCardKind.Opening -> "opening"
}

private fun percentDecodeUtf8(input: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char == '%' && index + 2 < input.length) {
            val high = input[index + 1].digitToIntOrNull(16)
            val low = input[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().let { bytes.addAll(it.toList()) }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

class ApiRequestException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause)

enum class ReusableCardKind {
    Scene,
    Self,
    Opening,
}
