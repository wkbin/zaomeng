package top.wkbin.zaomeng.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.wkbin.zaomeng.app.BuildConfig
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateInfo

/** Android 应用更新管理器：GitHub Release 检查 + 受信任下载 + FileProvider 安装。 */
class AppUpdateManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val updateDirectory = File(context.filesDir, UPDATE_DIRECTORY)

    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Zaomeng-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("检查更新失败：GitHub 返回 ${response.code}")
            parseLatestRelease(response.body.string(), BuildConfig.VERSION_NAME)
        }
    }

    suspend fun download(
        update: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): AppUpdateDownloadState = withContext(Dispatchers.IO) {
        val existing = refreshDownloadState(update)
        if (existing.status == AppUpdateDownloadStatus.Downloading || existing.status == AppUpdateDownloadStatus.Downloaded) {
            return@withContext existing
        }
        require(isAllowedUpdateDownloadUrl(update.downloadUrl)) { "更新包下载地址不受信任。" }
        updateDirectory.mkdirs()
        val finalFile = File(updateDirectory, "update-${update.version}.apk")
        val temporaryFile = File(updateDirectory, ".update-${update.version}.apk.part")
        finalFile.delete()
        temporaryFile.delete()
        try {
            val request = Request.Builder()
                .url(update.downloadUrl)
                .header("User-Agent", "Zaomeng-Android")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载更新失败：服务器返回 ${response.code}")
                val body = response.body
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: -1L
                if (totalBytes > MAX_UPDATE_BYTES) error("更新包过大，无法安全下载。")
                var downloadedBytes = 0L
                var lastReportedBytes = -1L
                fun reportProgress(force: Boolean = false) {
                    if (force || downloadedBytes - lastReportedBytes >= PROGRESS_UPDATE_BYTES) {
                        onProgress(downloadedBytes, totalBytes)
                        lastReportedBytes = downloadedBytes
                    }
                }
                reportProgress(force = true)
                body.byteStream().use { input ->
                    temporaryFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (downloadedBytes > MAX_UPDATE_BYTES) error("更新包过大，无法安全下载。")
                            reportProgress()
                        }
                    }
                }
                reportProgress(force = true)
                if (!temporaryFile.renameTo(finalFile)) error("下载更新失败：无法保存安装包")
                AppUpdatePreferences.rememberDownload(context, finalFile.absolutePath, update.version)
                AppUpdateDownloadState(
                    version = update.version,
                    localPath = finalFile.absolutePath,
                    status = AppUpdateDownloadStatus.Downloaded,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            temporaryFile.delete()
            throw cancelled
        } catch (_: Throwable) {
            temporaryFile.delete()
            finalFile.delete()
            AppUpdatePreferences.clearDownload(context)
            AppUpdateDownloadState(version = update.version, status = AppUpdateDownloadStatus.Failed)
        }
    }

    suspend fun refreshDownloadState(update: AppUpdateInfo): AppUpdateDownloadState = withContext(Dispatchers.IO) {
        val localPath = AppUpdatePreferences.downloadPath(context)
        if (localPath.isBlank() || AppUpdatePreferences.downloadVersion(context) != update.version) {
            return@withContext AppUpdateDownloadState(version = update.version)
        }
        val apkFile = File(localPath)
        if (
            !apkFile.isFile ||
            apkFile.length() <= 0L ||
            apkFile.length() > MAX_UPDATE_BYTES ||
            !isInsideUpdateDirectory(apkFile)
        ) {
            AppUpdatePreferences.clearDownload(context)
            return@withContext AppUpdateDownloadState(version = update.version)
        }
        AppUpdateDownloadState(
            version = update.version,
            localPath = localPath,
            status = AppUpdateDownloadStatus.Downloaded,
            downloadedBytes = apkFile.length(),
            totalBytes = apkFile.length(),
        )
    }

    fun installDownloadedUpdate(update: AppUpdateInfo): Boolean = runCatching {
        val path = AppUpdatePreferences.downloadPath(context)
        check(path.isNotBlank() && AppUpdatePreferences.downloadVersion(context) == update.version)
        val apkFile = File(path)
        check(apkFile.isFile && apkFile.length() > 0L && apkFile.length() <= MAX_UPDATE_BYTES)
        check(isInsideUpdateDirectory(apkFile))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.isSuccess

    private fun isInsideUpdateDirectory(file: File): Boolean {
        val root = runCatching { updateDirectory.canonicalFile }.getOrElse { updateDirectory.absoluteFile }
        val candidate = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        return candidate.parentFile == root
    }

    internal companion object {
        const val UPDATE_DIRECTORY = "app-update"
        const val RELEASE_URL = "https://api.github.com/repos/wkbin/zaomeng/releases/latest"
        const val MAX_UPDATE_BYTES = 256L * 1024L * 1024L
        const val PROGRESS_UPDATE_BYTES = 256L * 1024L

        internal fun isAllowedUpdateDownloadUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps &&
                parsed.host == "github.com" &&
                parsed.encodedPath.startsWith("/wkbin/zaomeng/releases/download/")
        }
    }
}

internal fun parseLatestRelease(payload: String, currentVersion: String): AppUpdateInfo? {
    val release = runCatching {
        Json.parseToJsonElement(payload).jsonObject
    }.getOrElse {
        throw IllegalArgumentException("无效的 GitHub release JSON", it)
    }
    val remoteVersion = release.stringValue("tag_name")
    if (!isNewerVersion(remoteVersion, currentVersion)) return null
    val assets = release.arrayValue("assets")
    val asset = assets.firstOrNull {
        it.stringValue("name").endsWith(".apk", true) && it.stringValue("name").contains("arm64-v8a", true)
    } ?: assets.firstOrNull {
        it.stringValue("name").endsWith(".apk", true)
    } ?: error("最新版本未提供 Android APK")
    val downloadUrl = asset.stringValue("browser_download_url")
    if (downloadUrl.isBlank() || !AppUpdateManager.isAllowedUpdateDownloadUrl(downloadUrl)) {
        error("更新包下载地址无效")
    }
    return AppUpdateInfo(
        version = remoteVersion.removePrefix("v"),
        downloadUrl = downloadUrl,
        fileName = asset.stringValue("name").ifBlank { "zaomeng-$remoteVersion.apk" },
        releaseNotes = release.stringValue("body"),
    )
}

internal fun isNewerVersion(remote: String, current: String): Boolean {
    fun components(value: String): List<Int>? {
        val normalized = value.trim().removePrefix("v").substringBefore('-')
        if (normalized.isBlank()) return null
        return normalized.split('.').map { it.toIntOrNull() ?: return null }
    }
    val remoteParts = components(remote) ?: return false
    val currentParts = components(current) ?: return false
    repeat(maxOf(remoteParts.size, currentParts.size)) { index ->
        val difference = remoteParts.getOrElse(index) { 0 }.compareTo(currentParts.getOrElse(index) { 0 })
        if (difference != 0) return difference > 0
    }
    return false
}

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.content?.trim().orEmpty()

private fun JsonObject.arrayValue(key: String): List<JsonObject> =
    this[key]?.jsonArray?.mapNotNull(JsonElement::asObjectOrNull).orEmpty()

private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
