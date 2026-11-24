package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.db.DocumentStore
import top.wkbin.zaomeng.db.FileSystemDocumentStore
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 回归：创建会话后 manifest 必须写全字段（title/mode 等默认值不能省略）。 */
class SessionManifestTest {
    @Test
    fun `session deletion waits for a receipt write and rejects later writes`() = runBlocking {
        val dir = createTempDirectory("zaomeng-session-delete")
        val receiptWrite = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        try {
            val files = FileSystemDocumentStore()
            val documents = object : DocumentStore by files {
                override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
                    if (path.name == "receipt.json") {
                        receiptWrite.complete(Unit)
                        check(releaseWrite.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the receipt write" }
                    }
                    files.writeBytes(path, bytes, updatedAtMillis)
                }
            }
            val storage = StorageService(dir.toString().toPath(), documents)
            val runId = "run-1"
            val sessionId = "session-1"
            val manifest = storage.getDialogueSessionManifestFile(runId, sessionId)
            storage.writeTextAtomically(manifest, """{"session_id":"$sessionId","transcript":[]}""")
            val operations = DialogueReplyOperationCoordinator(storage)
            val dialogue = DialogueService(storage)
            val request = ReplyOperationRequest(runId, sessionId, "turn-1", "继续。", "dialogue")
            val pending = async(Dispatchers.IO) { operations.begin(request) }
            try {
                withTimeout(5_000) { receiptWrite.await() }
                val deletionStarted = CompletableDeferred<Unit>()
                val deletion = async(Dispatchers.IO) {
                    deletionStarted.complete(Unit)
                    dialogue.deleteSessionData(runId, sessionId)
                }
                withTimeout(5_000) { deletionStarted.await() }
                assertNull(withTimeoutOrNull(100) { deletion.await() })
                releaseWrite.countDown()
                val acquired = pending.await() as ReplyOperationBegin.Active
                try {
                    assertTrue(deletion.await())
                    assertNull(operations.recoverFailure(acquired.lease, IllegalStateException("Late reply")))
                } finally {
                    operations.release(acquired.lease)
                }
                assertFalse(storage.exists(requireNotNull(manifest.parent)))
                assertFailsWith<NoSuchElementException> { operations.begin(request) }
                assertFailsWith<NoSuchElementException> {
                    storage.writeExistingSessionText(runId, sessionId, requireNotNull(manifest.parent) / "turns/late.json", "{}")
                }
                assertFalse(storage.exists(requireNotNull(manifest.parent)))
            } finally {
                releaseWrite.countDown()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session deletion waits for in flight derived memory`() = runBlocking {
        val dir = createTempDirectory("zaomeng-session-derived-delete")
        val memoryWrite = CompletableDeferred<Unit>()
        val releaseWrite = CountDownLatch(1)
        try {
            val files = FileSystemDocumentStore()
            val documents = object : DocumentStore by files {
                override fun isFile(path: Path): Boolean {
                    if (path.name == "long_term_memory.json") {
                        memoryWrite.complete(Unit)
                        check(releaseWrite.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release the memory worker" }
                    }
                    return files.isFile(path)
                }
            }
            val storage = StorageService(dir.toString().toPath(), documents)
            val runId = "run-1"
            val sessionId = "session-1"
            val manifest = storage.getDialogueSessionManifestFile(runId, sessionId)
            storage.writeTextAtomically(
                manifest,
                """{"run_id":"$runId","session_id":"$sessionId","mode":"observe","participants":["林夕"],"transcript":[]}""",
            )
            val dialogue = DialogueService(storage)
            dialogue.commitTurn(
                runId = runId,
                sessionId = sessionId,
                turnId = "turn-1",
                message = "我们在旧车站见。",
                messageKind = "dialogue",
                inputSpeaker = "我",
                responses = listOf(DialogueResponse("林夕", "我会带钥匙。")),
            )
            withTimeout(5_000) { memoryWrite.await() }
            val deletion = async { dialogue.deleteSessionData(runId, sessionId) }
            try {
                assertNull(withTimeoutOrNull(100) { deletion.await() })
                releaseWrite.countDown()
                assertTrue(withTimeout(5_000) { deletion.await() })
                assertFalse(storage.exists(requireNotNull(manifest.parent)))
            } finally {
                releaseWrite.countDown()
            }
        } finally {
            releaseWrite.countDown()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `chapter search reads archived text within its budget and skips later chunks`() {
        val dir = createTempDirectory("zaomeng-chapter-search")
        try {
            val root = dir.toString().toPath()
            val reads = mutableListOf<String>()
            val files = FileSystemDocumentStore()
            val store = object : DocumentStore by files {
                override fun readBytes(path: Path): ByteArray? {
                    reads += path.name
                    return files.readBytes(path)
                }
            }
            val storage = StorageService(root, store)
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                """{"session_id":"$sessionId","transcript_start":2,"transcript":[]}""",
            )
            val archive = storage.getDialogueSessionsDirectory(runId) / "$sessionId/transcript-archive"
            val message = "归档线索" + "文".repeat(200_000)
            storage.writeTextAtomically(archive / "0000000000.json", """[{"message":"$message"}]""")
            storage.writeTextAtomically(archive / "0000000001.json", """[{"message":"后续片段"}]""")
            val sessions = SessionManagementService(storage, DialogueService(storage))
            val chapters = ChapterManagementService(storage, sessions, llm = null, prompts = null)

            val matches = chapters.search(runId, "归档线索", limit = 5)
            assertEquals(sessionId, matches.single().jsonObject.getValue("session_id").jsonPrimitive.content)
            assertTrue("0000000000.json" in reads)
            assertTrue("0000000001.json" !in reads)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `created session manifest contains title and mode`() {
        val dir = createTempDirectory("zaomeng-session-repro")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1"); put("title", "测试") })
            val service = SessionManagementService(storage, DialogueService(storage))

            val created = service.createDialogueSession(
                runId = "run-1",
                mode = "observe",
                participants = listOf("角色甲"),
            )
            val sessionId = created["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val loaded = storage.loadSessionManifest("run-1", sessionId)
            assertTrue(loaded.containsKey("title"), "manifest 缺少 title")
            assertTrue(loaded.containsKey("mode"), "manifest 缺少 mode")
            assertTrue(loaded.containsKey("status"), "manifest 缺少 status")
            assertTrue(loaded.containsKey("turn_count"), "manifest 缺少 turn_count")
            assertEquals("", loaded["title"]?.jsonPrimitive?.contentOrNull)
            assertEquals("observe", loaded["mode"]?.jsonPrimitive?.contentOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `insert session ignores controlled character from stale clients`() {
        val dir = createTempDirectory("zaomeng-insert-session")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1"); put("title", "测试") })
            val service = SessionManagementService(storage, DialogueService(storage))

            val created = service.createDialogueSession(
                runId = "run-1",
                mode = "insert",
                participants = listOf("潘金莲"),
                controlledCharacter = "潘金莲",
            )
            val sessionId = created["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val loaded = storage.loadSessionManifest("run-1", sessionId)
            assertEquals("", loaded["controlled_character"]?.jsonPrimitive?.contentOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fork counts only the retained transcript`() {
        val dir = createTempDirectory("zaomeng-session-fork")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            val manifest = buildJsonObject {
                put("session_id", sessionId)
                put("run_id", runId)
                put("transcript_count", 125)
                put("transcript", buildJsonArray {
                    for (index in 1..125) {
                        add(buildJsonObject {
                            put("turn_id", "turn-$index")
                            put("role", "character")
                            put("speaker", "角色")
                            put("message", "消息$index")
                        })
                    }
                })
            }
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                manifest.toString(),
            )

            val service = DialogueAdvancedService(storage, llm = null, prompts = null)
            val branch = service.branchFromTurn(runId, sessionId, "turn-121")
            val branchId = branch.getValue("session_id").jsonPrimitive.content

            assertEquals(121, branch["transcript_count"]?.jsonPrimitive?.intOrNull)
            assertEquals(121, branch["transcript"]?.jsonArray?.size)
            val origin = branch.getValue("branch_origin").jsonObject
            assertEquals(sessionId, origin.getValue("session_id").jsonPrimitive.content)
            assertEquals("event_timeline", origin.getValue("kind").jsonPrimitive.content)
            assertEquals("turn-121", origin.getValue("turn_id").jsonPrimitive.content)
            assertEquals(80, storage.loadSessionManifest(runId, branchId)["transcript"]?.jsonArray?.size)
            assertEquals(121, storage.getDialogueSession(runId, branchId)["transcript"]?.jsonArray?.size)
            assertEquals(125, storage.loadSessionManifest(runId, sessionId)["transcript_count"]?.jsonPrimitive?.intOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `branch from scene falls back to the first message of each turn`() {
        val dir = createTempDirectory("zaomeng-scene-branch")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            val manifest = buildJsonObject {
                put("session_id", sessionId)
                put("run_id", runId)
                put("revision", 1)
                put("transcript_start", 0)
                put("transcript", buildJsonArray {
                    listOf("turn-1" to "user", "turn-1" to "character", "turn-2" to "user", "turn-2" to "character")
                        .forEachIndexed { index, (turnId, role) ->
                            add(buildJsonObject {
                                put("turn_id", turnId)
                                put("role", role)
                                put("speaker", if (role == "user") "旅人" else "角色")
                                put("message", "消息$index")
                            })
                        }
                })
            }
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                manifest.toString(),
            )

            val service = DialogueAdvancedService(storage, llm = null, prompts = null)
            val branch = service.branchFromScene(runId, sessionId, sceneIndex = 1)

            val kept = branch.getValue("transcript").jsonArray
            assertEquals(2, kept.size)
            assertEquals(
                listOf("turn-1", "turn-1"),
                kept.map { it.jsonObject.getValue("turn_id").jsonPrimitive.content },
            )
            val origin = branch.getValue("branch_origin").jsonObject
            assertEquals(sessionId, origin.getValue("session_id").jsonPrimitive.content)
            assertEquals("scene_timeline", origin.getValue("kind").jsonPrimitive.content)
            assertEquals(1, origin.getValue("scene_index").jsonPrimitive.intOrNull)
            assertEquals(
                "turn-1",
                origin.getValue("turn_id").jsonPrimitive.content,
                "客户端按 scene_index 恢复分支时还会用到 turn_id",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `branch turn count counts turns instead of messages`() {
        val dir = createTempDirectory("zaomeng-branch-turn-count")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            val manifest = buildJsonObject {
                put("session_id", sessionId)
                put("run_id", runId)
                put("revision", 2)
                put("transcript_start", 0)
                put("transcript", buildJsonArray {
                    listOf("turn-1", "turn-2").forEach { turnId ->
                        add(buildJsonObject {
                            put("turn_id", turnId)
                            put("role", "user")
                            put("speaker", "旅人")
                            put("message", "$turnId 的输入")
                        })
                        repeat(2) { index ->
                            add(buildJsonObject {
                                put("turn_id", turnId)
                                put("role", "character")
                                put("speaker", "角色$index")
                                put("message", "$turnId 的回复$index")
                            })
                        }
                    }
                })
            }
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                manifest.toString(),
            )

            val service = DialogueAdvancedService(storage, llm = null, prompts = null)
            val branch = service.branchFromTurn(runId, sessionId, "turn-2")

            assertEquals(6, branch.getValue("transcript").jsonArray.size)
            assertEquals(2, branch["turn_count"]?.jsonPrimitive?.intOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `large transcript is archived while full session remains materialized`() {
        val dir = createTempDirectory("zaomeng-session-archive")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            val base = buildJsonObject {
                put("run_id", runId)
                put("session_id", sessionId)
                put("transcript_start", 0)
            }
            val combined = buildJsonArray {
                repeat(121) { index ->
                    add(buildJsonObject {
                        put("speaker", "角色")
                        put("message", "消息$index")
                    })
                }
            }
            val compacted = storage.compactSessionTranscript(runId, sessionId, base, combined)
            val manifest = buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("transcript", compacted.recent)
                put("transcript_start", compacted.startIndex)
                put("transcript_count", compacted.totalCount)
            }
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), manifest),
            )

            // A later turn wrote an archive chunk before its manifest update was interrupted.
            val archive = storage.getDialogueSessionsDirectory(runId) / "$sessionId/transcript-archive"
            storage.writeTextAtomically(archive / "0000000041.json", """[{"message":"重复"}]""")
            val lean = storage.loadSessionManifest(runId, sessionId)
            val full = storage.getDialogueSession(runId, sessionId)
            assertEquals(80, lean["transcript"]?.jsonArray?.size)
            assertEquals(41, lean["transcript_start"]?.jsonPrimitive?.intOrNull)
            assertEquals(121, full["transcript"]?.jsonArray?.size)
            assertEquals("消息0", full["transcript"]?.jsonArray?.first()?.jsonObject?.get("message")?.jsonPrimitive?.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paged transcript reads only needed chunks and pins revision`() {
        val dir = createTempDirectory("zaomeng-session-paging")
        try {
            val reads = mutableListOf<String>()
            val files = FileSystemDocumentStore()
            val store = object : DocumentStore by files {
                override fun readBytes(path: Path): ByteArray? {
                    reads += path.name
                    return files.readBytes(path)
                }
            }
            val storage = StorageService(dir.toString().toPath(), store)
            val runId = "run-1"
            val sessionId = "session-1"
            writeArchivedSession(storage, runId, sessionId, count = 241, revision = 7)

            val tail = storage.transcriptReader.readPage(runId, sessionId, 0, 80, TranscriptOrder.DESC, 7)
            assertEquals((240 downTo 161).toList(), tail.items.map { it.seq })
            assertEquals(80, tail.nextOffset)
            assertTrue(reads.none { it.startsWith("00000000") && it.endsWith(".json") })

            reads.clear()
            val older = storage.transcriptReader.readPage(runId, sessionId, 80, 80, TranscriptOrder.DESC, 7)
            assertEquals((160 downTo 81).toList(), older.items.map { it.seq })
            assertEquals(160, older.nextOffset)
            assertTrue("0000000160.json" in reads)
            assertTrue("0000000080.json" in reads)
            assertTrue("0000000000.json" !in reads)

            val crossing = storage.transcriptReader.readPage(runId, sessionId, 159, 4, TranscriptOrder.ASC, 7)
            assertEquals(listOf(159, 160, 161, 162), crossing.items.map { it.seq })
            assertEquals(listOf(159, 160, 161), storage.transcriptReader.readByTurnId(runId, sessionId, "turn-53", 7).map { it.seq })
            assertFailsWith<SessionRevisionConflictException> {
                storage.transcriptReader.readPage(runId, sessionId, 0, 10, TranscriptOrder.ASC, 6)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `history mutation increments once and metadata keeps revision`() {
        val dir = createTempDirectory("zaomeng-session-revision")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            val manifestPath = storage.getDialogueSessionManifestFile(runId, sessionId)
            storage.writeTextAtomically(manifestPath, """{"session_id":"$sessionId","transcript":[]}""")

            val titled = storage.updateSessionManifest(runId, sessionId) { current ->
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("title", "标题")
                }
            }
            assertEquals(0, sessionHistoryRevision(titled))

            val committed = storage.mutateSessionHistory(runId, sessionId, expectedRevision = 0) { current ->
                val updated = buildJsonObject {
                    current.manifest.forEach { (key, value) -> put(key, value) }
                    put("transcript", buildJsonArray {
                        add(buildJsonObject {
                            put("turn_id", "turn-1")
                            put("message", "新消息")
                        })
                    })
                }
                SessionHistoryMutation(updated, "result")
            }
            assertEquals(1, committed.revision)
            assertEquals("result", committed.result)
            assertEquals(1, committed.transcriptCount)

            val renamed = storage.updateSessionManifest(runId, sessionId) { current ->
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("title", "新标题")
                    put("revision", 99)
                }
            }
            assertEquals(1, sessionHistoryRevision(renamed))
            assertFailsWith<SessionRevisionConflictException> {
                storage.mutateSessionHistory(runId, sessionId, expectedRevision = 0) {
                    SessionHistoryMutation(it.manifest, Unit)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `committing operation resumes and completed operation replays`() {
        val dir = createTempDirectory("zaomeng-operation-replay")
        try {
            val storage = StorageService(dir.toString().toPath())
            val store = ReplyOperationStore(storage)
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile("run-1", "session-1"), "{}")
            val claimed = store.claim("run-1", "session-1", "op-1", "op-1", "hash", 0, "owner-1") as OperationClaim.Owner
            assertTrue(store.claim("run-1", "session-1", "op-1", "op-1", "hash", 0, "owner-2") is OperationClaim.Wait)
            assertTrue(store.claim("run-1", "session-1", "op-1", "op-1", "other", 0, "owner-2") is OperationClaim.Conflict)
            val result = operationResult("op-1")

            val committing = store.markCommitting(claimed.receipt, result)
            assertEquals(ReplyOperationStatus.COMMITTING, committing.status)
            store.recoverOrphan("run-1", "session-1", "op-1")

            val resumed = store.claim("run-1", "session-1", "op-1", "op-1", "hash", 0, "owner-3") as OperationClaim.Owner
            assertEquals(ReplyOperationStatus.COMMITTING, resumed.receipt.status)
            assertEquals(result, resumed.receipt.result)
            store.markCompleted(resumed.receipt)

            val replay = store.claim("run-1", "session-1", "op-1", "op-1", "hash", 0, "owner-4") as OperationClaim.Replay
            assertEquals(ReplyOperationStatus.COMPLETED, replay.receipt.status)
            assertEquals(result, replay.receipt.result)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interrupted generating operation becomes retryable`() {
        val dir = createTempDirectory("zaomeng-operation-retry")
        try {
            val storage = StorageService(dir.toString().toPath())
            val store = ReplyOperationStore(storage)
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile("run-1", "session-1"), "{}")
            store.claim("run-1", "session-1", "op-1", "op-1", "hash", 0, "owner-1")

            val interrupted = store.recoverOrphan("run-1", "session-1", "op-1")
            assertEquals(ReplyOperationStatus.FAILED, interrupted?.status)
            assertEquals(true, interrupted?.failure?.retryable)
            val retry = store.claim("run-1", "session-1", "op-1", "op-1", "hash", 3, "owner-2") as OperationClaim.Owner
            assertEquals(2, retry.receipt.attempt)
            assertEquals(3, retry.receipt.baseRevision)
            assertEquals(ReplyOperationStatus.GENERATING, retry.receipt.status)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `enhancer changes do not invalidate an existing operation`() {
        val dir = createTempDirectory("zaomeng-operation-enhancer")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                """{"run_id":"$runId","session_id":"$sessionId","transcript":[]}""",
            )
            val coordinator = DialogueReplyOperationCoordinator(storage)
            val request = ReplyOperationRequest(
                runId = runId,
                sessionId = sessionId,
                operationId = "op-1",
                message = "同一条消息",
                messageKind = "dialogue",
                includeInnerThoughts = false,
            )
            val active = runBlocking { coordinator.begin(request) } as ReplyOperationBegin.Active
            coordinator.markCommitting(active.lease, operationResult("op-1"))
            coordinator.markCompleted(active.lease)
            coordinator.release(active.lease)
            storage.updateSessionManifest(runId, sessionId) { current ->
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("plugin_enhancer_states", buildJsonObject {
                        put("com.zaomeng.inner-thoughts", buildJsonObject {
                            put("inner-thoughts", true)
                        })
                    })
                }
            }

            val replay = runBlocking { coordinator.begin(request) }
            assertTrue(replay is ReplyOperationBegin.Replay)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `full session rejects missing archive chunks`() {
        val dir = createTempDirectory("zaomeng-full-archive-validation")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            writeArchivedSession(storage, runId, sessionId, count = 121, revision = 7)
            val archive = storage.getDialogueSessionsDirectory(runId) / "$sessionId/transcript-archive"
            storage.deleteFile(archive / "0000000000.json")

            assertFailsWith<TranscriptArchiveException> {
                storage.getDialogueSession(runId, sessionId)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `derived operations complete effects and recover orphan claims`() {
        val dir = createTempDirectory("zaomeng-derived-store")
        try {
            val storage = StorageService(dir.toString().toPath())
            val store = DerivedOperationStore(storage)
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile("run-1", "session-1"), """{"session_id":"session-1","revision":2,"transcript":[{"turn_id":"op-1","message":"回复"}]}""")
            store.ensure("run-1", "session-1", DerivedOperationKind.POST_TURN, operationResult("op-1"))

            var claimed = store.claimNext("run-1", "session-1", "owner-1")!!
            assertEquals(DerivedOperationStatus.RUNNING, claimed.status)
            DerivedEffect.entries.forEach { effect ->
                claimed = store.completeEffect(claimed, effect)
            }
            assertEquals(DerivedOperationStatus.SUCCEEDED, claimed.status)
            assertEquals(null, store.claimNext("run-1", "session-1", "owner-2"))

            store.ensure("run-1", "session-1", DerivedOperationKind.POST_TURN, operationResult("op-1").copy(committedRevision = 2))
            val orphan = store.claimNext("run-1", "session-1", "owner-3")!!
            val recovered = store.recoverOrphan(orphan)
            assertEquals(DerivedOperationStatus.PENDING, recovered.status)
            assertEquals("", recovered.ownerId)
            val retry = store.claimNext("run-1", "session-1", "owner-4")!!
            assertEquals(orphan.attempt + 1, retry.attempt)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `correction supersedes older derived generation`() {
        val dir = createTempDirectory("zaomeng-derived-correction")
        try {
            val storage = StorageService(dir.toString().toPath())
            val store = DerivedOperationStore(storage)
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile("run-1", "session-1"), """{"session_id":"session-1","revision":2,"transcript":[{"turn_id":"op-1","message":"回复"}]}""")
            store.ensure("run-1", "session-1", DerivedOperationKind.POST_TURN, operationResult("op-1"))
            store.ensure(
                "run-1",
                "session-1",
                DerivedOperationKind.CORRECTION,
                operationResult("op-1").copy(committedRevision = 2, message = "修正稿"),
            )

            val operations = store.listAll("run-1", "session-1")
            assertEquals(DerivedOperationStatus.SUPERSEDED, operations.first().status)
            assertEquals(DerivedOperationStatus.PENDING, operations.last().status)
            assertEquals(2, operations.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session projection rebuilds from authoritative revision without changing history`() {
        val dir = createTempDirectory("zaomeng-session-projection")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            val manifest = buildJsonObject {
                put("run_id", runId)
                put("session_id", sessionId)
                put("title", "测试会话")
                put("revision", 1)
                put("participants", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("林夕")) })
                put("transcript", buildJsonArray {
                    add(buildJsonObject {
                        put("turn_id", "turn-1")
                        put("speaker", "我")
                        put("role", "user")
                        put("message", "我们在旧车站见面。")
                    })
                    add(buildJsonObject {
                        put("turn_id", "turn-1")
                        put("speaker", "林夕")
                        put("role", "character")
                        put("message", "我会带上钥匙。")
                    })
                    add(buildJsonObject {
                        put("turn_id", "turn-2")
                        put("speaker", "我")
                        put("role", "user")
                        put("message", "后来去了码头。")
                    })
                    add(buildJsonObject {
                        put("turn_id", "turn-2")
                        put("speaker", "林夕")
                        put("role", "character")
                        put("message", "雨还没有停。")
                    })
                })
            }
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile(runId, sessionId), manifest.toString())
            val service = SessionProjectionService(storage)

            val fresh = awaitProjection(service, runId, sessionId, "fresh")
            assertEquals(1, fresh.sourceRevision)
            assertEquals(1, fresh.currentRevision)
            assertTrue(fresh.session?.storyRecap != null)
            assertEquals(0, fresh.session?.transcript?.size)
            assertEquals(1, sessionHistoryRevision(storage.loadSessionManifest(runId, sessionId)))
            assertTrue(service.freshPromptSummaries(runId, sessionId) != null)

            storage.mutateSessionHistory(runId, sessionId, expectedRevision = 1) { current ->
                val updated = buildJsonObject {
                    current.manifest.forEach { (key, value) -> put(key, value) }
                    put("transcript", buildJsonArray {
                        (current.manifest["transcript"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach(::add)
                        add(buildJsonObject {
                            put("turn_id", "turn-3")
                            put("speaker", "林夕")
                            put("role", "character")
                            put("message", "今晚我们在灯塔会合。")
                        })
                    })
                }
                SessionHistoryMutation(updated, Unit)
            }
            assertEquals("stale", service.get(runId, sessionId).status)
            assertEquals(null, service.freshPromptSummaries(runId, sessionId))

            val rebuilt = awaitProjection(service, runId, sessionId, "fresh")
            assertEquals(2, rebuilt.sourceRevision)
            assertEquals(2, rebuilt.currentRevision)
            assertEquals(2, sessionHistoryRevision(storage.loadSessionManifest(runId, sessionId)))
            assertTrue(rebuilt.session?.storyRecap != null)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `foreign or broken projection sidecar is treated as missing`() {
        val dir = createTempDirectory("zaomeng-projection-foreign")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                buildJsonObject {
                    put("session_id", sessionId)
                    put("revision", 1)
                    put("transcript", buildJsonArray {
                        add(buildJsonObject {
                            put("turn_id", "turn-1")
                            put("speaker", "我")
                            put("role", "user")
                            put("message", "我们从这里开始。")
                        })
                    })
                }.toString(),
            )
            // 整卷目录被复制或导入到新书卷后，旧 sidecar 仍带着原来的 run_id/session_id。
            storage.writeTextAtomically(
                storage.getDialogueSessionsDirectory(runId) / "$sessionId/session_projection.json",
                """{"projection_version":1,"run_id":"old-run","session_id":"old-session","source_revision":1,""" +
                    """"source_start_seq":0,"source_end_seq":1,"volumes":[],"session_summary":{},""" +
                    """"generated_at":"2026-01-01T00:00:00Z"}""",
            )
            val service = SessionProjectionService(storage)

            assertNull(service.freshPromptSummaries(runId, sessionId))
            assertNotEquals("fresh", service.get(runId, sessionId).status)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun awaitProjection(
        service: SessionProjectionService,
        runId: String,
        sessionId: String,
        expectedStatus: String,
    ) = runBlocking {
        service.rebuild(runId, sessionId)
        var current = service.get(runId, sessionId)
        repeat(100) {
            if (current.status == expectedStatus) return@runBlocking current
            kotlinx.coroutines.delay(25)
            current = service.get(runId, sessionId)
        }
        current
    }

    @Test
    fun `prepared intent is not claimed before commit and is cancelled by startup reconciliation`() {
        val dir = createTempDirectory("zaomeng-derived-prepared")
        try {
            val storage = StorageService(dir.toString().toPath())
            val store = DerivedOperationStore(storage)
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile("run-1", "session-1"), """{"session_id":"session-1","revision":2,"transcript":[{"turn_id":"op-1","message":"回复"}]}""")
            val prepared = store.ensure(
                "run-1",
                "session-1",
                DerivedOperationKind.POST_TURN,
                operationResult("op-ghost"),
                activate = false,
            )
            assertEquals(DerivedOperationStatus.PREPARED, prepared.status)
            assertEquals(null, store.claimNext("run-1", "session-1", "owner-1"))
            assertEquals(DerivedOperationStatus.PREPARED, store.listAll("run-1", "session-1").single().status)

            store.reconcilePrepared("run-1", "session-1")
            assertEquals(DerivedOperationStatus.CANCELLED, store.listAll("run-1", "session-1").single().status)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `search finds archived messages with bounded pages`() {
        val dir = createTempDirectory("zaomeng-search-archive")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            writeArchivedSession(storage, runId, sessionId, count = 241, revision = 7)
            val service = DialogueAdvancedService(storage, llm = null, prompts = null)

            val results = service.search(runId, sessionId, "消息0", limit = 1)
            assertEquals(1, results.size)
            assertEquals("消息0", results.first().jsonObject["message"]?.jsonPrimitive?.contentOrNull)
            assertEquals(true, results.first().jsonObject["archived"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `history tools distinguish incomplete lookup from not found`() {
        val dir = createTempDirectory("zaomeng-history-tools")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
            writeArchivedSession(storage, runId, sessionId, count = 241, revision = 7)
            val service = DialogueHistoryRetrievalService(storage)

            val hit = service.historySearch(runId, sessionId, "消息0", TranscriptOrder.DESC, 1, 7)
            assertEquals(listOf(0), hit.items.map { it.seq })
            assertEquals(true, hit.complete)

            val turn = service.historyRead(runId, sessionId, "turn-53", 7)
            assertEquals(listOf(159, 160, 161), turn.items.map { it.seq })
            assertEquals(false, turn.complete)

            val missing = service.historySearch(runId, sessionId, "不存在的关键词", TranscriptOrder.DESC, 1, 7)
            assertEquals(emptyList(), missing.items)
            assertEquals(true, missing.complete)
            assertFailsWith<SessionRevisionConflictException> {
                service.historyRead(runId, sessionId, "turn-53", 6)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy manifest without revision paginates from zero`() {
        val dir = createTempDirectory("zaomeng-legacy-manifest")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                """
                {"session_id":"$sessionId","transcript":[
                  {"turn_id":"turn-0","message":"旧消息0"},
                  {"turn_id":"turn-0","message":"旧消息1"},
                  {"turn_id":"turn-1","message":"旧消息2"},
                  {"turn_id":"turn-1","message":"旧消息3"},
                  {"turn_id":"turn-1","message":"旧消息4"}
                ]}
                """.trimIndent(),
            )

            val snapshot = storage.transcriptReader.readSnapshot(runId, sessionId)
            assertEquals(0L, snapshot.revision)
            assertEquals(0, snapshot.transcriptStart)
            assertEquals(5, snapshot.transcriptCount)

            val tail = storage.transcriptReader.readPage(runId, sessionId, 0, 2, TranscriptOrder.DESC)
            assertEquals(listOf(4, 3), tail.items.map { it.seq })
            assertEquals(2, tail.nextOffset)
            assertEquals(0L, tail.revision)

            assertEquals(
                listOf(0, 1),
                storage.transcriptReader.readByTurnId(runId, sessionId, "turn-0").map { it.seq },
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale transcript count does not shrink paged total`() {
        val dir = createTempDirectory("zaomeng-stale-count")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                """
                {"session_id":"$sessionId","transcript_start":0,"transcript_count":999,
                 "transcript":[{"message":"a"},{"message":"b"},{"message":"c"}]}
                """.trimIndent(),
            )

            val page = storage.transcriptReader.readPage(runId, sessionId, 0, 10, TranscriptOrder.DESC)
            assertEquals(3, page.total)
            assertEquals(listOf(2, 1, 0), page.items.map { it.seq })
            assertEquals(false, page.hasMore)
            assertEquals(null, page.nextOffset)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun operationResult(turnId: String) = ReplyOperationResult(
        turnId = turnId,
        committedRevision = 1,
        transcriptCountAtCommit = 1,
        appendedTranscript = listOf(buildJsonObject {
            put("turn_id", turnId)
            put("seq", 0)
            put("message", "回复")
        }),
        message = "输入",
        messageKind = "dialogue",
        responses = listOf(DialogueResponse("角色", "回复")),
    )

    private fun writeArchivedSession(
        storage: StorageService,
        runId: String,
        sessionId: String,
        count: Int,
        revision: Long,
    ) {
        val base = buildJsonObject {
            put("run_id", runId)
            put("session_id", sessionId)
            put("transcript_start", 0)
            put("revision", revision)
        }
        val combined = buildJsonArray {
            repeat(count) { index ->
                add(buildJsonObject {
                    put("turn_id", "turn-${index / 3}")
                    put("speaker", "角色")
                    put("message", "消息$index")
                })
            }
        }
        val compacted = storage.compactSessionTranscript(runId, sessionId, base, combined)
        val manifest = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("transcript", compacted.recent)
            put("transcript_start", compacted.startIndex)
            put("transcript_count", compacted.totalCount)
        }
        storage.writeTextAtomically(storage.getDialogueSessionManifestFile(runId, sessionId), manifest.toString())
        // 崩溃遗留的未来 chunk 不属于当前 revision。
        val archive = storage.getDialogueSessionsDirectory(runId) / "$sessionId/transcript-archive"
        storage.writeTextAtomically(archive / "0000000241.json", """[{"message":"孤儿"}]""")
    }
}
