package top.wkbin.zaomeng.ktor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.nio.file.Files
import okio.Path.Companion.toPath
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.ktor.services.DialoguePayloadBuilder
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 对话回复链路性能回归测试：
 * 1) speaker 统计从 transcript 派生（不依赖 turns 目录全量扫描——长会话 O(N) 文件 IO 已消除）；
 * 2) 构建出的 payload 大小有上界（防止上下文膨胀拖慢 DeepSeek prefill/TTFT）。
 */
class DialoguePayloadBuilderPerformanceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun makeStorage(): Pair<StorageService, java.io.File> {
        val rootFile = Files.createTempDirectory("zaomeng-perf-").toFile()
        val storage = StorageService(rootFile.absolutePath.toPath())
        val root = rootFile
        val runDir = java.io.File(root, "runs/test-run")
        runDir.mkdirs()
        java.io.File(runDir, "run_manifest.json").writeText(
            """{"run_id":"test-run","title":"性能测试","participants":["武松","潘金莲"],"artifact_index":{}}""",
        )
        val sessionDir = java.io.File(runDir, "sessions/test-session")
        sessionDir.mkdirs()
        val transcript = buildString {
            repeat(10) { round ->
                val turnId = "turn-$round"
                append("""{"speaker":"我","message":"消息$round","role":"user","turn_id":"$turnId","timestamp":"t"},""")
                append("""{"speaker":"武松","message":"武松说$round","role":"character","turn_id":"$turnId","timestamp":"t"},""")
                append("""{"speaker":"潘金莲","message":"金莲说$round","role":"character","turn_id":"$turnId","timestamp":"t"},""")
            }
        }.trimEnd(',')
        java.io.File(sessionDir, "session_manifest.json").writeText(
            """{"session_id":"test-session","participants":["武松","潘金莲"],"turn_count":10,"transcript":[$transcript]}""",
        )
        return storage to root
    }

    @Test
    fun `speaker activity is derived from transcript without turns directory`() {
        val (storage, _) = makeStorage()
        val builder = DialoguePayloadBuilder(storage)
        val runManifest = json.parseToJsonElement(
            storage.readText(storage.getRunDirectory("test-run") / "run_manifest.json"),
        ).jsonObject
        val session = json.parseToJsonElement(
            storage.readText(storage.getRunDirectory("test-run") / "sessions/test-session/session_manifest.json"),
        ).jsonObject

        // 关键：turns/ 目录根本不存在——统计必须来自 transcript（旧实现扫描 turns 目录会得到空数据）
        val turnsDir = java.io.File(storage.getRunDirectory("test-run").toString(), "sessions/test-session/turns")
        assertTrue(!turnsDir.exists(), "fixture 不应包含 turns 目录")

        val payload = builder.buildTurnPayload(
            runManifest = runManifest,
            session = session,
            turnId = "next-turn",
            message = "你好",
            messageKind = "dialogue",
            includeInnerThoughts = false,
        )
        val activity = (payload["speaker_activity"] as? List<*>) ?: emptyList<Any?>()

        // 10 轮 transcript → 武松/潘金莲各应有 10 次发言，状态 active（非 new/silent）
        val wusong = activity.mapNotNull { (it as? Map<*, *>)?.mapKeys { e -> e.key.toString() } }
            .firstOrNull { it["name"]?.toString() == "武松" }
        assertEquals("武松", wusong?.get("name")?.toString())
        assertEquals(10, (wusong?.get("reply_count") as? Number)?.toInt())
        assertEquals("active", wusong?.get("status")?.toString())

        val pan = activity.mapNotNull { (it as? Map<*, *>)?.mapKeys { e -> e.key.toString() } }
            .firstOrNull { it["name"]?.toString() == "潘金莲" }
        assertEquals(10, (pan?.get("reply_count") as? Number)?.toInt())
        assertEquals("active", pan?.get("status")?.toString())
    }

    @Test
    fun `payload size stays bounded for long sessions`() {
        val (storage, _) = makeStorage()
        val builder = DialoguePayloadBuilder(storage)
        val runManifest = json.parseToJsonElement(
            storage.readText(storage.getRunDirectory("test-run") / "run_manifest.json"),
        ).jsonObject
        val session = json.parseToJsonElement(
            storage.readText(storage.getRunDirectory("test-run") / "sessions/test-session/session_manifest.json"),
        ).jsonObject

        val payload = builder.buildTurnPayload(
            runManifest = runManifest,
            session = session,
            turnId = "next-turn",
            message = "你好",
            messageKind = "dialogue",
            includeInnerThoughts = true,
        )
        // 序列化后的 user payload 有上界（10 轮会话不应超过 30KB；超阈值说明上下文膨胀，会拖慢 TTFT）
        val serialized = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            JsonObject(payload.mapKeys { it.key.toString() }.mapValues { (_, v) -> toJsonElement(v) }),
        )
        assertTrue(serialized.length < 30_000, "payload 过大：${serialized.length} 字符")
    }

    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> kotlinx.serialization.json.buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), toJsonElement(v)) }
        }
        is List<*> -> kotlinx.serialization.json.buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }
}
