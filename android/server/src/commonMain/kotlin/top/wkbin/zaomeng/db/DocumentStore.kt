package top.wkbin.zaomeng.db

import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.platform.monotonicNanos
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.runBlockingPlatform

/**
 * 文档存储后端抽象：StorageService 的业务文件语义（文本/二进制、目录、递归删除、改名）。
 *
 * - [FileSystemDocumentStore]：原 JSON 文件行为（旧 :app 与测试兼容）。
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
}

/** 文件系统后端：保留原有 okio 文件行为（含临时文件 + 原子 rename 写入）。 */
class FileSystemDocumentStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
) : DocumentStore {
    override fun readBytes(path: Path): ByteArray? {
        if (!fs.exists(path)) return null
        return runCatching { fs.source(path).buffer().use { it.readByteArray() } }.getOrNull()
    }

    override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        runCatching { fs.createDirectories(path.parent!!) }
        val temp = path.parent!! / ".${path.name}.${monotonicNanos()}.tmp"
        fs.sink(temp).buffer().use { sink -> sink.write(bytes) }
        if (!replace(temp, path)) {
            runCatching { fs.delete(path) }
            if (!replace(temp, path)) {
                runCatching { fs.delete(temp) }
                throw IllegalStateException("Unable to replace ${path.name}")
            }
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
    private val dao: DocumentDao,
) : DocumentStore {
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

    override fun rename(source: Path, target: Path): Boolean = try {
        val sourceKey = key(source)
        val sourcePrefix = prefixOf(source)
        val moves: List<Pair<String, ByteArray>> = runBlockingPlatform {
            val self = dao.bytesOf(sourceKey)?.let { listOf(sourceKey to it) } ?: emptyList()
            val under = dao.pathsUnder(patternFor(sourcePrefix))
            self + under.map { it to (dao.bytesOf(it) ?: ByteArray(0)) }
        }
        if (moves.isEmpty()) return false
        val targetKey = key(target)
        val targetPrefix = prefixOf(target)
        val now = nowEpochMillis()
        runBlockingPlatform {
            moves.forEach { (from, bytes) ->
                val to = if (from == sourceKey) {
                    targetKey
                } else {
                    targetPrefix + from.removePrefix(sourcePrefix)
                }
                dao.upsert(DocumentEntity(to, bytes, now))
            }
            moves.forEach { (from, _) -> dao.delete(from) }
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun fileSize(path: Path): Long =
        runBlockingPlatform { dao.bytesOf(key(path))?.size?.toLong() ?: 0L }
}
