package top.wkbin.zaomeng.data.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.app.shared.AppMetadata

/**
 * 跨平台 GitHub Release 更新检查（Ktor）。
 *
 * 版本号三端统一（[AppMetadata.VERSION_NAME]）；平台差异只在“动作”：
 * Android 自动下载 APK 安装，桌面端用 [AppUpdateInfo.downloadUrl]（Release 页面）跳转手动下载。
 */
class ReleaseUpdateChecker(
    private val httpClient: HttpClient,
    private val currentVersion: String = AppMetadata.VERSION_NAME,
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

        /** 语义化版本比较（支持 v2.0.0 / 2.0.0 写法）。 */
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
