package top.wkbin.zaomeng.db

import androidx.room3.withWriteTransaction
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.monotonicNanos
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.runBlockingPlatform
import kotlin.coroutines.cancellation.CancellationException

/**
 * 文档存储后端抽象：StorageService 的业务文件语义（文本/二进制、目录、递归删除、改名）。
 *
 * - [FileSystemDocumentStore]：原 JSON 文件行为（测试/开发用）。
 * - [RoomDocumentStore]：生产环境统一走 Room（SQLite）。
 */
interface DocumentStore {
    fun readBytes(path: Path): ByteArray?

    fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long)

    fun updatedAtMillis(path: Path): Long?

    /** 文件或目录是否存在。 */
    fun exists(path: Path): Boolean

    fun isFile(path: Path): Boolean

    fun isDirectory(path: Path): Boolean

    /** 列出直接子项（一层；返回完整子路径）。 */
    fun listFiles(path: Path): List<Path>

    fun mkdirs(path: Path)

    fun deleteFile(path: Path)

    fun deleteRecursively(path: Path)

    fun rename(source: Path, target: Path): Boolean

    fun fileSize(path: Path): Long

    fun fileVersion(path: Path): String? =
        if (isFile(path)) "${updatedAtMillis(path) ?: 0}-${fileSize(path)}" else null
}

/** 文件系统后端：保留原有 okio 文件行为（含临时文件 + 原子 rename 写入）。 */
class FileSystemDocumentStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
) : DocumentStore {
    private val documentLocks = Array(64) { SimpleLock() }

    private fun documentLock(path: Path): SimpleLock =
        documentLocks[(path.normalized().hashCode() and Int.MAX_VALUE) % documentLocks.size]

    override fun readBytes(path: Path): ByteArray? = documentLock(path).withLock {
        if (!fs.exists(path)) return@withLock null
        runCatching { fs.source(path).buffer().use { it.readByteArray() } }.getOrNull()
    }

    override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        val parent = requireNotNull(path.parent) { "Document path must have a parent: $path" }
        fs.createDirectories(parent)
        val temp = parent / ".${path.name}.${monotonicNanos()}.tmp"
        try {
            fs.sink(temp).buffer().use { sink -> sink.write(bytes) }
            // On Windows the target cannot be replaced while our reader keeps it open.
            documentLock(path).withLock { fs.atomicMove(temp, path) }
        } finally {
            runCatching { fs.delete(temp, mustExist = false) }
        }
    }

    private fun replace(source: Path, target: Path): Boolean = try {
        fs.atomicMove(source, target)
        true
    } catch (e: Exception) {
        false
    }

    override fun updatedAtMillis(path: Path): Long? = fs.metadataOrNull(path)?.lastModifiedAtMillis

    override fun exists(path: Path): Boolean = fs.exists(path)

    override fun isFile(path: Path): Boolean = fs.metadataOrNull(path)?.isRegularFile == true

    override fun isDirectory(path: Path): Boolean = fs.metadataOrNull(path)?.isDirectory == true

    override fun listFiles(path: Path): List<Path> =
        if (fs.exists(path)) fs.list(path) else emptyList()

    override fun mkdirs(path: Path) {
        fs.createDirectories(path)
    }

    override fun deleteFile(path: Path) {
        runCatching { fs.delete(path) }
    }

    override fun deleteRecursively(path: Path) {
        runCatching { fs.deleteRecursively(path) }
    }

    override fun rename(source: Path, target: Path): Boolean = try {
        fs.atomicMove(source, target)
        true
    } catch (e: Exception) {
        false
    }

    override fun fileSize(path: Path): Long = fs.metadataOrNull(path)?.size ?: 0L
}

/**
 * Room 后端：文档行按虚拟路径存取。
 *
 * Room DAO 是 suspend，服务层保持同步文件语义，这里用平台 runBlocking 桥接
 * （SQLite 操作在 Room 的 IO 执行器上跑，调用线程阻塞等待，等价于原同步文件 IO）。
 */
