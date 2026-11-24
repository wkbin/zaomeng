package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class ChapterSearchPreviewTest {
    @Test
    fun `search preview slice stays aligned when lowercase changes text length`() {
        val dir = createTempDirectory("zaomeng-chapter-preview")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-preview"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            val chapterId = "chapter-preview"
            val file = storage.getChapterFile(runId, chapterId)
            storage.mkdirs(file.parent!!)
            // İ.lowercase() == "i̇" (two code points): lowercase-index no longer matches original.
            val content = "İ".repeat(150) + " 命中关键词 " + "后文".repeat(40)
            storage.writeTextAtomically(
                file,
                buildJsonObject {
                    put("chapter_id", chapterId)
                    put("title", "试章")
                    put("content", content)
                    put("order", 1)
                }.toString(),
            )
            val sessions = SessionManagementService(storage, DialogueService(storage))
            val chapters = ChapterManagementService(storage, sessions, llm = null, prompts = null)

            val matches = chapters.search(runId, "命中关键词", limit = 5)

            assertTrue(matches.isNotEmpty())
            val preview = matches.first().jsonObject.getValue("preview").jsonPrimitive.content
            assertTrue("命中关键词" in preview, "preview should contain the matched needle: $preview")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
