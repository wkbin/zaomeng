package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import androidx.room3.RoomDatabase
import kotlinx.coroutines.CoroutineDispatcher
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.Path
import top.wkbin.zaomeng.db.ZaomengDatabase
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * 平台差异抽象（expect/actual）。
 *
 * server 业务逻辑全部在 commonMain，仅有 JVM 系实现差异的 API 收拢到这里：
 * Android 用 Logcat / Keystore / assets，JVM 用 stdout / 本地存储 / 文件系统。
 */

/** 平台日志：Android 走 Logcat，JVM 走 stdout/stderr。 */
expect object PlatformLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** YAML 解析（snakeyaml 仅 JVM 系平台可用，隔离到 expect/actual；非 map 文档/解析失败返回 null）。 */
expect fun parseYaml(text: String): Map<String, Any?>?

/** YAML 序列化（block 风格，等价 snakeyaml DumperOptions.FlowStyle.BLOCK）。 */
expect fun dumpYaml(value: Any?): String

/** 随机 UUID（标准小写格式，等价 java.util.UUID.randomUUID().toString()）。 */
expect fun randomUuid(): String

/** Base64 编码（标准 RFC 4648，带 padding，无换行；okio 全平台实现）。 */
fun base64Encode(bytes: ByteArray): String = bytes.toByteString().base64()

/** Base64 解码（标准 RFC 4648）。 */
fun base64Decode(text: String): ByteArray =
    text.decodeBase64()?.toByteArray()
        ?: throw IllegalArgumentException("Invalid base64 input")

/** Zip 归档条目（目录条目 name 以 / 结尾）。 */
data class ZipEntryData(val name: String, val content: ByteArray)

/** 读取 zip 归档全部文件条目（跳过目录条目）。 */
expect fun readZipEntries(bytes: ByteArray): List<ZipEntryData>

/** 写入 zip 归档。 */
expect fun writeZipEntries(entries: List<ZipEntryData>): ByteArray

/** 磁盘空间信息（诊断用）。 */
data class DiskSpaceInfo(val freeBytes: Long, val totalBytes: Long)

/** 查询路径所在文件系统的剩余/总空间；失败返回 null。 */
expect fun diskSpaceOf(path: Path): DiskSpaceInfo?

/** JVM 系统属性（Android 返回近似值，用于诊断报告）。 */
expect fun systemProperty(name: String): String?

/** 平台 IO 调度器（Android/JVM 均为 Dispatchers.IO，保留下发语义）。 */
expect val platformIoDispatcher: CoroutineDispatcher

/** 平台 HTTP 客户端引擎（Android/JVM 均用 OkHttp）。 */
expect fun createHttpClientEngine(): HttpClientEngine

/** 平台阻塞桥接（JVM/Android 用 runBlocking；Room DAO 为 suspend，服务层保持同步语义）。 */
expect fun <T> runBlockingPlatform(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T

/** 服务器运行平台：数据根目录、提示词资源、安全密钥存储。 */
interface ServerPlatform {
    val dataRoot: Path
    val promptSource: PromptSource
    fun secureStore(): SecureKeyValueStore

    /** Room 数据库构建器（平台只提供路径/Context，驱动与协程上下文在公共代码统一配置）。 */
    fun databaseBuilder(): RoomDatabase.Builder<ZaomengDatabase>
}

/** 安全键值存储（Android Keystore 加密；JVM 本地实现）。 */
interface SecureKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun entries(): Map<String, String>
}

/** 提示词/蒸馏资源读取：assets 优先 + 文件系统回退（由平台实现）。 */
interface PromptSource {
    /** 返回原文与 mtime（打包 assets 固定 0）；缺失返回 null。 */
    fun read(relativePath: String): Pair<String, Long>?

    /**
     * 只探测资源的 mtime（不读内容），用于缓存命中判定。
     * 与 [read] 使用同一解析顺序；缺失返回 null。
     */
    fun lastModified(relativePath: String): Long?
}

/** 当前 UTC 时间的 ISO-8601 字符串（等价 Instant.now().toString()）。 */
@OptIn(ExperimentalTime::class)
fun nowIsoString(): String = Clock.System.now().toString()

/** 当前 epoch 毫秒（等价 System.currentTimeMillis()）。 */
@OptIn(ExperimentalTime::class)
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 单调时钟纳秒（等价 System.nanoTime()）。 */
fun monotonicNanos(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeNanoseconds

/** 字节数组小写十六进制（等价 "%02x".format(byte) 拼接）。 */
fun ByteArray.toHex(): String = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
