package top.wkbin.zaomeng.db

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 数据库升级策略回归：
 *
 * 1. v1（documents-only）已确认可以丢弃 → 允许破坏性重建，且重建后库可用；
 * 2. 同版本重开必须保留数据（破坏性回退不能波及正常打开）。
 *
 * 说明：第 1 条同时充当「只对 v1 登记了破坏性回退」的守门用例 —— 如果有人把这段配置改成
 * 纯 `addMigrations`（不再给 v1 兜底），打开这个 v1 库会抛 `IllegalStateException`，
 * 用例会失败。至于「是否被人改回全局 destructive 回退」无法从外部观测（那需要造一个
 * 未来的 schema 版本），只能靠 [buildZaomengDatabase] 上的注释约束。
 */
class ZaomengDatabaseUpgradePolicyTest {

    @Test
    fun `v1 database is destructively rebuilt and stays usable`() {
        val dbFile = tempDbFile()
        // 造一个 v1 形态的库：user_version = 1 + 一个只属于旧版的表
        writeRawDatabase(dbFile, userVersion = 1) { raw ->
            raw.exec("CREATE TABLE legacy_documents (path TEXT PRIMARY KEY, bytes BLOB)")
            raw.exec("INSERT INTO legacy_documents (path, bytes) VALUES ('old', 'x')")
        }

        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db)
        store.writeBytes(ROOT / "runs" / "run-1" / "run_manifest.json", "{}".encodeToByteArray(), 1L)

        assertEquals("{}", store.readBytes(ROOT / "runs" / "run-1" / "run_manifest.json")?.decodeToString())
        db.close()
    }

    @Test
    fun `reopening at the same version keeps documents`() {
        val dbFile = tempDbFile()
        val path = ROOT / "runs" / "run-2" / "run_manifest.json"

        newDatabase(dbFile).let { db ->
            RoomDocumentStore(db).writeBytes(path, "{\"run_id\":\"run-2\"}".encodeToByteArray(), 5L)
            db.close()
        }

        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db)
        assertEquals("{\"run_id\":\"run-2\"}", store.readBytes(path)?.decodeToString())
        assertEquals(5L, store.updatedAtMillis(path))
        db.close()
    }

    private fun newDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    /** 用同一个 SQLite 驱动写一个「版本可控」的库文件。 */
    private fun writeRawDatabase(
        file: File,
        userVersion: Int,
        block: (RawConnection) -> Unit,
    ) {
        val driver: SQLiteDriver = BundledSQLiteDriver()
        driver.open(file.absolutePath).use { connection ->
            val raw = RawConnection { sql -> connection.prepare(sql).apply { step() }.close() }
            block(raw)
            raw.exec("PRAGMA user_version = $userVersion")
        }
    }

    private class RawConnection(val exec: (String) -> Unit)

    private fun tempDbFile(): File =
        File.createTempFile("zaomeng-upgrade-", ".db").apply { deleteOnExit() }

    private companion object {
        val ROOT = "/tmp/zaomeng".toPath()
    }
}
