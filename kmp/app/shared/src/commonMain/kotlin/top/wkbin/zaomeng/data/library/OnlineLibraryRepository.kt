package top.wkbin.zaomeng.data.library

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.platform.createHttpClientEngine

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

/**
 * 在线书库（GitHub index + 包下载）跨平台实现：
 * Ktor 客户端（引擎由平台提供）+ okio 文件流 + SHA-256 校验。
 */
class OnlineLibraryRepository(
    private val cacheDir: Path,
    private val engine: HttpClientEngine = createHttpClientEngine(),
) {
    private val httpClient = HttpClient(engine) { expectSuccess = false }
    private val fs = FileSystem.SYSTEM
    private val lockGuard = Mutex()
    private val downloadLocks = mutableMapOf<String, Mutex>()

    private suspend fun lockFor(id: String): Mutex = lockGuard.withLock {
        downloadLocks.getOrPut(id) { Mutex() }
    }

    suspend fun listBooks(): List<OnlineLibraryBook> = withContext(Dispatchers.IO) {
        val response = httpClient.get(INDEX_URL) {
            header("Accept", "application/json")
            header("User-Agent", USER_AGENT)
        }
        check(response.status.isSuccess()) { "在线书卷包暂时无法访问：GitHub 返回 ${response.status.value}" }
        val index = json.decodeFromString<OnlineLibraryIndex>(response.bodyAsText())
        index.books.filter { book ->
            isSafeBookId(book.id) && book.title.isNotBlank() &&
                book.sha256.matches(SHA256_PATTERN) && isAllowedDownloadUrl(book.downloadUrl)
        }
    }

    suspend fun downloadBook(
        book: OnlineLibraryBook,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ByteArray = withContext(Dispatchers.IO) {
        require(book.sha256.matches(SHA256_PATTERN)) { "书卷校验信息无效。" }
        require(isAllowedDownloadUrl(book.downloadUrl)) { "书卷下载地址不受信任。" }
        require(isSafeBookId(book.id)) { "书卷编号不受信任。" }
        lockFor(book.id).withLock {
            downloadBookLocked(book, onProgress)
        }
    }

    private suspend fun downloadBookLocked(
        book: OnlineLibraryBook,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): ByteArray {
        val cacheDirectory = cacheDir / "online-packages"
        fs.createDirectories(cacheDirectory)
        val partialFile = resolveDownloadFile(book.id, cacheDirectory)
        val existingBytes = if (fs.exists(partialFile)) fs.metadataOrNull(partialFile)?.size ?: 0L else 0L

        val response = httpClient.get(book.downloadUrl) {
            header("User-Agent", USER_AGENT)
            if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
        }
        if (!response.status.isSuccess()) error("书卷下载失败：GitHub 返回 ${response.status.value}")

        val resume = existingBytes > 0 && response.status.value == 206
        if (existingBytes > 0 && !resume) fs.delete(partialFile)

        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
        val totalBytes = response.headers[HttpHeaders.ContentRange]
            ?.substringAfterLast('/')?.toLongOrNull()
            ?: contentLength.takeIf { it > 0 }?.plus(if (resume) existingBytes else 0L)
            ?: book.sizeBytes
        if (totalBytes > MAX_PACKAGE_BYTES) error("书卷包过大，无法安全导入。")

        val channel = response.bodyAsChannel()
        val sink = fs.appendingSink(partialFile).buffer()
        var downloadedBytes = if (resume) existingBytes else 0L
        var lastReportedBytes = downloadedBytes
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (count > 0) {
                    downloadedBytes += count
                    if (downloadedBytes > MAX_PACKAGE_BYTES) error("书卷包过大，无法安全导入。")
                    sink.write(buffer, 0, count)
                    if (downloadedBytes - lastReportedBytes >= PROGRESS_REPORT_INTERVAL_BYTES) {
                        lastReportedBytes = downloadedBytes
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
            sink.flush()
        } finally {
            runCatching { sink.close() }
        }

        val downloaded = fs.source(partialFile).buffer().use { it.readByteArray() }
        if (downloaded.isEmpty()) error("书卷下载内容为空。")
        onProgress(downloaded.size.toLong(), totalBytes.coerceAtLeast(downloaded.size.toLong()))

        val actualHash = downloaded.toByteString().sha256().hex()
        if (!actualHash.equals(book.sha256, ignoreCase = true)) {
            fs.delete(partialFile)
            error("书卷校验失败，请刷新书库后重试。")
        }
        fs.delete(partialFile)
        return downloaded
    }

    private fun resolveDownloadFile(id: String, cacheDirectory: Path): Path {
        val candidate = cacheDirectory / "$id.part"
        require(candidate.parent == cacheDirectory) { "书卷文件路径不受信任。" }
        return candidate
    }

    companion object {
        private const val INDEX_URL = "https://raw.githubusercontent.com/wkbin/zaomeng-library/main/index.json"
        private const val USER_AGENT = "Zaomeng"
        private const val MAX_PACKAGE_BYTES = 100L * 1024 * 1024
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 64L * 1024
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        private val SAFE_BOOK_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val json = Json { ignoreUnknownKeys = true }

        internal fun isSafeBookId(id: String): Boolean = SAFE_BOOK_ID_PATTERN.matches(id)

        internal fun isAllowedDownloadUrl(url: String): Boolean {
            val parsed = runCatching { io.ktor.http.Url(url) }.getOrNull() ?: return false
            return parsed.protocol.name == "https" &&
                parsed.host == "raw.githubusercontent.com" &&
                parsed.encodedPath.startsWith("/wkbin/zaomeng-library/")
        }
    }
}
