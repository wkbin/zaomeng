package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.FileSecureKeyValueStore
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DistillLifecycleTest {
    @Test
    fun `redistill persists requested limits and reselects an existing source`() = runBlocking {
        val directory = createTempDirectory("zaomeng-redistill-source")
        val root = directory.toString().toPath()
        val storage = StorageService(root)
        val keys = ModelApiKeyService(FileSecureKeyValueStore(root / "test.properties"))
        val client = LlmClient(keys, storage)
        val executor = DistillExecutor(storage, llm = client, promptLoader = null)
        val runId = "run-1"
        try {
            SettingsManagementService(storage, keys)
                .saveModelSettings("openai-compatible", "local", "http://127.0.0.1:1/v1", "test-key")
            val run = storage.getRunDirectory(runId)
            val original = run / "original.txt"
            val newer = run / "newer.txt"
            val text = "最初的原文。"
            storage.writeTextAtomically(original, text)
            storage.writeTextAtomically(newer, "后来的原文。")
            storage.writeRunManifest(runId, buildJsonObject {
                put("run_id", runId)
                put("max_sentences", 120)
                put("max_chars", 50_000)
                put("novel_sources", buildJsonArray {
                    add(buildJsonObject { put("source_path", original.toString()) })
                    add(buildJsonObject { put("source_path", newer.toString()) })
                })
            })
            val service = RunOperationsService(storage, RunManagementService(storage, executor), RunPackageService(storage), executor)
            val encoded = Base64.getEncoder().encodeToString(text.encodeToByteArray())
            assertFailsWith<IllegalArgumentException> {
                service.redistill(runId, listOf("角色"), "原文.txt", encoded, maxSentences = 19, maxChars = 8_000)
            }
            val updated = service.redistill(runId, listOf("角色"), "原文.txt", encoded, maxSentences = 35, maxChars = 8_000)

            assertEquals(35, updated.getValue("max_sentences").jsonPrimitive.int)
            assertEquals(8_000, updated.getValue("max_chars").jsonPrimitive.int)
            val sources = updated.getValue("novel_sources").jsonArray.map {
                it.jsonObject.getValue("source_path").jsonPrimitive.content
            }
            assertEquals(listOf(newer.toString(), original.toString()), sources)
            assertFalse(storage.exists(run / "redistill-sources"))
        } finally {
            executor.stopAndWait(runId, timeoutMillis = 0)
            client.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleting a run blocks competing starts until cleanup returns`() = runBlocking {
        val directory = createTempDirectory("zaomeng-distill-lifecycle")
        try {
            val storage = StorageService(directory.toString().toPath())
            val executor = DistillExecutor(storage, llm = null, promptLoader = null)
            val deleting = CompletableDeferred<Unit>()
            val finishDeletion = CompletableDeferred<Unit>()
            val deletion = async {
                executor.deleteAfterStopping("run-1") {
                    deleting.complete(Unit)
                    finishDeletion.await()
                    "deleted"
                }
            }
            try {
                deleting.await()
                assertFailsWith<IllegalArgumentException> {
                    executor.withRunLifecycle(" run-1 ") { error("Must reject before the manifest is changed") }
                }
                executor.start("run-1", listOf("角色"))
                assertFalse(executor.isRunning("run-1"))
                assertEquals("other run", executor.withRunLifecycle("run-2") { "other run" })
                finishDeletion.complete(Unit)
                assertEquals("deleted", deletion.await())
                assertEquals("admitted", executor.withRunLifecycle("run-1") { "admitted" })
            } finally {
                deletion.cancelAndJoin()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
