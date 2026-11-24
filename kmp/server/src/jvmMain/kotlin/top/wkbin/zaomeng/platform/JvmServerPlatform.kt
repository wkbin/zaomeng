package top.wkbin.zaomeng.platform

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import androidx.room3.RoomDatabase
import java.io.File
import java.util.Properties
import top.wkbin.zaomeng.db.ZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder

/** JVM 实现：数据目录/提示词优先从仓库根目录解析（开发/测试用），
 *  打包安装后自动落到用户可写目录，密钥走 [FileSecureKeyValueStore]（Windows DPAPI / macOS Keychain）。 */
class JvmServerPlatform(
    override val dataRoot: Path = defaultDataRoot(),
    override val promptSource: PromptSource = JvmPromptSource(),
) : ServerPlatform {
    private val store = FileSecureKeyValueStore(dataRoot / "secrets.properties")

    override fun secureStore(): SecureKeyValueStore = store

    override fun databaseBuilder(): RoomDatabase.Builder<ZaomengDatabase> =
        getDatabaseBuilder(dataRoot / "zaomeng.db")
}

internal fun defaultDataRoot(cwd: File = File(System.getProperty("user.dir"))): Path {
    val repoRoot = findRepoRoot(cwd)
    return if (repoRoot != null) {
        // 开发/测试：数据目录留在仓库根，复用已有 zaomeng-data
        File(repoRoot, "zaomeng-data").absolutePath.toPath()
    } else {
        // 打包安装（如 C:\Program Files\造梦）不可写：落到用户目录
        val home = System.getProperty("user.home").takeIf { it.isNotBlank() }
            ?: cwd.absolutePath
        File(home, ".zaomeng/server").absolutePath.toPath()
    }
}

internal fun findRepoRoot(cwd: File): File? = sequence {
    var dir: File? = cwd
    while (dir != null) {
        yield(dir)
        dir = dir.parentFile
    }
}.firstOrNull { File(it, "prompts").isDirectory }

/**
 * JVM 提示词读取：
 * 1. 仓库根 prompts/ 与 zaomeng-skill/（开发/测试）；
 * 2. 打包后回退到 classpath 内的 composeResources 提示词（随 shared jar 分发）。
 */
class JvmPromptSource(
    private val cwd: File = File(System.getProperty("user.dir")),
) : PromptSource {
    private val repoRoot: File? by lazy {
        findRepoRoot(cwd)
    }

    private fun resolveRepoFile(relativePath: String): File? {
        val root = repoRoot ?: return null
        val configFile = File(root, "prompts").resolve(relativePath)
        if (configFile.exists()) return configFile
        // zaomeng-skill 的蒸馏 md 按 skill 布局放在 prompts/ 与 references/ 子目录
        val skillName = relativePath.removePrefix("distill/")
        return listOf(
            File(root, "zaomeng-skill").resolve(skillName),
            File(root, "zaomeng-skill/prompts").resolve(skillName),
            File(root, "zaomeng-skill/references").resolve(skillName),
        ).firstOrNull { it.exists() }
    }

    private fun resourcePath(relativePath: String): String =
        "composeResources/zaomeng.app.shared.generated.resources/files/prompts/$relativePath"

    override fun read(relativePath: String): Pair<String, Long>? {
        resolveRepoFile(relativePath)?.let { return it.readText() to it.lastModified() }
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath(relativePath))
            ?: return null
        return stream.use { it.readBytes().decodeToString() } to 0L
    }

    override fun lastModified(relativePath: String): Long? {
        resolveRepoFile(relativePath)?.let { return it.lastModified() }
        return if (javaClass.classLoader.getResource(resourcePath(relativePath)) != null) 0L else null
    }
}

/**
 * JVM 安全存储：Windows 下每个值用 DPAPI 加密后落盘；macOS 下值进系统 Keychain、文件只留标记；
 * 其他系统退化为 0600 权限的明文 properties。
 *
 * 历史明文条目在首次构造时迁移，原文件保留为 `<name>.<millis>.legacy` 备份。
 */
