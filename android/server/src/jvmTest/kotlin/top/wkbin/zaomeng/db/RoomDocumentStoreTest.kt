package top.wkbin.zaomeng.db

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.services.StorageService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Room 文档存储行为测试：验证目录语义/递归删除/改名/StorageService 端到端。 */
class RoomDocumentStoreTest {
    private fun newDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    @Test
    fun `write read roundtrip with metadata`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db.documentDao())
        val path = dbFile.absolutePath.toPath() / "runs" / "run-1" / "run_manifest.json"

        store.writeBytes(path, """{"run_id":"run-1"}""".encodeToByteArray(), updatedAtMillis = 42L)

        assertEquals("""{"run_id":"run-1"}""", store.readBytes(path)?.decodeToString())
        assertEquals(42L, store.updatedAtMillis(path))
        assertEquals(18L, store.fileSize(path))
        assertTrue(store.isFile(path))
        assertTrue(store.exists(path))
        assertFalse(store.isDirectory(path))
        db.close()
    }

    @Test
    fun `directory semantics and one level listing`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db.documentDao())
        val root = dbFile.absolutePath.toPath()
        val runDir = root / "runs" / "run-a"
        val runManifest = runDir / "run_manifest.json"
        val chapter = runDir / "chapters" / "c1.json"

        store.writeBytes(runManifest, "{}".encodeToByteArray(), 1L)
        store.writeBytes(chapter, "{}".encodeToByteArray(), 2L)

        // 目录是隐式的
        assertTrue(store.isDirectory(runDir))
        assertTrue(store.isDirectory(runDir / "chapters"))
        assertTrue(store.exists(runDir))
        assertFalse(store.isFile(runDir))

        // 只列一层：runs 下只有 run-a，不出现 run-a/chapters
        val runsChildren = store.listFiles(root / "runs").map { it.name }
        assertEquals(listOf("run-a"), runsChildren)
        val runChildren = store.listFiles(runDir).map { it.name }.sorted()
        assertEquals(listOf("chapters", "run_manifest.json"), runChildren)
        db.close()
    }

    @Test
    fun `delete recursively removes subtree only`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db.documentDao())
        val root = dbFile.absolutePath.toPath()
        val keep = root / "runs" / "keep" / "run_manifest.json"
        val dropDir = root / "runs" / "drop"
        val dropFile = dropDir / "run_manifest.json"
        val dropChapter = dropDir / "chapters" / "c1.json"
        store.writeBytes(keep, "{}".encodeToByteArray(), 1L)
        store.writeBytes(dropFile, "{}".encodeToByteArray(), 2L)
        store.writeBytes(dropChapter, "{}".encodeToByteArray(), 3L)

        store.deleteRecursively(dropDir)

        assertFalse(store.exists(dropDir))
        assertFalse(store.exists(dropFile))
        assertFalse(store.exists(dropChapter))
        assertTrue(store.isFile(keep))
        db.close()
    }

    @Test
    fun `rename moves file and directory`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db.documentDao())
        val root = dbFile.absolutePath.toPath()
        val oldFile = root / "a.json"
        val newFile = root / "b.json"
        val oldDir = root / "old"
        val newDir = root / "new"
        store.writeBytes(oldFile, "file".encodeToByteArray(), 1L)
        store.writeBytes(oldDir / "x.json", "x".encodeToByteArray(), 2L)
        store.writeBytes(oldDir / "sub" / "y.json", "y".encodeToByteArray(), 3L)

        assertTrue(store.rename(oldFile, newFile))
        assertTrue(store.rename(oldDir, newDir))

        assertEquals("file", store.readBytes(newFile)?.decodeToString())
        assertFalse(store.exists(oldFile))
        assertEquals("x", store.readBytes(newDir / "x.json")?.decodeToString())
        assertEquals("y", store.readBytes(newDir / "sub" / "y.json")?.decodeToString())
        assertFalse(store.exists(oldDir))
        db.close()
    }

    @Test
    fun `like special characters in paths are handled`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val store = RoomDocumentStore(db.documentDao())
        val root = dbFile.absolutePath.toPath()
        val weirdDir = root / "runs" / "run_100%_x"
        val file = weirdDir / "a_b.json"
        store.writeBytes(file, "{}".encodeToByteArray(), 1L)

        assertTrue(store.exists(file))
        assertTrue(store.isDirectory(weirdDir))
        assertEquals(listOf("run_100%_x"), store.listFiles(root / "runs").map { it.name })
        store.deleteRecursively(weirdDir)
        assertFalse(store.exists(weirdDir))
        db.close()
    }

    @Test
    fun `storage service end to end over room backend`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val root = dbFile.absolutePath.toPath()
        val storage = StorageService(root, RoomDocumentStore(db.documentDao()))

        storage.writeRunManifest(
            "run-1",
            buildJsonObject { put("run_id", "run-1") },
        )
        assertEquals(listOf("run-1"), storage.listRunIds())
        assertNotNull(storage.readRunManifest("run-1"))

        val sessionDir = storage.getDialogueSessionsDirectory("run-1") / "dlg-1"
        storage.mkdirs(sessionDir)
        storage.writeTextAtomically(
            storage.getDialogueSessionManifestFile("run-1", "dlg-1"),
            """{"session_id":"dlg-1","transcript":[]}""",
        )
        assertEquals(listOf("dlg-1"), storage.listDialogueSessionIds("run-1"))
        assertEquals(
            "dlg-1",
            storage.getDialogueSession("run-1", "dlg-1")["session_id"]?.jsonPrimitive?.content,
        )

        // 会话删除：递归删掉会话目录后，会话清单消失
        storage.deleteRecursively(sessionDir)
        assertEquals(emptyList<String>(), storage.listDialogueSessionIds("run-1"))
        assertNull(runCatching { storage.loadSessionManifest("run-1", "dlg-1") }.getOrNull())

        // run 删除
        storage.deleteRecursively(storage.getRunDirectory("run-1"))
        assertEquals(emptyList<String>(), storage.listRunIds())
        db.close()
    }

    private fun tempDbFile(): File =
        File.createTempFile("zaomeng-room-test-", ".db").apply { deleteOnExit() }
}
