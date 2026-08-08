package top.wkbin.zaomeng.data.library

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
private data class OnlineLibraryIndex(
    val version: Int = 1,
    val books: List<OnlineLibraryBook> = emptyList(),
)

@Serializable
data class OnlineLibraryBook(
    val id: String,
    val title: String,
    @SerialName("created_by") val createdBy: String = "",
    val summary: String = "",
    val version: String = "",
    @SerialName("release_notes") val releaseNotes: String = "",
    @SerialName("download_url") val downloadUrl: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

class OnlineLibraryRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun listBooks(): List<OnlineLibraryBook> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(INDEX_URL)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("在线书卷包暂时无法访问：GitHub 返回 ${response.code}")
            val index = json.decodeFromString<OnlineLibraryIndex>(response.body?.string().orEmpty())
            index.books.filter { book ->
                isSafeBookId(book.id) && book.title.isNotBlank() &&
                    book.sha256.matches(SHA256_PATTERN) && isAllowedDownloadUrl(book.downloadUrl)
            }
        }
    }

    suspend fun downloadBook(
        book: OnlineLibraryBook,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ByteArray = withContext(Dispatchers.IO) {
        require(book.sha256.matches(SHA256_PATTERN)) { "书卷校验信息无效。" }
        require(isAllowedDownloadUrl(book.downloadUrl)) { "书卷下载地址不受信任。" }
        require(isSafeBookId(book.id)) { "书卷编号不受信任。" }
        val lock = downloadLocks.computeIfAbsent(book.id) { Mutex() }
        lock.withLock {
            downloadBookLocked(book, onProgress)
        }
    }

    private suspend fun downloadBookLocked(
        book: OnlineLibraryBook,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): ByteArray {
        val cacheDirectory = File(context.cacheDir, "online-packages").apply { mkdirs() }
        val partialFile = resolveDownloadFile(book.id, cacheDirectory)
        var existingBytes = partialFile.takeIf(File::exists)?.length() ?: 0L
        val requestBuilder = Request.Builder().url(book.downloadUrl).header("User-Agent", USER_AGENT)
        if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")
        val request = requestBuilder.build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("书卷下载失败：GitHub 返回 ${response.code}")
            if (existingBytes > 0 && response.code != 206) {
                partialFile.delete()
                existingBytes = 0
            }
            val contentLength = response.body?.contentLength() ?: -1L
            val totalBytes = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: contentLength.takeIf { it > 0 }?.plus(existingBytes)
                ?: book.sizeBytes
            if (totalBytes > MAX_PACKAGE_BYTES) error("书卷包过大，无法安全导入。")
            val body = response.body ?: error("书卷下载内容为空。")
            val digest = MessageDigest.getInstance("SHA-256")
            if (existingBytes > 0) partialFile.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            body.byteStream().use { input ->
                FileOutputStream(partialFile, existingBytes > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReportedBytes = existingBytes
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        val downloadedBytes = output.channel.size() + count
                        if (downloadedBytes > MAX_PACKAGE_BYTES) error("书卷包过大，无法安全导入。")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (downloadedBytes - lastReportedBytes >= PROGRESS_REPORT_INTERVAL_BYTES) {
                            lastReportedBytes = downloadedBytes
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                }
            }
            val downloaded = partialFile.readBytes()
            if (downloaded.isEmpty()) error("书卷下载内容为空。")
            onProgress(downloaded.size.toLong(), totalBytes.coerceAtLeast(downloaded.size.toLong()))
            val actualHash = digest.digest().joinToString("") {
                "%02x".format(Locale.ROOT, it.toInt() and 0xff)
            }
            if (!actualHash.equals(book.sha256, ignoreCase = true)) {
                partialFile.delete()
                error("书卷校验失败，请刷新书库后重试。")
            }
            partialFile.delete()
            downloaded
        }
    }

    private fun resolveDownloadFile(id: String, cacheDirectory: File): File {
        val candidate = File(cacheDirectory, "$id.part")
        val cacheRoot = runCatching { cacheDirectory.canonicalFile }.getOrElse { cacheDirectory.absoluteFile }
        val canonical = runCatching { candidate.canonicalFile }.getOrElse { candidate.absoluteFile }
        require(canonical.parentFile == cacheRoot) { "书卷文件路径不受信任。" }
        return candidate
    }

    companion object {
        private const val INDEX_URL = "https://raw.githubusercontent.com/wkbin/zaomeng-library/main/index.json"
        private const val USER_AGENT = "Zaomeng-Android"
        private const val MAX_PACKAGE_BYTES = 100L * 1024 * 1024
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 64L * 1024
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        private val SAFE_BOOK_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun isSafeBookId(id: String): Boolean = SAFE_BOOK_ID_PATTERN.matches(id)

        internal fun isAllowedDownloadUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps && parsed.host == "raw.githubusercontent.com" &&
                parsed.encodedPath.startsWith("/wkbin/zaomeng-library/")
        }
    }
}