class FileSecureKeyValueStore internal constructor(
    private val file: Path,
    private val codec: SecretCodec,
) : SecureKeyValueStore {
    constructor(file: Path) : this(file, DesktopSecretCodec.platformDefault())

    private val fs = okio.FileSystem.SYSTEM
    private val lock = Any()

    init {
        migrateLegacyPlaintext()
    }

    private fun load(): Map<String, String> {
        if (!fs.exists(file)) return emptyMap()
        return runCatching {
            val props = Properties()
            fs.source(file).buffer().asInputStream().use { props.load(it) }
            props.stringPropertyNames().associateWith { props.getProperty(it) }
        }.getOrDefault(emptyMap())
    }

    private fun save(map: Map<String, String>) {
        synchronized(lock) {
            runCatching { fs.createDirectories(file.parent!!) }
            val props = Properties()
            map.forEach { (k, v) -> props.setProperty(k, v) }
            fs.sink(file).buffer().asOutputStream().use { props.store(it, null) }
            runCatching { restrictSecretFilePermissions(secretFilePath(file.toString())) }
        }
    }

    override fun get(key: String): String? = synchronized(lock) {
        load()[key]?.let { codec.reveal(key, it) }
    }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            val next = load().toMutableMap().apply { put(key, codec.encode(key, value)) }
            save(next)
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val current = load()
            val stored = current[key] ?: return
            // 系统侧记录先删：即使文件写入随后失败，残留标记也只会按“没有密钥”读出。
            if (codec.isEncoded(stored)) codec.forget(key)
            save(current.toMutableMap().apply { remove(key) })
        }
    }

    override fun entries(): Map<String, String> = synchronized(lock) {
        load().mapNotNull { (key, stored) -> codec.reveal(key, stored)?.let { key to it } }.toMap()
    }

    /** 已存在明文条目时整体重写为受保护形式；备份文件只做保留，不自动删除。 */
    private fun migrateLegacyPlaintext() {
        if (!codec.canEncrypt) return
        val raw = load()
        // 带其他保护标记的值（从别的平台/账户复制过来）无法解出，也不是明文：
        // 必须原样保留并继续按“未配置密钥”读出，绝不能当明文重新加密。
        fun isMigratable(stored: String): Boolean = !codec.isEncoded(stored) && !SecretMarkers.isForeign(stored)
        val migratable = raw.filterValues { isMigratable(it) }
        if (migratable.isEmpty()) return
        // 先在系统侧完成写入：任一条目保护失败就保持原文件，避免出现“备份没了、明文还在”的中间态。
        val encoded = raw.mapValues { (key, value) -> if (isMigratable(value)) codec.encode(key, value) else value }
        if (migratable.keys.any { !codec.isEncoded(encoded.getValue(it)) }) {
            PlatformLog.w(TAG, "Skip secret migration: OS protection unavailable")
            // 迁移失败时不能留下半写入的系统侧记录。
            migratable.keys.forEach(codec::forget)
            return
        }
        synchronized(lock) {
            val backup = file.parent!! / "${file.name}.${nowEpochMillis()}.legacy"
            if (!writeLegacyBackup(backup)) {
                // 备份失败就不改写：宁可继续用明文文件，也不要丢掉回滚能力。
                // 系统侧已写入的条目同样回滚，保持与文件一致。
                migratable.keys.forEach(codec::forget)
                PlatformLog.w(TAG, "Skip secret migration: cannot write backup")
                return
            }
            // 备份里仍是明文，至少收紧到仅当前用户可读。
            runCatching { restrictSecretFilePermissions(secretFilePath(backup.toString())) }
            save(encoded)
            PlatformLog.i(TAG, "Migrated ${migratable.size} plaintext secret(s) to ${codec.protection}; backup=${backup.name}")
        }
    }

    /**
     * 复制原文件作为备份。
     *
     * 这里不用 `atomicMove`：它在部分磁盘/杀软/CI 临时目录上会失败，而备份是迁移的前置条件，
     * 失败被吞掉就会得到“已加密但没有备份”的结果。复制只要求读句柄，退路才是整文件移动。
     */
    private fun writeLegacyBackup(backup: Path): Boolean {
        if (runCatching { fs.copy(file, backup) }.isSuccess) return true
        return runCatching { fs.atomicMove(file, backup) }.isSuccess
    }

    private companion object {
        const val TAG = "FileSecureKeyValueStore"
    }
}

/** okio Source → java.io.InputStream（okio 3.x 已移除内置转换扩展）。 */
private fun okio.Source.asInputStream(): java.io.InputStream = object : java.io.InputStream() {
    private val buffer = okio.Buffer()

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = this@asInputStream.read(buffer, len.toLong())
        if (n == -1L) return -1
        buffer.read(b, off, n.toInt())
        return n.toInt()
    }
}

/** okio Sink → java.io.OutputStream（okio 3.x 已移除内置转换扩展）。 */
private fun okio.Sink.asOutputStream(): java.io.OutputStream = object : java.io.OutputStream() {
    private val buffer = okio.Buffer()

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
        this@asOutputStream.write(buffer, len.toLong())
    }

    override fun flush() {
        this@asOutputStream.flush()
    }

    override fun close() {
        // BufferedSink.close() 内部会先 flush 再关闭底层文件；
        // 若不显式转发，Properties.store() 的缓冲数据永远不会落盘。
        this@asOutputStream.close()
    }
}
