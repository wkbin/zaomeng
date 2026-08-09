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
 *  打包安装后自动落到用户可写目录，密钥为本地文件存储。 */
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

/** JVM 安全存储：properties 文件明文保存（开发/测试；Android 用 Keystore 加密）。 */
class FileSecureKeyValueStore(private val file: Path) : SecureKeyValueStore {
    private val fs = okio.FileSystem.SYSTEM
    private val lock = Any()

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
        }
    }

    override fun get(key: String): String? = synchronized(lock) { load()[key] }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            val next = load().toMutableMap().apply { put(key, value) }
            save(next)
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val next = load().toMutableMap().apply { remove(key) }
            save(next)
        }
    }

    override fun entries(): Map<String, String> = synchronized(lock) { load() }
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