class RoomDocumentStore(
    private val db: ZaomengDatabase,
) : DocumentStore {
    private val dao: DocumentDao = db.documentDao()

    /** 统一用 / 作路径分隔符（Windows 的 okio Path toString 是 \，前缀匹配需要一致）。 */
    private fun key(path: Path): String = path.toString().replace('\\', '/')

    private fun prefixOf(path: Path): String = key(path) + "/"

    /** LIKE 前缀 pattern：转义 %、_ 与转义符本身。 */
    private fun patternFor(prefix: String): String =
        prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"

    override fun readBytes(path: Path): ByteArray? =
        runBlockingPlatform { dao.bytesOf(key(path)) }

    override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        runBlockingPlatform { dao.upsert(DocumentEntity(key(path), bytes, updatedAtMillis)) }
    }

    override fun updatedAtMillis(path: Path): Long? =
        runBlockingPlatform { dao.updatedAtOf(key(path)) }

    override fun exists(path: Path): Boolean {
        val k = key(path)
        return runBlockingPlatform { dao.exists(k) || dao.existsUnder(patternFor(prefixOf(path))) }
    }

    override fun isFile(path: Path): Boolean =
        runBlockingPlatform { dao.exists(key(path)) }

    override fun isDirectory(path: Path): Boolean =
        runBlockingPlatform { dao.existsUnder(patternFor(prefixOf(path))) }

    override fun listFiles(path: Path): List<Path> {
        val prefix = prefixOf(path)
        val keys = runBlockingPlatform { dao.pathsUnder(patternFor(prefix)) }
        return keys.mapNotNull { k ->
            val rest = k.removePrefix(prefix)
            val segment = rest.substringBefore('/')
            if (segment.isEmpty()) null else path / segment
        }.distinct().sortedBy { it.toString() }
    }

    override fun mkdirs(path: Path) {
        // 目录隐式存在，无需落行
    }

    override fun deleteFile(path: Path) {
        runBlockingPlatform { dao.delete(key(path)) }
    }

    override fun deleteRecursively(path: Path) {
        runBlockingPlatform {
            dao.delete(key(path))
            dao.deleteUnder(patternFor(prefixOf(path)))
        }
    }

    /**
     * 单事务内完成「列举源行 → 读源字节 → 写目标行 → 删源行」。
     *
     * - 写入与删除原本是两个独立事务，中途失败会留下新旧两份数据；现在整体包在
     *   [withWriteTransaction] 里，失败由 SQLite 回滚，不存在半搬移状态。
     * - 源行读不到时**跳过**而不是写 [ByteArray] 空字节：空字节会把「行已消失」伪装成
     *   长度为 0 的合法文件，比直接丢这一条更难排查。
     * - 事务失败对调用方仍通过 `false` 表达（`StorageService.rename` 的契约只返回布尔），
     *   但会记一条截断日志，避免静默。
     */
    override fun rename(source: Path, target: Path): Boolean {
        val sourceKey = key(source)
        val sourcePrefix = prefixOf(source)
        val targetKey = key(target)
        val targetPrefix = prefixOf(target)
        return runBlockingPlatform {
            try {
                db.withWriteTransaction {
                    val moves = mutableListOf<Pair<String, ByteArray>>()
                    dao.bytesOf(sourceKey)?.let { moves += sourceKey to it }
                    for (child in dao.pathsUnder(patternFor(sourcePrefix))) {
                        dao.bytesOf(child)?.let { moves += child to it }
                    }
                    if (moves.isEmpty()) {
                        // 源不存在：不写任何行，返回 false（与文件系统后端的 atomicMove 一致）。
                        return@withWriteTransaction false
                    }
                    val now = nowEpochMillis()
                    for ((from, bytes) in moves) {
                        val to = if (from == sourceKey) {
                            targetKey
                        } else {
                            targetPrefix + from.removePrefix(sourcePrefix)
                        }
                        dao.upsert(DocumentEntity(to, bytes, now))
                    }
                    for ((from, _) in moves) dao.delete(from)
                    true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PlatformLog.w(TAG, "document rename failed, rolled back: $source -> $target", error)
                false
            }
        }
    }

    override fun fileSize(path: Path): Long =
        runBlockingPlatform { dao.fileSizeOf(key(path)) ?: 0L }

    override fun fileVersion(path: Path): String? =
        runBlockingPlatform { dao.fileVersionOf(key(path)) }

    private companion object {
        const val TAG = "RoomDocumentStore"
    }
}
