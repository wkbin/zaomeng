package top.wkbin.zaomeng.ktor

import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import top.wkbin.zaomeng.ktor.services.StorageService

class StorageServiceTest {
    @Test
    fun `atomic write leaves complete content and no temporary files`() {
        val root = Files.createTempDirectory("zaomeng-storage-").toFile()
        try {
            val storage = StorageService(root)
            val target = root.resolve("nested/data.json")
            storage.writeTextAtomically(target, "{\"ok\":true}")
            assertEquals("{\"ok\":true}", target.readText())
            assertFalse(target.parentFile.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dialogue session manifest uses the sessions directory`() {
        val root = Files.createTempDirectory("zaomeng-storage-").toFile()
        try {
            val storage = StorageService(root)
            assertEquals(
                root.resolve("runs/run-1/dialogue/sessions/session-1/session_manifest.json").canonicalPath,
                storage.getDialogueSessionManifestFile("run-1", "session-1").canonicalPath
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `chapter listing preserves chapter metadata`() {
        val root = Files.createTempDirectory("zaomeng-storage-").toFile()
        try {
            val storage = StorageService(root)
            val chapter = storage.getChapterFile("run-1", "chapter-1")
            storage.writeTextAtomically(chapter, "{\"title\":\"Opening\",\"content\":\"Text\"}")
            assertEquals("Opening", storage.listChapters("run-1").single()["title"]?.toString()?.trim('"'))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `imported run manifest preserves structured progress and metadata`() {
        val root = Files.createTempDirectory("zaomeng-storage-").toFile()
        try {
            val storage = StorageService(root)
            val manifest = storage.getRunManifestPath("imported-run")
            storage.writeTextAtomically(
                manifest,
                """
                {
                  "run_id": "imported-run",
                  "status": "ready",
                  "progress": {"stage": "completed", "percent": 100.0},
                  "imported_from": {"package_filename": "demo.zaomeng-run.zip"}
                }
                """.trimIndent(),
            )

            val loaded = checkNotNull(storage.readRunManifest("imported-run"))
            assertEquals("completed", loaded["progress"]?.jsonObject?.get("stage")?.jsonPrimitive?.content)
            assertEquals(
                "demo.zaomeng-run.zip",
                loaded["imported_from"]?.jsonObject?.get("package_filename")?.jsonPrimitive?.content,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
