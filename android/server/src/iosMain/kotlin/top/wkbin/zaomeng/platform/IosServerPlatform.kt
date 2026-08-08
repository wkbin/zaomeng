package top.wkbin.zaomeng.platform

import androidx.room3.RoomDatabase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import top.wkbin.zaomeng.db.ZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder

/** iOS 平台：数据目录 = ApplicationSupport/zaomeng，提示词走主 bundle 资源，密钥为本地文件。 */
class IosServerPlatform : ServerPlatform {
    private val appSupportPath: String = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )?.path,
    ) { "NSApplicationSupportDirectory unavailable" }

    override val dataRoot: Path = (appSupportPath + "/zaomeng").toPath()

    override val promptSource: PromptSource = IosPromptSource()

    private val store = IosFileSecureStore(dataRoot / "secrets.properties")

    override fun secureStore(): SecureKeyValueStore = store

    override fun databaseBuilder(): RoomDatabase.Builder<ZaomengDatabase> =
        getDatabaseBuilder(dataRoot / "zaomeng.db")
}

/** iOS 提示词读取：主 bundle 资源目录（打包时把 prompts/ 放进 app resources）。 */
class IosPromptSource : PromptSource {
    override fun read(relativePath: String): Pair<String, Long>? {
        val resourceRoot = NSBundle.mainBundle.resourcePath ?: return null
        val file = resourceRoot + "/" + relativePath.trimStart('/')
        if (!NSFileManager.defaultManager.fileExistsAtPath(file)) return null
        return runCatching {
            FileSystem.SYSTEM.source(file.toPath()).buffer().use { it.readUtf8() } to 0L
        }.getOrNull()
    }
}

/** iOS 安全密钥存储：明文 properties 文件（与桌面一致；后续可升级 Keychain）。 */
class IosFileSecureStore(
    private val file: Path,
) : SecureKeyValueStore {
    private val fs = FileSystem.SYSTEM
    private val lock = Any()

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

    private fun load(): Map<String, String> {
        if (!fs.exists(file)) return emptyMap()
        return runCatching {
            fs.source(file).buffer().use { source ->
                buildMap {
                    source.readUtf8().lineSequence().forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
                        val index = line.indexOf('=')
                        if (index > 0) {
                            put(line.substring(0, index).trim(), line.substring(index + 1).trim())
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(map: Map<String, String>) {
        runCatching { fs.createDirectories(file.parent!!) }
        fs.sink(file).buffer().use { sink ->
            map.forEach { (key, value) -> sink.writeUtf8("$key=$value\n") }
        }
    }
}
