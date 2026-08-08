package top.wkbin.zaomeng.ktor

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.ktor.plugins.configureSecurity
import top.wkbin.zaomeng.ktor.routes.cardsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterManagementRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueAdvancedRoutes
import top.wkbin.zaomeng.ktor.routes.pluginOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.relationsRoutes
import top.wkbin.zaomeng.ktor.routes.runOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.diagnosticsRoute
import top.wkbin.zaomeng.ktor.routes.healthRoute
import top.wkbin.zaomeng.ktor.routes.pluginRoutes
import top.wkbin.zaomeng.ktor.routes.personaRoutes
import top.wkbin.zaomeng.ktor.routes.runManagementRoutes
import top.wkbin.zaomeng.ktor.routes.runsRoute
import top.wkbin.zaomeng.ktor.routes.sessionManagementRoutes
import top.wkbin.zaomeng.ktor.routes.worldMemoryRoutes
import top.wkbin.zaomeng.ktor.services.DialogueService
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.ChapterManagementService
import top.wkbin.zaomeng.ktor.services.CardsManagementService
import top.wkbin.zaomeng.ktor.services.DialogueAdvancedService
import top.wkbin.zaomeng.ktor.services.DistillExecutor
import top.wkbin.zaomeng.ktor.services.PluginOperationsService
import top.wkbin.zaomeng.ktor.services.RelationsService
import top.wkbin.zaomeng.ktor.services.RunOperationsService
import top.wkbin.zaomeng.ktor.services.PluginService
import top.wkbin.zaomeng.ktor.services.PersonaService
import top.wkbin.zaomeng.ktor.services.RunManagementService
import top.wkbin.zaomeng.ktor.services.RunPackageService
import top.wkbin.zaomeng.ktor.services.SessionManagementService
import top.wkbin.zaomeng.ktor.services.WorldMemoryService
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * Ktor API 集成测试
 *
 * 测试所有 API 端点的基本功能
 */
class KtorApiIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health check endpoint returns ok`() = testZaomengApplication {
        val response = client.get("/api/web/health")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonBody = json.parseToJsonElement(body).jsonObject

        assertEquals("ok", jsonBody["status"]?.jsonPrimitive?.content)
        assertEquals("ktor", jsonBody["backend"]?.jsonPrimitive?.content)
        assertTrue(jsonBody.containsKey("version"))
    }

    @Test
    fun `runs list endpoint returns array`() = testZaomengApplication {
        val response = client.get("/api/web/runs") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonBody = json.parseToJsonElement(body)

        assertTrue(json.parseToJsonElement(body).jsonObject.containsKey("items"))
    }

    @Test
    fun `runs endpoint requires authentication`() = testZaomengApplication {
        // 不带 token
        val response = client.get("/api/web/runs")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `diagnostics export endpoint returns report`() = testZaomengApplication {
        val response = client.get("/api/web/diagnostics/export") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonBody = json.parseToJsonElement(body).jsonObject

        // 验证报告结构
        assertEquals("zaomeng_diagnostics", jsonBody["kind"]?.jsonPrimitive?.content)
        assertEquals(1, jsonBody["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull())
        assertTrue(jsonBody.containsKey("generatedAt"))
        assertTrue(jsonBody.containsKey("runtime"))
        assertTrue(jsonBody.containsKey("storage"))
        assertTrue(jsonBody.containsKey("model"))
        assertTrue(jsonBody.containsKey("runs"))
    }

    @Test
    fun `get non-existent run returns 404`() = testZaomengApplication {
        val response = client.get("/api/web/runs/non-existent-run-id") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `dialogue sessions endpoint works for non-existent run`() = testZaomengApplication {
        val response = client.get("/api/web/runs/non-existent/dialogue/sessions") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        // 应该返回 404（运行不存在）
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `chapters endpoint works for non-existent run`() = testZaomengApplication {
        val response = client.get("/api/web/runs/non-existent/chapters") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        // 应该返回 404（运行不存在）
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `plugin list returns items envelope`() = testZaomengApplication {
        val response = client.get("/api/web/plugins") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["items"] != null)
    }

    @Test
    fun `run creation rejects invalid payload with json error`() = testZaomengApplication {
        val response = client.post("/api/web/runs") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("{\"novel_name\":\"\",\"novel_content_base64\":\"bad\",\"characters\":[]}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(json.parseToJsonElement(response.bodyAsText()).jsonObject["error"] != null)
    }

    @Test
    fun `legacy stop route is available`() = testZaomengApplication {
        val response = client.post("/api/web/runs/missing/stop") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `plugin config returns not found for unknown plugin`() = testZaomengApplication {
        val response = client.get("/api/web/plugins/missing/config") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `plugin enable validates plugin id`() = testZaomengApplication {
        val response = client.post("/api/web/plugins/../enable") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }
        assertTrue(response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.NotFound)
    }

    @Test
    fun `imported run detail preserves structured manifest`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(
                storage.getRunManifestPath("imported-run"),
                """
                {
                  "run_id": "imported-run",
                  "status": "ready",
                  "progress": {"stage": "completed", "percent": 100.0},
                  "imported_from": {"package_filename": "demo.zaomeng-run.zip"}
                }
                """.trimIndent(),
            )
        },
    ) {
        val response = client.get("/api/web/runs/imported-run") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("completed", body["progress"]?.jsonObject?.get("stage")?.jsonPrimitive?.content)
        assertEquals(
            "demo.zaomeng-run.zip",
            body["imported_from"]?.jsonObject?.get("package_filename")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `imported persona can be read edited and assigned an avatar`() = testZaomengApplication(
        setup = { storage ->
            val profile = storage.getRunDirectory("persona-run").resolve("artifacts/characters/book/林黛玉/PROFILE.generated.md")
            storage.writeTextAtomically(
                profile,
                """
                ---
                name: 林黛玉
                core_identity: 贾府寄居的才女
                core_traits:
                  - 敏感
                  - 清醒
                ---

                # PROFILE
                """.trimIndent(),
            )
            storage.writeTextAtomically(
                storage.getRunManifestPath("persona-run"),
                """
                {
                  "run_id": "persona-run",
                  "artifact_index": {
                    "characters": [{
                      "name": "林黛玉",
                      "profile_file": ${JsonPrimitive(profile.absolutePath)},
                      "persona_dir": ${JsonPrimitive(requireNotNull(profile.parentFile).absolutePath)},
                      "avatar_version": ""
                    }]
                  }
                }
                """.trimIndent(),
            )
        },
        includePersona = true,
    ) {
        val path = "/api/web/runs/persona-run/personas/${"林黛玉".encodeURLPathPart()}"
        val review = client.get(path) { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, review.status)
        assertEquals("贾府寄居的才女", json.parseToJsonElement(review.bodyAsText()).jsonObject["fields"]?.jsonObject?.get("core_identity")?.jsonPrimitive?.content)

        val saved = client.put(path) {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("{\"core_identity\":\"潇湘馆主人\",\"review_note\":\"test\"}")
        }
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals("潇湘馆主人", json.parseToJsonElement(saved.bodyAsText()).jsonObject["fields"]?.jsonObject?.get("core_identity")?.jsonPrimitive?.content)

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val uploaded = client.post("$path/avatar") {
            bearerAuth("test-token")
            setBody(MultiPartFormDataContent(formData {
                append("file", png, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=avatar.png")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, uploaded.status)
        val version = json.parseToJsonElement(uploaded.bodyAsText()).jsonObject["avatar_version"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(version.isNotBlank())
        val manifestVersion = json.parseToJsonElement(
            client.get("/api/web/runs/persona-run") { bearerAuth("test-token") }.bodyAsText(),
        ).jsonObject["artifact_index"]?.jsonObject?.get("characters")?.jsonArray?.first()?.jsonObject?.get("avatar_version")?.jsonPrimitive?.content
        assertEquals(version, manifestVersion)
        assertTrue(client.get("$path/avatar") { bearerAuth("test-token") }.body<ByteArray>().contentEquals(png))
    }

    @Test
    fun `persona without yaml frontmatter is tolerated not 400`() = testZaomengApplication(
        setup = { storage ->
            val profileDir = storage.getRunDirectory("persona-run").resolve("artifacts/characters/book/林黛玉")
            profileDir.mkdirs()
            // 纯 Markdown 档案，无 YAML frontmatter（此前会 400）
            storage.writeTextAtomically(
                profileDir.resolve("PROFILE.md"),
                "# 林黛玉\n\n贾府姑娘，体弱多病。\n\n## 特点\n- 敏感\n- 有才情",
            )
            storage.writeTextAtomically(
                storage.getRunManifestPath("persona-run"),
                """
                {
                  "run_id": "persona-run",
                  "artifact_index": {
                    "characters": [{
                      "name": "林黛玉",
                      "profile_file": ${JsonPrimitive(profileDir.resolve("PROFILE.md").absolutePath)},
                      "persona_dir": ${JsonPrimitive(profileDir.absolutePath)},
                      "avatar_version": ""
                    }]
                  }
                }
                """.trimIndent(),
            )
        },
        includePersona = true,
    ) {
        val path = "/api/web/runs/persona-run/personas/${"林黛玉".encodeURLPathPart()}"
        val review = client.get(path) { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, review.status, review.bodyAsText())
        val fields = json.parseToJsonElement(review.bodyAsText()).jsonObject["fields"]?.jsonObject
        assertTrue(fields != null)
    }

    @Test
    fun `app dialogue modes fail cleanly without llm and preset sessions are readable`() {
        var sessionsDir: java.io.File? = null
        testZaomengApplication(
            setup = { storage ->
                storage.writeTextAtomically(
                    storage.getRunManifestPath("dialogue-run"),
                    """{"run_id":"dialogue-run","artifact_index":{"characters":[]}}""",
                )
                // 预置一个已开场会话（对齐 openDialogueSession 成功后的状态）
                val sessionDir = storage.getDialogueSessionsDirectory("dialogue-run").resolve("preset-session")
                sessionDir.mkdirs()
                storage.writeTextAtomically(
                    sessionDir.resolve("session_manifest.json"),
                    """{"session_id":"preset-session","run_id":"dialogue-run","mode":"observe","participants":["林黛玉"],"controlled_character":"","scene_card_id":"","scene_profile":{},"self_card_id":"","self_profile":{},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","title":"","status":"ready","transcript":[],"turns":[],"turn_count":0,"current_turn_id":""}""",
                )
                sessionsDir = storage.getDialogueSessionsDirectory("dialogue-run")
            },
            includeSessions = true,
        ) {
            // 对齐 Python：创建会话时自动开场生成场景；测试环境无 LLM → 创建失败且会话被清理
            for (mode in listOf("observe", "act", "insert")) {
                val created = client.post("/api/web/runs/dialogue-run/dialogue/sessions") {
                    bearerAuth("test-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"mode":"$mode","participants":["林黛玉"]}""")
                }
                assertTrue(created.status != HttpStatusCode.Created, created.bodyAsText())
            }
            val leftovers = sessionsDir!!.listFiles()?.map { it.name }.orEmpty()
            assertEquals(listOf("preset-session"), leftovers, "开场失败的会话应被清理，预置会话应保留")

            // 预置会话可读取
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/web/runs/dialogue-run/dialogue/sessions/preset-session") {
                    bearerAuth("test-token")
                }.status,
            )
        }
    }

    @Test
    fun `imported run avatars are served and injected into responses`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(
                storage.getRunManifestPath("avatar-run"),
                """{"run_id":"avatar-run","artifact_index":{"characters":[{"name":"林黛玉","profile_file":""}]}}""",
            )
            // 模拟书卷包自带头像：avatars/sha256(角色名).png
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("林黛玉".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val avatarFile = storage.getRunDirectory("avatar-run").resolve("avatars/$digest.png")
            avatarFile.parentFile?.mkdirs()
            avatarFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            // 预置会话（对齐 openDialogueSession 成功后的状态）
            val sessionDir = storage.getDialogueSessionsDirectory("avatar-run").resolve("avatar-session")
            sessionDir.mkdirs()
            storage.writeTextAtomically(
                sessionDir.resolve("session_manifest.json"),
                """{"session_id":"avatar-session","run_id":"avatar-run","mode":"observe","participants":["林黛玉"],"controlled_character":"","scene_card_id":"","scene_profile":{},"self_card_id":"","self_profile":{},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","title":"","status":"ready","transcript":[],"turns":[],"turn_count":0,"current_turn_id":""}""",
            )
        },
        includeSessions = true,
        includePersona = true,
    ) {
        // 1. run manifest 响应的 characters 实时注入 avatar_version
        val runBody = json.parseToJsonElement(
            client.get("/api/web/runs/avatar-run") { bearerAuth("test-token") }.bodyAsText(),
        ).jsonObject
        val avatarVersion = runBody["artifact_index"]?.jsonObject?.get("characters")
            ?.jsonArray?.first()?.jsonObject?.get("avatar_version")?.jsonPrimitive?.content
        assertTrue(!avatarVersion.isNullOrBlank(), "run manifest 应注入非空 avatar_version，实际: $avatarVersion")

        // 2. 会话响应的 character_avatars 实时注入（GET 预置会话）
        val session = json.parseToJsonElement(
            client.get("/api/web/runs/avatar-run/dialogue/sessions/avatar-session") {
                bearerAuth("test-token")
            }.bodyAsText(),
        ).jsonObject
        val avatars = session["character_avatars"]?.jsonObject
        assertTrue(avatars != null, "会话响应应包含 character_avatars")
        assertEquals(avatarVersion, avatars?.get("林黛玉")?.jsonPrimitive?.content)

        // 3. 头像文件可下载
        val encoded = java.net.URLEncoder.encode("林黛玉", "UTF-8")
        val avatarResponse = client.get("/api/web/runs/avatar-run/personas/$encoded/avatar") {
            bearerAuth("test-token")
        }
        assertEquals(HttpStatusCode.OK, avatarResponse.status)
        assertTrue(avatarResponse.bodyAsBytes().size > 0)
    }

    @Test
    fun `markdown persona profile fields are extracted`() = testZaomengApplication(
        setup = { storage ->
            val profile = storage.getRunDirectory("persona-run").resolve("artifacts/characters/book/史湘云/PROFILE.md")
            storage.writeTextAtomically(
                profile,
                """
                # PROFILE
                <!-- Canonical markdown profile storage. -->

                ## Meta
                - name: 史湘云

                ## Basic Positioning
                - core_identity: 史家豪门的幺女，父母双亡后寄居贾府
                - story_role: 大观园诗社的活跃成员

                ## Inner Core
                - identity_anchor: 史家嫡出小姐
                - temperament_type: 明朗直爽型，直来直去
                """.trimIndent(),
            )
            storage.writeTextAtomically(
                storage.getRunManifestPath("persona-run"),
                """
                {
                  "run_id": "persona-run",
                  "artifact_index": {
                    "characters": [{
                      "name": "史湘云",
                      "profile_file": ${JsonPrimitive(profile.absolutePath)},
                      "persona_dir": ${JsonPrimitive(requireNotNull(profile.parentFile).absolutePath)},
                      "avatar_version": ""
                    }]
                  }
                }
                """.trimIndent(),
            )
        },
        includePersona = true,
    ) {
        val path = "/api/web/runs/persona-run/personas/${"史湘云".encodeURLPathPart()}"
        val review = client.get(path) { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, review.status, review.bodyAsText())
        val fields = json.parseToJsonElement(review.bodyAsText()).jsonObject["fields"]?.jsonObject
        assertEquals("史家豪门的幺女，父母双亡后寄居贾府", fields?.get("core_identity")?.jsonPrimitive?.content)
        assertEquals("大观园诗社的活跃成员", fields?.get("story_role")?.jsonPrimitive?.content)
        assertEquals("史家嫡出小姐", fields?.get("identity_anchor")?.jsonPrimitive?.content)
        assertEquals("明朗直爽型，直来直去", fields?.get("temperament_type")?.jsonPrimitive?.content)
    }

    @Test
    fun `world memory CRUD preserves imported timeline`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("memory-run"), """{"run_id":"memory-run"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("memory-run").resolve("world_memory.json"),
                """
                {
                  "version": 1,
                  "facts": [],
                  "timeline": [{"timeline_id":"timeline-imported","title":"初见","extension":{"kept":true}}],
                  "updated_at": ""
                }
                """.trimIndent(),
            )
        },
        includeWorldMemory = true,
    ) {
        val base = "/api/web/runs/memory-run/world-memory"
        assertEquals(HttpStatusCode.OK, client.get(base) { bearerAuth("test-token") }.status)
        val created = client.post("$base/facts") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"category":"event","summary":"宝黛初见","characters":["贾宝玉","林黛玉"]}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val factId = json.parseToJsonElement(created.bodyAsText()).jsonObject["fact_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(factId.isNotBlank())
        assertEquals(
            HttpStatusCode.OK,
            client.put("$base/facts/$factId") {
                bearerAuth("test-token")
                contentType(ContentType.Application.Json)
                setBody("""{"category":"relationship","summary":"二人互生牵挂","locked":true}""")
            }.status,
        )
        val stored = json.parseToJsonElement(
            client.get(base) { bearerAuth("test-token") }.bodyAsText(),
        ).jsonObject
        assertEquals("timeline-imported", stored["timeline"]?.jsonArray?.first()?.jsonObject?.get("timeline_id")?.jsonPrimitive?.content)
        val deletedResponse = client.delete("$base/facts/$factId") { bearerAuth("test-token") }
        assertEquals(
            HttpStatusCode.OK,
            deletedResponse.status,
        )
        // 空 facts 为默认值，序列化时可能被省略；两者均视为已清空
        val factsAfterDelete = json.parseToJsonElement(
            client.get(base) { bearerAuth("test-token") }.bodyAsText(),
        ).jsonObject["facts"]?.jsonArray
        assertTrue(factsAfterDelete == null || factsAfterDelete.isEmpty())
    }

    @Test
    fun `session deletion purges its world memory and export without dialogue excludes it`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(
                storage.getRunManifestPath("purge-run"),
                """{"run_id":"purge-run","novel_id":"novel-purge","title":"清理测试","status":"ready"}""",
            )
            storage.writeTextAtomically(
                storage.getRunDirectory("purge-run").resolve("world_memory.json"),
                """
                {
                  "version": 1,
                  "facts": [
                    {"fact_id":"fact-sess1","category":"event","summary":"会话一的事实","source":"dialogue","source_session_id":"sess1","source_turn_id":"t1"},
                    {"fact_id":"fact-manual","category":"setting","summary":"手动设定","source":"manual"}
                  ],
                  "timeline": [
                    {"timeline_id":"tl-sess1","title":"会话一","turn_key":"sess1:t1","source_session_id":"sess1","source_turn_id":"t1"},
                    {"timeline_id":"tl-manual","title":"手动条目"}
                  ],
                  "updated_at": ""
                }
                """.trimIndent(),
            )
            storage.writeTextAtomically(
                storage.getRunDirectory("purge-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """{"session_id":"sess1","run_id":"purge-run","mode":"observe","participants":["贾宝玉"],"title":"会话一"}""",
            )
            storage.writeTextAtomically(
                storage.getRunDirectory("purge-run").resolve("novel.txt"),
                "测试正文。",
            )
        },
        includeSessions = true,
        includeWorldMemory = true,
        includeRunOps = true,
    ) {
        val base = "/api/web/runs/purge-run"
        // 不带会话导出：world_memory.json 不应出现
        val withoutDialogue = client.get("$base/export?include_dialogue=false") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, withoutDialogue.status, withoutDialogue.bodyAsText())
        val withoutNames = java.util.zip.ZipInputStream(withoutDialogue.bodyAsBytes().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue(withoutNames.none { it == "run/world_memory.json" }, "不带会话导出不应包含 world_memory.json: $withoutNames")
        assertTrue(withoutNames.none { it.startsWith("run/dialogue/") })

        // 带会话导出：world_memory.json 应包含
        val withDialogue = client.get("$base/export?include_dialogue=true") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, withDialogue.status, withDialogue.bodyAsText())
        val withNames = java.util.zip.ZipInputStream(withDialogue.bodyAsBytes().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue(withNames.any { it == "run/world_memory.json" })

        // 删除会话后：该会话归属的 facts/timeline 一并清除，手动条目保留
        val deleted = client.delete("$base/dialogue/sessions/sess1") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.bodyAsText())
        val memory = json.parseToJsonElement(
            client.get("$base/world-memory") { bearerAuth("test-token") }.bodyAsText(),
        ).jsonObject
        val factIds = memory["facts"]?.jsonArray?.mapNotNull { it.jsonObject["fact_id"]?.jsonPrimitive?.content }.orEmpty()
        assertEquals(listOf("fact-manual"), factIds)
        val timelineIds = memory["timeline"]?.jsonArray
            ?.mapNotNull { it.jsonObject["timeline_id"]?.jsonPrimitive?.content }.orEmpty()
        assertEquals(listOf("tl-manual"), timelineIds)
    }

    @Test
    fun `dialogue advanced memory relation-lock and branch-meta`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("adv-run"), """{"run_id":"adv-run"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("adv-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """
                {
                  "session_id": "sess1",
                  "run_id": "adv-run",
                  "mode": "observe",
                  "participants": ["贾宝玉", "林黛玉"],
                  "title": "初见",
                  "transcript": [
                    {"speaker":"我","message":"他们初见","role":"user","turn_id":"t1","timestamp":"2026-01-01T00:00:00Z"},
                    {"speaker":"贾宝玉","message":"这个妹妹我曾见过的","role":"character","turn_id":"t1","timestamp":"2026-01-01T00:00:01Z"},
                    {"speaker":"林黛玉","message":"好生奇怪，倒像在哪里见过","role":"character","turn_id":"t2","timestamp":"2026-01-01T00:00:02Z"}
                  ],
                  "turn_count": 2,
                  "current_turn_id": "t2"
                }
                """.trimIndent(),
            )
        },
        includeDialogueAdvanced = true,
    ) {
        val base = "/api/web/runs/adv-run/dialogue/sessions/sess1"

        // 创建记忆
        val created = client.post("$base/memories") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"黛玉葬花","category":"story","pinned":true}""")
        }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val memoryId = json.parseToJsonElement(created.bodyAsText()).jsonObject["memory_ledger"]
            ?.jsonArray?.first()?.jsonObject?.get("memory_id")?.jsonPrimitive?.content.orEmpty()
        assertTrue(memoryId.isNotBlank())

        // 更新记忆
        val updated = client.put("$base/memories/$memoryId") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"text":"黛玉葬花（修改）","category":"relationship"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        assertEquals(
            "黛玉葬花（修改）",
            json.parseToJsonElement(updated.bodyAsText()).jsonObject["memory_ledger"]
                ?.jsonArray?.first()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )

        // 删除记忆
        val deleted = client.delete("$base/memories/$memoryId") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.bodyAsText())
        assertTrue(
            json.parseToJsonElement(deleted.bodyAsText()).jsonObject["memory_ledger"]
                ?.jsonArray?.isEmpty() == true,
        )

        // 关系锁定
        val locked = client.put("$base/relation-lock") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"pair_key":"贾宝玉_林黛玉","locked":true}""")
        }
        assertEquals(HttpStatusCode.OK, locked.status, locked.bodyAsText())
        assertEquals(
            true,
            json.parseToJsonElement(locked.bodyAsText()).jsonObject["relation_locks"]
                ?.jsonObject?.get("贾宝玉_林黛玉")?.jsonPrimitive?.booleanOrNull,
        )

        // 分支元数据
        val meta = client.patch("$base/branch-meta") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"label":"主线","is_mainline":true}""")
        }
        assertEquals(HttpStatusCode.OK, meta.status, meta.bodyAsText())
        val branchMeta = json.parseToJsonElement(meta.bodyAsText()).jsonObject["branch_meta"]?.jsonObject
        assertEquals("主线", branchMeta?.get("label")?.jsonPrimitive?.content)
        assertEquals(true, branchMeta?.get("is_mainline")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `dialogue search recover and branch endpoints`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("adv-run"), """{"run_id":"adv-run"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("adv-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """
                {
                  "session_id": "sess1",
                  "run_id": "adv-run",
                  "mode": "observe",
                  "participants": ["贾宝玉", "林黛玉"],
                  "transcript": [
                    {"speaker":"我","message":"他们初见","role":"user","turn_id":"t1","timestamp":"2026-01-01T00:00:00Z"},
                    {"speaker":"贾宝玉","message":"这个妹妹我曾见过的","role":"character","turn_id":"t1","timestamp":"2026-01-01T00:00:01Z"}
                  ],
                  "turn_count": 1,
                  "current_turn_id": "t1"
                }
                """.trimIndent(),
            )
        },
        includeDialogueAdvanced = true,
    ) {
        val base = "/api/web/runs/adv-run/dialogue/sessions/sess1"

        // 搜索命中 transcript
        val search = client.get("$base/search") {
            bearerAuth("test-token")
            url {
                parameters.append("q", "妹妹")
            }
        }
        assertEquals(HttpStatusCode.OK, search.status, search.bodyAsText())
        val items = json.parseToJsonElement(search.bodyAsText()).jsonObject["items"]?.jsonArray.orEmpty()
        assertTrue(items.isNotEmpty(), "search should find transcript hits")

        // 恢复会话
        val recovered = client.post("$base/recover") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, recovered.status, recovered.bodyAsText())
        assertEquals("sess1", json.parseToJsonElement(recovered.bodyAsText()).jsonObject["session_id"]?.jsonPrimitive?.content)

        // 从 turn 分支
        val branched = client.post("$base/branch-turn") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"turn_id":"t1"}""")
        }
        assertEquals(HttpStatusCode.OK, branched.status, branched.bodyAsText())
        val branchJson = json.parseToJsonElement(branched.bodyAsText()).jsonObject
        val branchId = branchJson["session_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(branchId.isNotBlank() && branchId != "sess1")
        // 分支 transcript 仅保留 t1 之前（含 t1）的条目
        assertEquals(2, branchJson["transcript"]?.jsonArray?.size)
        assertEquals("sess1", branchJson["branch_origin"]?.jsonObject?.get("source_session_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `dialogue scene card switch and recommend`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("adv-run"), """{"run_id":"adv-run"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("adv-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """
                {
                  "session_id": "sess1",
                  "run_id": "adv-run",
                  "mode": "observe",
                  "participants": ["贾宝玉", "林黛玉"],
                  "transcript": []
                }
                """.trimIndent(),
            )
            storage.writeTextAtomically(
                storage.getStorageRoot().resolve("scene-cards/scene-a1b2c3/scene-card.json"),
                """
                {"title":"潇湘馆","location":"潇湘馆","atmosphere":"清冷","opening_situation":"夜谈","scene_drive":"推动感情"}
                """.trimIndent(),
            )
        },
        includeDialogueAdvanced = true,
    ) {
        val base = "/api/web/runs/adv-run/dialogue/sessions/sess1"

        // 切换前推荐应命中唯一一张场景卡
        val recommended = client.post("$base/scene-card/recommend") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, recommended.status, recommended.bodyAsText())
        assertEquals(
            "scene-a1b2c3",
            json.parseToJsonElement(recommended.bodyAsText()).jsonObject["recommended_card_id"]?.jsonPrimitive?.content,
        )

        val switched = client.put("$base/scene-card") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"scene_card_id":"scene-a1b2c3","scene_profile":{"title":"潇湘馆"},"transition_message":"众人移步潇湘馆"}""")
        }
        assertEquals(HttpStatusCode.OK, switched.status, switched.bodyAsText())
        val switchedJson = json.parseToJsonElement(switched.bodyAsText()).jsonObject
        assertEquals("scene-a1b2c3", switchedJson["scene_card_id"]?.jsonPrimitive?.content)
        assertEquals("scene-a1b2c3", switchedJson["scene_card"]?.jsonObject?.get("card_id")?.jsonPrimitive?.content)
    }

    @Test
    fun `chapter management CRUD search and export`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(
                storage.getRunManifestPath("chap-run"),
                """{"run_id":"chap-run","artifact_index":{"characters":[]}}""",
            )
        },
        includeChapters = true,
    ) {
        val base = "/api/web/runs/chap-run"

        // 创建章节
        val created = client.post("$base/chapters") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"初见","goal":"宝黛初见","participants":["贾宝玉","林黛玉"],"content":"黛玉初进贾府，众人簇拥。"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val chapterId = json.parseToJsonElement(created.bodyAsText()).jsonObject["chapter_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(chapterId.isNotBlank())

        // 更新章节
        val updated = client.put("$base/chapters/$chapterId") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"初见（改）","goal":"","participants":[],"content":"黛玉初进贾府。"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        assertEquals(
            "初见（改）",
            json.parseToJsonElement(updated.bodyAsText()).jsonObject["title"]?.jsonPrimitive?.content,
        )

        // 书卷搜索命中章节
        val search = client.get("$base/search") {
            bearerAuth("test-token")
            url { parameters.append("query", "黛玉") }
        }
        assertEquals(HttpStatusCode.OK, search.status, search.bodyAsText())
        assertTrue(json.parseToJsonElement(search.bodyAsText()).jsonObject["items"]?.jsonArray?.isNotEmpty() == true)

        // 导出手稿
        val exported = client.get("$base/chapters/export") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, exported.status, exported.bodyAsText())
        assertTrue(exported.bodyAsText().contains("初见（改）"))

        // 重排（仅一章时 order=1）
        val reordered = client.patch("$base/chapters/$chapterId/order") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"target_order":1}""")
        }
        assertEquals(HttpStatusCode.OK, reordered.status, reordered.bodyAsText())

        // 删除
        val deleted = client.delete("$base/chapters/$chapterId") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.bodyAsText())
        assertEquals(
            "deleted",
            json.parseToJsonElement(deleted.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `chapter archive continue sync and ask route`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("chap-run"), """{"run_id":"chap-run"}""")
            // 6 轮有效对话（每轮 user + character 两条）
            val transcript = StringBuilder()
            for (turn in 1..6) {
                transcript.append("""{"speaker":"我","message":"轮次$turn","role":"user","turn_id":"t$turn","timestamp":"2026-01-01T00:00:0${turn}Z"},""")
                transcript.append("""{"speaker":"贾宝玉","message":"回应$turn","role":"character","turn_id":"t$turn","timestamp":"2026-01-01T00:00:0${turn}Z"}""")
                if (turn < 6) transcript.append(",")
            }
            storage.writeTextAtomically(
                storage.getRunDirectory("chap-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """
                {
                  "session_id": "sess1",
                  "run_id": "chap-run",
                  "mode": "observe",
                  "participants": ["贾宝玉", "林黛玉"],
                  "title": "初会话",
                  "transcript": [${transcript}]
                }
                """.trimIndent(),
            )
        },
        includeChapters = true,
    ) {
        val base = "/api/web/runs/chap-run"

        // 归档会话为章节
        val archived = client.post("$base/chapters/archive-session") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"session_id":"sess1","title":"归档章"}""")
        }
        assertEquals(HttpStatusCode.OK, archived.status, archived.bodyAsText())
        val archivedJson = json.parseToJsonElement(archived.bodyAsText()).jsonObject
        val chapterId = archivedJson["chapter_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(chapterId.isNotBlank())
        assertTrue(archivedJson["content"]?.jsonPrimitive?.content?.contains("回应1") == true)

        // 从章节继续写作（创建新会话）
        val continued = client.post("$base/chapters/$chapterId/continue") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, continued.status, continued.bodyAsText())
        val sessionId = json.parseToJsonElement(continued.bodyAsText()).jsonObject["session_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(sessionId.isNotBlank())

        // 同步会话到章节（新续写会话无 transcript，返回章节本身）
        val synced = client.post("$base/chapters/$chapterId/sync-session") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, synced.status, synced.bodyAsText())
        assertEquals(
            chapterId,
            json.parseToJsonElement(synced.bodyAsText()).jsonObject["chapter_id"]?.jsonPrimitive?.content,
        )

        // ask 为 LLM 端点：路由已注册（非 404）
        val ask = client.post("$base/ask") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"question":"贾宝玉是谁"}""")
        }
        assertTrue(ask.status != HttpStatusCode.NotFound, ask.bodyAsText())
    }

    @Test
    fun `reusable cards CRUD and recommend`() = testZaomengApplication(
        includeCards = true,
    ) {
        // 场景卡：创建（必填字段）
        val sceneCreated = client.post("/api/web/scene-cards") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"潇湘馆","location":"大观园","atmosphere":"清冷","opening_situation":"夜谈","scene_drive":"推动感情"}""")
        }
        assertEquals(HttpStatusCode.OK, sceneCreated.status, sceneCreated.bodyAsText())
        val sceneJson = json.parseToJsonElement(sceneCreated.bodyAsText()).jsonObject
        val sceneId = sceneJson["card_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(sceneId.startsWith("scene-"))

        // 列表包含新卡
        val sceneList = client.get("/api/web/scene-cards") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, sceneList.status, sceneList.bodyAsText())
        assertTrue(json.parseToJsonElement(sceneList.bodyAsText()).jsonObject["items"]?.jsonArray?.isNotEmpty() == true)

        // 读取单卡
        val sceneGet = client.get("/api/web/scene-cards/$sceneId") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, sceneGet.status, sceneGet.bodyAsText())
        assertEquals("潇湘馆", json.parseToJsonElement(sceneGet.bodyAsText()).jsonObject["fields"]?.jsonObject?.get("title")?.jsonPrimitive?.content)

        // 更新
        val sceneUpdated = client.put("/api/web/scene-cards/$sceneId") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"潇湘馆（改）","location":"大观园","atmosphere":"清冷","opening_situation":"夜谈","scene_drive":"推动感情"}""")
        }
        assertEquals(HttpStatusCode.OK, sceneUpdated.status, sceneUpdated.bodyAsText())
        assertEquals("潇湘馆（改）", json.parseToJsonElement(sceneUpdated.bodyAsText()).jsonObject["fields"]?.jsonObject?.get("title")?.jsonPrimitive?.content)

        // 推荐命中该卡
        val recommended = client.post("/api/web/scene-cards/recommend") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"observe","participants":["贾宝玉","林黛玉"]}""")
        }
        assertEquals(HttpStatusCode.OK, recommended.status, recommended.bodyAsText())
        assertEquals(sceneId, json.parseToJsonElement(recommended.bodyAsText()).jsonObject["recommended_card_id"]?.jsonPrimitive?.content)

        // 删除
        val sceneDeleted = client.delete("/api/web/scene-cards/$sceneId") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, sceneDeleted.status, sceneDeleted.bodyAsText())
        assertEquals("deleted", json.parseToJsonElement(sceneDeleted.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)

        // 自我卡与开局预设：创建 + 列表
        val selfCreated = client.post("/api/web/self-cards") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"display_name":"读者","scene_identity":"旁观者","interaction_style":"克制"}""")
        }
        assertEquals(HttpStatusCode.OK, selfCreated.status, selfCreated.bodyAsText())
        val selfId = json.parseToJsonElement(selfCreated.bodyAsText()).jsonObject["card_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(selfId.startsWith("card-"))
        assertEquals(HttpStatusCode.OK, client.get("/api/web/self-cards") { bearerAuth("test-token") }.status)

        val openingCreated = client.post("/api/web/opening-presets") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"入府","mode":"observe","participants":["贾宝玉"]}""")
        }
        assertEquals(HttpStatusCode.OK, openingCreated.status, openingCreated.bodyAsText())
        val openingId = json.parseToJsonElement(openingCreated.bodyAsText()).jsonObject["card_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(openingId.startsWith("opening-"))
        assertEquals(HttpStatusCode.OK, client.get("/api/web/opening-presets") { bearerAuth("test-token") }.status)
    }

    @Test
    fun `relations list update and conflict detection`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("rel-run"), """{"run_id":"rel-run","novel_id":"novel-a"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("rel-run").resolve("artifacts/relations/novel-a_relations.md"),
                """
                ---
                novel_id: novel-a
                relations:
                  贾宝玉_林黛玉:
                    trust: 7
                    affection: 9
                    hostility: 1
                    ambiguity: 2
                    relationship_type: 深情
                    relation_change: 日渐亲近
                    conflict_point: 金玉良缘
                    typical_interaction: 拌嘴
                conflicts: []
                ---
                # RELATION_GRAPH
                """.trimIndent(),
            )
        },
        includeRelations = true,
    ) {
        // 列表
        val listed = client.get("/api/web/runs/rel-run/relations") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
        val listedJson = json.parseToJsonElement(listed.bodyAsText()).jsonObject
        assertEquals(1, listedJson["relation_count"]?.jsonPrimitive?.content?.toIntOrNull())
        assertEquals(
            "贾宝玉",
            listedJson["items"]?.jsonArray?.first()?.jsonObject?.get("characters")?.jsonArray?.first()?.jsonPrimitive?.content,
        )

        // 更新：制造高信任 + 高敌意冲突
        val updated = client.patch("/api/web/runs/rel-run/relations/贾宝玉_林黛玉") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"trust":9,"affection":5,"hostility":7,"ambiguity":3,"relationship_type":"对立","relation_change":"反目","conflict_point":"","typical_interaction":""}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val updatedJson = json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals(
            9,
            updatedJson["relations"]?.jsonObject?.get("贾宝玉_林黛玉")?.jsonObject?.get("trust")?.jsonPrimitive?.content?.toIntOrNull(),
        )
        val conflictTags = updatedJson["conflicts"]?.jsonArray?.first()?.jsonObject?.get("tags")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        assertTrue(conflictTags.contains("high_trust_high_hostility"))

        // 未知 pair 返回 404
        val missing = client.patch("/api/web/runs/rel-run/relations/不存在_角色") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"trust":1,"affection":1,"hostility":1,"ambiguity":1}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `run operations estimate export redistill and refresh`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(
                storage.getRunManifestPath("ops-run"),
                """
                {
                  "run_id": "ops-run",
                  "novel_id": "novel-ops",
                  "title": "测试书",
                  "status": "ready",
                  "locked_characters": ["贾宝玉", "林黛玉"],
                  "progress": {"completed_characters": ["贾宝玉"], "total_characters": 2, "completed_count": 1, "stage": "completed"},
                  "novel_sources": [{"source_name": "novel.txt", "source_path": ${JsonPrimitive(storage.getRunDirectory("ops-run").resolve("novel.txt").absolutePath)}, "kind": "initial"}],
                  "artifact_index": {"characters": []}
                }
                """.trimIndent(),
            )
            storage.writeTextAtomically(
                storage.getRunDirectory("ops-run").resolve("chapters/chapter-1.json"),
                """{"chapter_id":"chapter-1","order":1,"title":"第一章","content":"测试内容。"}""",
            )
            storage.writeTextAtomically(
                storage.getRunDirectory("ops-run").resolve("novel.txt"),
                "贾宝玉走进大观园。\n林黛玉站在花下，心想：\n" + "黛玉笑道：“今日天气甚好。”\n".repeat(30),
            )
        },
        includeRunOps = true,
    ) {
        // 采样估算
        val estimated = client.post("/api/web/runs/estimate") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"char_count":40000,"sentence_count":200,"character_count":3,"max_sentences":120,"max_chars":50000}""")
        }
        assertEquals(HttpStatusCode.OK, estimated.status, estimated.bodyAsText())
        val estimateJson = json.parseToJsonElement(estimated.bodyAsText()).jsonObject
        assertTrue(estimateJson["distill_chunk_count"]?.jsonPrimitive?.content?.toIntOrNull()?.let { it >= 1 } == true)
        assertTrue(estimateJson["total_calls"]?.jsonPrimitive?.content?.toIntOrNull()?.let { it >= 1 } == true)

        // 内置书卷（Android 无内置目录 → 空列表）
        val builtins = client.get("/api/web/builtin-novels") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, builtins.status, builtins.bodyAsText())
        assertTrue(json.parseToJsonElement(builtins.bodyAsText()).jsonObject["items"]?.jsonArray?.isEmpty() == true)

        // 导出书卷包（zip）
        val exported = client.get("/api/web/runs/ops-run/export") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, exported.status, exported.bodyAsText())

        // 重新蒸馏（未配置模型 → 400，对齐 Python restart_run_distill）
        val redistilled = client.post("/api/web/runs/ops-run/redistill") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"characters":["贾宝玉","林黛玉"],"novel_name":"","novel_content_base64":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, redistilled.status, redistilled.bodyAsText())

        // resume（未配置模型 → 400）
        val resumed = client.post("/api/web/runs/ops-run/resume-distill") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.BadRequest, resumed.status)

        // 片段推荐
        val recommended = client.post("/api/web/runs/ops-run/redistill/recommend") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"character":"林黛玉","max_segments":2}""")
        }
        assertEquals(HttpStatusCode.OK, recommended.status, recommended.bodyAsText())
        val recommendJson = json.parseToJsonElement(recommended.bodyAsText()).jsonObject
        assertEquals("林黛玉", recommendJson["character"]?.jsonPrimitive?.content)
        assertTrue(recommendJson["segments"]?.jsonArray?.isNotEmpty() == true)

        // 刷新
        val refreshed = client.post("/api/web/runs/ops-run/refresh") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
    }

    @Test
    fun `plugin package inspect install and enhancer state`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("plug-run"), """{"run_id":"plug-run"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("plug-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """{"session_id":"sess1","run_id":"plug-run","mode":"observe","participants":["贾宝玉"],"transcript":[]}""",
            )
        },
        includePluginOps = true,
    ) {
        // 构造最小插件包 zip
        val pluginBytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(pluginBytes.buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("plugin.json"))
            zip.write(
                """{"id":"demo-plugin","name":"演示插件","version":"1.0.0","apiVersion":"1","contributes":{"chatActions":[]}}"""
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        val contentBase64 = java.util.Base64.getEncoder().encodeToString(pluginBytes.toByteArray())

        // 检查插件包
        val inspected = client.post("/api/web/plugins/packages/inspect") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"filename":"demo.zip","content_base64":"$contentBase64"}""")
        }
        assertEquals(HttpStatusCode.OK, inspected.status, inspected.bodyAsText())
        val inspectJson = json.parseToJsonElement(inspected.bodyAsText()).jsonObject
        val token = inspectJson["token"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(token.isNotBlank())
        assertEquals("demo-plugin", inspectJson["plugin"]?.jsonObject?.get("id")?.jsonPrimitive?.content)

        // 安装插件
        val installed = client.post("/api/web/plugins/packages/$token/install") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"confirm_permissions":true,"allow_update":true}""")
        }
        assertEquals(HttpStatusCode.OK, installed.status, installed.bodyAsText())
        assertEquals("demo-plugin", json.parseToJsonElement(installed.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content)

        // 插件出现在列表中
        val pluginList = client.get("/api/web/plugins") { bearerAuth("test-token") }
        assertEquals(HttpStatusCode.OK, pluginList.status, pluginList.bodyAsText())
        assertTrue(
            json.parseToJsonElement(pluginList.bodyAsText()).jsonObject["items"]?.jsonArray
                ?.any { it.jsonObject["id"]?.jsonPrimitive?.content == "demo-plugin" } == true,
        )

        // 增强器状态（会话内存储）
        val enhancer = client.put("/api/web/runs/plug-run/dialogue/sessions/sess1/plugins/demo-plugin/enhancers/expand/state") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, enhancer.status, enhancer.bodyAsText())
        assertEquals(
            true,
            json.parseToJsonElement(enhancer.bodyAsText()).jsonObject["plugin_enhancer_states"]
                ?.jsonObject?.get("demo-plugin")?.jsonObject?.get("expand")?.jsonPrimitive?.booleanOrNull,
        )

        // 插件动作需要 Python 运行时 → 400 而非 404
        val action = client.post("/api/web/runs/plug-run/dialogue/sessions/sess1/plugins/demo-plugin/actions/act1") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"seed_text":"","direction":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, action.status)

        // NPC 生成同理
        val npc = client.post("/api/web/runs/plug-run/dialogue/sessions/sess1/plugins/demo-plugin/npc-generators/gen1") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"direction":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, npc.status)
    }

    @Test
    fun `dialogue advanced llm endpoints are registered not 404`() = testZaomengApplication(
        setup = { storage ->
            storage.writeTextAtomically(storage.getRunManifestPath("adv-run"), """{"run_id":"adv-run"}""")
            storage.writeTextAtomically(
                storage.getRunDirectory("adv-run").resolve("dialogue/sessions/sess1/session_manifest.json"),
                """
                {
                  "session_id": "sess1",
                  "run_id": "adv-run",
                  "mode": "observe",
                  "participants": ["贾宝玉"],
                  "transcript": [
                    {"speaker":"我","message":"他们初见","role":"user","turn_id":"t1","timestamp":"2026-01-01T00:00:00Z"},
                    {"speaker":"贾宝玉","message":"这个妹妹我曾见过的","role":"character","turn_id":"t1","timestamp":"2026-01-01T00:00:01Z"}
                  ]
                }
                """.trimIndent(),
            )
        },
        includeDialogueAdvanced = true,
    ) {
        val base = "/api/web/runs/adv-run/dialogue/sessions/sess1"

        // 这些端点依赖 LLM（测试环境未配置），应返回 500/400 而非 404
        val suggest = client.post("$base/suggest") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"seed_text":"","direction":""}""")
        }
        assertTrue(suggest.status != HttpStatusCode.NotFound, suggest.bodyAsText())

        val director = client.post("$base/director-options") {
            bearerAuth("test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"推进感情","action":"advance"}""")
        }
        assertTrue(director.status != HttpStatusCode.NotFound, director.bodyAsText())

        val correct = client.post("$base/correct-latest") { bearerAuth("test-token") }
        assertTrue(correct.status != HttpStatusCode.NotFound, correct.bodyAsText())

        val deepReview = client.post("$base/deep-review") { bearerAuth("test-token") }
        assertTrue(deepReview.status != HttpStatusCode.NotFound, deepReview.bodyAsText())
    }

    private fun testZaomengApplication(
        setup: (StorageService) -> Unit = {},
        includePersona: Boolean = false,
        includeSessions: Boolean = false,
        includeWorldMemory: Boolean = false,
        includeDialogueAdvanced: Boolean = false,
        includeChapters: Boolean = false,
        includeCards: Boolean = false,
        includeRelations: Boolean = false,
        includeRunOps: Boolean = false,
        includePluginOps: Boolean = false,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val root = Files.createTempDirectory("zaomeng-api-").toFile()
        val storage = StorageService(root)
        setup(storage)
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            configureSecurity("test-token")
            routing {
                healthRoute()
                runsRoute(storage)
                diagnosticsRoute(storage, DiagnosticsService(root, storage))
                runManagementRoutes(RunManagementService(storage, distillExecutor = null), RunPackageService(storage))
                pluginRoutes(PluginService(storage))
                if (includePersona) personaRoutes(PersonaService(storage))
                if (includeSessions) {
                    sessionManagementRoutes(
                        SessionManagementService(storage, DialogueService(storage), WorldMemoryService(storage)),
                    )
                }
                if (includeWorldMemory) worldMemoryRoutes(WorldMemoryService(storage))
                if (includeDialogueAdvanced) {
                    dialogueAdvancedRoutes(DialogueAdvancedService(storage, llm = null, prompts = null))
                }
                if (includeChapters) {
                    chapterManagementRoutes(
                        ChapterManagementService(
                            storage,
                            SessionManagementService(storage, DialogueService(storage)),
                            llm = null,
                            prompts = null,
                        ),
                    )
                }
                if (includeCards) {
                    cardsManagementRoutes(CardsManagementService(storage))
                }
                if (includeRelations) {
                    relationsRoutes(RelationsService(storage))
                }
                if (includeRunOps) {
                    runOperationsRoutes(
                        RunOperationsService(
                            storage,
                            RunManagementService(storage, distillExecutor = null),
                            RunPackageService(storage),
                            DistillExecutor(context = null, storage = storage, llm = null, promptLoader = null),
                        ),
                    )
                }
                if (includePluginOps) {
                    pluginOperationsRoutes(PluginOperationsService(storage, PluginService(storage)))
                }
            }
        }
        try {
            block()
        } finally {
            root.deleteRecursively()
        }
    }
}
