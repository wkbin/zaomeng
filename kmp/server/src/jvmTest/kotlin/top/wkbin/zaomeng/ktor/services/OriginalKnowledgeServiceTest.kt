package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginalKnowledgeServiceTest {
    @Test
    fun `manual boundary and pinned evidence survive rebuild`() {
        val dir = createTempDirectory("zaomeng-original-evidence")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-evidence"
            val runDir = storage.getRunDirectory(runId)
            storage.mkdirs(runDir)
            val source = runDir / "novel.txt"
            storage.writeTextAtomically(
                source,
                "甲把密信交给乙，低声说只有乙可以知道。\n后来甲在城中公开宣布新的法令。",
            )
            val manifest = buildJsonObject {
                put("run_id", runId)
                put("novel_path", source.toString())
            }
            val service = OriginalKnowledgeService(storage)
            val first = service.ensure(manifest, characterNames = listOf("甲", "乙"))
            val entryId = first["entries"]!!.jsonArray.first().jsonObject["entry_id"]!!.jsonPrimitive.content

            service.updateBoundary(runId, entryId, "private", listOf("乙"))
            service.updatePinned(runId, entryId, true)
            service.rebuild(manifest)

            val pinned = service.search(
                runManifest = manifest,
                query = "",
                participants = listOf("甲", "乙"),
                pinnedOnly = true,
                limit = 50,
            )
            assertEquals(1, pinned.size)
            assertEquals(entryId, pinned.single()["source_id"])
            assertEquals(true, pinned.single()["pinned"])
            assertEquals("private", pinned.single()["visibility"])
            assertEquals(listOf("乙"), pinned.single()["knowers"])
            assertTrue("乙" in (pinned.single()["allowed_characters"] as List<*>))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
