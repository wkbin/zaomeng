package top.wkbin.zaomeng.data.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 跨平台 GitHub Release 更新检查。当前版本由应用层提供。 */
class ReleaseUpdateChecker(
    private val httpClient: HttpClient,
    private val currentVersion: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(): AppUpdateInfo? {
        val body = httpClient.get(RELEASE_URL).bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v").orEmpty()
        if (tag.isBlank() || !isNewerVersion(tag, currentVersion)) return null
        return AppUpdateInfo(
            version = tag,
            downloadUrl = root["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_PAGE,
            fileName = "",
            releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    companion object {
        const val RELEASE_URL = "https://api.github.com/repos/wkbin/zaomeng/releases/latest"
        const val RELEASES_PAGE = "https://github.com/wkbin/zaomeng/releases"

        fun isNewerVersion(remote: String, current: String): Boolean {
            val remoteParts = remote.split('.').mapNotNull { it.toIntOrNull() }
            val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
            for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
                val left = remoteParts.getOrElse(index) { 0 }
                val right = currentParts.getOrElse(index) { 0 }
                if (left != right) return left > right
            }
            return false
        }
    }
}
