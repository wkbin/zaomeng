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

/** JVM 实现：数据目录/提示词从仓库根目录解析（开发/测试用），密钥为本地文件存储。 */
class JvmServerPlatform(
    override val dataRoot: Path = defaultDataRoot(),
    override val promptSource: PromptSource = JvmPromptSource(),
) : ServerPlatform {
    private val store = FileSecureKeyValueStore(dataRoot / "secrets.properties")

    override fun secureStore(): SecureKeyValueStore = store

    override fun databaseBuilder(): RoomDatabase.Builder<ZaomengDatabase> =
        getDatabaseBuilder(dataRoot / "zaomeng.db")
}

private fun defaultDataRoot(): Path {
    val repoRoot = File(System.getProperty("user.dir")).let { cwd ->
        sequence {
            var dir: File? = cwd
            while (dir != null) {
                yield(dir)
                dir = dir.parentFile
            }
        }.firstOrNull { File(it, "prompts").isDirectory }
            ?: cwd
    }
    return File(repoRoot, "zaomeng-data").absolutePath.toPath()
}

/** JVM 提示词读取：仓库根 prompts/ 与 zaomeng-skill/（无 assets）。 */
class JvmPromptSource : PromptSource {
    private val repoRoot: File by lazy {
        File(System.getProperty("user.dir")).let { cwd ->
            sequence {
                var dir: File? = cwd
                while (dir != null) {
                    yield(dir)
                    dir = dir.parentFile
                }
            }.firstOrNull { File(it, "prompts").isDirectory }
                ?: throw IllegalStateException("Could not locate prompts directory")
        }
    }

    override fun read(relativePath: String): Pair<String, Long>? {
        val configFile = File(repoRoot, "prompts").resolve(relativePath)
        if (configFile.exists()) {
            return configFile.readText() to configFile.lastModified()
        }
        // zaomeng-skill 的蒸馏 md 按 skill 布局放在 prompts/ 与 references/ 子目录
        val skillName = relativePath.removePrefix("distill/")
        val skillFile = listOf(
            File(repoRoot, "zaomeng-skill").resolve(skillName),
            File(repoRoot, "zaomeng-skill/prompts").resolve(skillName),
            File(repoRoot, "zaomeng-skill/references").resolve(skillName),
        ).firstOrNull { it.exists() }
        if (skillFile != null) {
            return skillFile.readText() to skillFile.lastModified()
        }
        return null
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
