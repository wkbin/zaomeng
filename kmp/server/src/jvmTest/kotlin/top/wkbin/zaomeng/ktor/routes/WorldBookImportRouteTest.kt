package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.EditWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.CreateWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.ManagedWorldBooksDto
import top.wkbin.zaomeng.data.api.SetWorldBookRecursionRequest
import top.wkbin.zaomeng.data.api.SillyTavernPreviewDto
import top.wkbin.zaomeng.data.api.SillyTavernPreviewRequest
import top.wkbin.zaomeng.data.api.WorldBookEntryPlacement
import top.wkbin.zaomeng.data.api.WorldBookGroupSettings
import top.wkbin.zaomeng.data.api.WorldBookKeywordMatching
import top.wkbin.zaomeng.data.api.WorldBookSecondaryLogic
import top.wkbin.zaomeng.data.api.TestWorldBooksRequest
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import top.wkbin.zaomeng.ktor.services.DialoguePayloadBuilder
import top.wkbin.zaomeng.ktor.services.DialogueService
import top.wkbin.zaomeng.ktor.services.RunManagementService
import top.wkbin.zaomeng.ktor.services.RunPackageService
import top.wkbin.zaomeng.ktor.services.SessionManagementService
import top.wkbin.zaomeng.ktor.services.SessionHistoryMutation
import top.wkbin.zaomeng.ktor.services.StorageService

class WorldBookImportRouteTest {
    @Test
    fun `keyword matching options round trip and trial scans only the selected history window`() {
        val path = File.createTempFile("zaomeng-world-matching-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{"name":"林晚",
                "character_book":{"scan_depth":2,"entries":[{"id":1,"keys":["Bell"],"secondary_keys":["red","blue"],
                    "content":"钟响了。","extensions":{"case_sensitive":true,"match_whole_words":true,"selectiveLogic":3}}]}}}""")
            val runId = packages.importPackage(ImportRunPackageRequest("card.json", card)).getValue("run_id").jsonPrimitive.content
            val service = packages.sillyTavern
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                val base = "/api/web/runs/$runId/world-books"
                suspend fun test(input: TestWorldBooksRequest) = client.post("$base/test") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(input))
                }
                suspend fun matches(text: String, history: List<String> = emptyList()): Boolean {
                    val response = test(TestWorldBooksRequest(text, history = history))
                    assertEquals(HttpStatusCode.OK, response.status)
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("injected_count").jsonPrimitive.int == 1
                }
                fun book() = service.listWorldBooks(runId).books.single()
                assertEquals(WorldBookKeywordMatching(caseSensitive = true, wholeWords = true, secondaryLogic = WorldBookSecondaryLogic.AndAll),
                    book().entries.single().matching)
                assertTrue(matches("red blue", listOf("Bell")))
                assertFalse(matches("red blue", listOf("Bell", "older than the window")))
                assertFalse(matches("red blue", listOf("bell")))
                assertFalse(matches("red blue", listOf("Bells")))
                assertFalse(matches("red", listOf("Bell")))
                assertEquals(HttpStatusCode.BadRequest, test(TestWorldBooksRequest("Bell", history = List(32) { "history" })).status)

                val current = book()
                suspend fun save(matching: WorldBookKeywordMatching) =
                    client.patch("$base/character_book/entries/0/content") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(EditWorldBookEntryRequest(current.revision, matching = matching)))
                    }.status
                val edited = WorldBookKeywordMatching(scanDepth = 1, caseSensitive = false, wholeWords = false,
                    secondaryLogic = WorldBookSecondaryLogic.NotAny)
                assertEquals(HttpStatusCode.BadRequest, save(edited.copy(scanDepth = -1)))
                assertEquals(HttpStatusCode.OK, save(edited))
                assertEquals(edited, book().entries.single().matching)
                assertFalse(matches("nothing", listOf("Bell")))
                assertTrue(matches("bells"))
                assertFalse(matches("bells red"))
                val exported = service.exportWorldBook(runId, "character_book")
                    .getValue("entries").jsonObject.getValue("1").jsonObject
                assertEquals(1, exported.getValue("scanDepth").jsonPrimitive.int)
                assertFalse(exported.getValue("caseSensitive").jsonPrimitive.boolean)
                assertFalse(exported.getValue("matchWholeWords").jsonPrimitive.boolean)
                assertEquals(2, exported.getValue("selectiveLogic").jsonPrimitive.int)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `activation delay gates imported lore and persists through entry edits`() {
        val path = File.createTempFile("zaomeng-world-delay-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "character_book":{"entries":[{"id":1,"keys":["铃声"],"content":"有人按铃。","extensions":{"delay":3}}]}}}""")
            val manifest = packages.importPackage(ImportRunPackageRequest("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val standalone = encode("""{"entries":{"0":{"uid":0,"constant":true,"content":"远处有一座钟塔。","delay":132}}}""")
            packages.importPackage(ImportRunPackageRequest("book.json", standalone, targetRunId = runId))
            val books = packages.sillyTavern
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                val base = "/api/web/runs/$runId/world-books"
                suspend fun trial(messageCount: Int) = client.post("$base/test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"铃声","message_count":$messageCount}""")
                }
                fun book(source: String) = books.listWorldBooks(runId).books.single { it.source == source }
                fun exportedDelay(source: String, id: String) = books.exportWorldBook(runId, source)
                    .getValue("entries").jsonObject.getValue(id).jsonObject.getValue("delay").jsonPrimitive.int
                val embedded = book("character_book")
                assertEquals(3, embedded.entries.single().activationDelay)
                assertEquals(3, exportedDelay("character_book", "1"))
                assertEquals(132, exportedDelay("world_book", "0"))
                assertEquals(HttpStatusCode.BadRequest, trial(0).status)
                assertTrue(trial(2).bodyAsText().contains("activation_delay"))
                val atThreshold = Json.parseToJsonElement(trial(3).bodyAsText()).jsonObject
                assertEquals(1, atThreshold.getValue("injected_count").jsonPrimitive.int)

                suspend fun editDelay(delay: Int) = client.patch("$base/character_book/entries/0/content") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(EditWorldBookEntryRequest(embedded.revision, activationDelay = delay)))
                }.status
                assertEquals(HttpStatusCode.BadRequest, editDelay(-1))
                assertEquals(HttpStatusCode.OK, editDelay(0))
                assertEquals(0, exportedDelay("character_book", "1"))
                assertEquals(1, books.testWorldBooks(runId, "铃声").injectedCount)
                assertEquals(HttpStatusCode.Conflict, editDelay(2))
                val standaloneBook = book("world_book")
                val create = client.post("$base/world_book/entries") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(CreateWorldBookEntryRequest(
                        standaloneBook.revision, "门已经关上。", listOf("大门"), activationDelay = 4, sticky = 5, cooldown = 2,
                        group = WorldBookGroupSettings("Gate, Gate,  Road", priority = true, weight = 250, scoring = true),
                    )))
                }
                assertEquals(HttpStatusCode.OK, create.status)
                assertEquals(4, book("world_book").entries.last().activationDelay)
                assertEquals(5, book("world_book").entries.last().sticky)
                assertEquals(2, book("world_book").entries.last().cooldown)
                assertEquals(WorldBookGroupSettings("Gate, Road", true, 250, true), book("world_book").entries.last().group)

                val dialogue = DialogueService(storage)
                val sessionId = SessionManagementService(storage, dialogue)
                    .openDialogueSession(runId, mode = "insert", participants = listOf("林晚"))
                    .getValue("session_id").jsonPrimitive.content
                storage.mutateSessionHistory(runId, sessionId) { current ->
                    val old = buildJsonObject {
                        put("role", "character")
                        put("speaker", "林晚")
                        put("message", "还在路上。")
                    }
                    val snapshot = JsonObject(current.manifest + ("transcript" to JsonArray(List(131) { old })))
                    SessionHistoryMutation(snapshot, Unit)
                }
                val snapshot = storage.loadSessionManifest(runId, sessionId)
                assertTrue(snapshot.getValue("transcript_start").jsonPrimitive.int > 0)
                val payloads = DialoguePayloadBuilder(storage)
                val continuation = payloads.buildTurnPayload(manifest, snapshot, "auto", "继续。", countsCurrentInput = false)
                assertTrue(dialogue.extractWorldBookMatches(continuation).isEmpty())
                val next = payloads.buildTurnPayload(manifest, snapshot, "next", "继续。")
                assertEquals("远处有一座钟塔。", dialogue.extractWorldBookMatches(next).single().excerpt)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `placement changes persist in both formats and honor entry revisions`() {
        val path = File.createTempFile("zaomeng-world-placement-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。","character_book":{"entries":[
                {"id":1,"keys":["北门"],"content":"门边有盏灯。","position":"after_char",
                 "extensions":{"position":8,"custom":"保留"}}
            ]}}}""")
            val runId = packages.importPackage(ImportRunPackageRequest("card.json", card)).getValue("run_id").jsonPrimitive.content
            val book = encode("""{"entries":{}}""")
            packages.importPackage(ImportRunPackageRequest("book.json", book, targetRunId = runId))
            val service = packages.sillyTavern
            fun current(source: String) = service.listWorldBooks(runId).books.single { it.source == source }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                val embedded = current("character_book")
                suspend fun edit(request: EditWorldBookEntryRequest) =
                    client.patch("/api/web/runs/$runId/world-books/${embedded.source}/entries/0/content") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(request))
                    }.status
                fun exported() = service.exportWorldBook(runId, embedded.source)
                    .getValue("entries").jsonObject.getValue("1").jsonObject
                assertEquals(HttpStatusCode.OK, edit(EditWorldBookEntryRequest(embedded.revision, comment = "北门")))
                assertEquals("8", exported().getValue("position").jsonPrimitive.content)
                val revision = current(embedded.source).revision
                val depth = WorldBookEntryPlacement(WorldBookInjectionPosition.AtDepth, 0, WorldBookInjectionRole.Assistant)
                assertEquals(HttpStatusCode.BadRequest, edit(EditWorldBookEntryRequest(revision, placement = depth.copy(depth = -1))))
                assertEquals(HttpStatusCode.OK, edit(EditWorldBookEntryRequest(revision, placement = depth)))
                assertEquals(HttpStatusCode.Conflict, edit(EditWorldBookEntryRequest(revision, placement = depth)))
                val match = service.testWorldBooks(runId, "北门").entries.single()
                assertEquals(WorldBookInjectionPosition.AtDepth, match.position)
                assertEquals(0, match.injectionDepth)
                assertEquals(WorldBookInjectionRole.Assistant, match.injectionRole)
                assertEquals("保留", exported().getValue("extensions").jsonObject.getValue("custom").jsonPrimitive.content)
                assertEquals("2", exported().getValue("role").jsonPrimitive.content)
                val after = WorldBookEntryPlacement(WorldBookInjectionPosition.AfterCharacter)
                val updatedRevision = current(embedded.source).revision
                assertEquals(HttpStatusCode.BadRequest, edit(EditWorldBookEntryRequest(updatedRevision, placement = after.copy(depth = 1))))
                assertEquals(HttpStatusCode.OK, edit(EditWorldBookEntryRequest(updatedRevision, placement = after)))
                assertEquals(WorldBookInjectionPosition.AfterCharacter, current(embedded.source).entries.single().position)
                assertEquals(null, current(embedded.source).entries.single().injectionDepth)
                val outlet = WorldBookEntryPlacement(WorldBookInjectionPosition.Outlet, outletName = " Gate ")
                val beforeOutlet = current(embedded.source).revision
                assertEquals(HttpStatusCode.BadRequest, edit(EditWorldBookEntryRequest(beforeOutlet, placement = outlet.copy(outletName = " "))))
                assertEquals(HttpStatusCode.OK, edit(EditWorldBookEntryRequest(beforeOutlet, placement = outlet)))
                assertEquals("Gate", current(embedded.source).entries.single().outletName)
                assertEquals("7", exported().getValue("position").jsonPrimitive.content)
                assertEquals("Gate", exported().getValue("outletName").jsonPrimitive.content)
                val beforeExamples = WorldBookEntryPlacement(WorldBookInjectionPosition.BeforeExamples)
                val outletRevision = current(embedded.source).revision
                assertEquals(HttpStatusCode.OK, edit(EditWorldBookEntryRequest(outletRevision, placement = beforeExamples)))
                assertEquals(WorldBookInjectionPosition.BeforeExamples, current(embedded.source).entries.single().position)
                assertEquals("5", exported().getValue("position").jsonPrimitive.content)

                val native = current("world_book")
                val response = client.post("/api/web/runs/$runId/world-books/${native.source}/entries") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(CreateWorldBookEntryRequest(
                        revision = native.revision,
                        content = "旅人可在此歇脚。",
                        keywords = emptyList(),
                        constant = true,
                        placement = depth.copy(depth = 1, role = WorldBookInjectionRole.User),
                    )))
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val created = current(native.source).entries.single()
                assertEquals(WorldBookInjectionPosition.AtDepth, created.position)
                assertEquals(1, created.injectionDepth)
                assertEquals(WorldBookInjectionRole.User, created.injectionRole)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `constant entries can be created and switch modes without losing keyword settings`() {
        val path = File.createTempFile("zaomeng-world-constant-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{
                "name":"林晚","first_mes":"欢迎进店。","character_book":{"entries":[]}
            }}""")
            val runId = packages.importPackage(ImportRunPackageRequest("card.json", card)).getValue("run_id").jsonPrimitive.content
            val native = encode("""{"entries":{"8":{
                "uid":8,"key":["水井"],"keysecondary":["山路"],"content":"井口有盖。","selective":true,"order":23
            }}}""")
            packages.importPackage(ImportRunPackageRequest("book.json", native, targetRunId = runId))
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                suspend fun patchMode(source: String, request: EditWorldBookEntryRequest) =
                    client.patch("/api/web/runs/$runId/world-books/$source/entries/0/content") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(request))
                    }
                val service = packages.sillyTavern
                fun current(source: String) = service.listWorldBooks(runId).books.single { it.source == source }
                val embedded = current("character_book")
                val response = client.post("/api/web/runs/$runId/world-books/${embedded.source}/entries") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(CreateWorldBookEntryRequest(
                        revision = embedded.revision,
                        content = "入夜后关闭城门。",
                        keywords = emptyList(),
                        constant = true,
                    )))
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val created = current(embedded.source)
                assertTrue(created.entries.single().constant)
                assertEquals(1, service.testWorldBooks(runId, "无关台词").injectedCount)
                val exported = service.exportWorldBook(runId, created.source).getValue("entries").jsonObject.values.single().jsonObject
                assertTrue(exported.getValue("constant").jsonPrimitive.boolean)
                assertEquals(HttpStatusCode.BadRequest, patchMode(created.source, EditWorldBookEntryRequest(
                    revision = created.revision,
                    constant = false,
                )).status)
                assertEquals(HttpStatusCode.OK, patchMode(created.source, EditWorldBookEntryRequest(
                    revision = created.revision,
                    keywords = listOf("城门"),
                    constant = false,
                )).status)
                assertEquals(0, service.testWorldBooks(runId, "无关台词").injectedCount)
                assertEquals(1, service.testWorldBooks(runId, "城门").injectedCount)

                val standalone = current("world_book")
                assertEquals(HttpStatusCode.OK, patchMode(standalone.source, EditWorldBookEntryRequest(
                    revision = standalone.revision,
                    constant = true,
                )).status)
                assertEquals(1, service.testWorldBooks(runId, "无关台词").injectedCount)
                val changed = current(standalone.source)
                assertEquals(listOf("水井"), changed.entries.single().keywords)
                assertEquals(listOf("山路"), changed.entries.single().secondaryKeywords)
                assertEquals(HttpStatusCode.OK, patchMode(standalone.source, EditWorldBookEntryRequest(
                    revision = changed.revision,
                    constant = false,
                )).status)
                assertEquals(0, service.testWorldBooks(runId, "水井").injectedCount)
                assertEquals(1, service.testWorldBooks(runId, "水井 山路").injectedCount)
                val restored = service.exportWorldBook(runId, standalone.source).getValue("entries").jsonObject.getValue("8").jsonObject
                assertFalse(restored.getValue("constant").jsonPrimitive.boolean)
                assertEquals("23", restored.getValue("order").jsonPrimitive.content)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting an entry preserves other entries and rejects a stale index`() {
        val path = File.createTempFile("zaomeng-world-delete-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{
                "name":"林晚","first_mes":"欢迎进店。","character_book":{"entries":[
                    {"id":4,"keys":["旧钟"],"content":"时钟已经停摆。"},
                    {"id":9,"keys":["路牌"],"content":"路牌指向北方。","extensions":{"custom":"保留"}}
                ]}
            }}""")
            val runId = packages.importPackage(ImportRunPackageRequest("card.json", card)).getValue("run_id").jsonPrimitive.content
            val native = encode("""{"entries":{"8":{"uid":8,"key":["水井"],"content":"井口有盖。"}}}""")
            packages.importPackage(ImportRunPackageRequest("book.json", native, targetRunId = runId))
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                suspend fun deleteEntry(source: String, index: String, revision: String? = null) =
                    client.delete("/api/web/runs/$runId/world-books/$source/entries/$index") {
                        if (revision != null) parameter("revision", revision)
                    }
                val service = packages.sillyTavern
                val books = service.listWorldBooks(runId).books.associateBy { it.source }
                val embedded = books.getValue("character_book")
                assertEquals(1, service.testWorldBooks(runId, "旧钟").injectedCount)
                assertEquals(HttpStatusCode.BadRequest, deleteEntry(embedded.source, "0").status)
                assertEquals(HttpStatusCode.BadRequest, deleteEntry(embedded.source, "invalid", embedded.revision).status)
                assertEquals(HttpStatusCode.NotFound, deleteEntry(embedded.source, "2", embedded.revision).status)
                val response = deleteEntry(embedded.source, "0", embedded.revision)
                assertEquals(HttpStatusCode.OK, response.status)
                val updated = Json.decodeFromString<ManagedWorldBooksDto>(response.bodyAsText()).books.first()
                assertNotEquals(embedded.revision, updated.revision)
                assertEquals("路牌", updated.entries.single().title)
                assertEquals(0, updated.entries.single().index)
                assertEquals(0, service.testWorldBooks(runId, "旧钟").injectedCount)
                assertEquals(HttpStatusCode.Conflict, deleteEntry(embedded.source, "0", embedded.revision).status)
                assertEquals(1, service.testWorldBooks(runId, "路牌").injectedCount)
                val exported = service.exportWorldBook(runId, embedded.source).getValue("entries").jsonObject
                assertEquals(setOf("9"), exported.keys)
                assertEquals("保留", exported.getValue("9").jsonObject.getValue("extensions").jsonObject.getValue("custom").jsonPrimitive.content)

                val standalone = books.getValue("world_book")
                assertEquals(HttpStatusCode.OK, deleteEntry(standalone.source, "0", standalone.revision).status)
                assertTrue(service.listWorldBooks(runId).books.single { it.source == standalone.source }.entries.isEmpty())
                assertTrue(service.exportWorldBook(runId, standalone.source).getValue("entries").jsonObject.isEmpty())
                assertEquals(0, service.testWorldBooks(runId, "水井").injectedCount)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `new entries activate immediately and preserve existing IDs across formats`() {
        val path = File.createTempFile("zaomeng-world-create-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{
                "name":"林晚","first_mes":"欢迎进店。","character_book":{"entries":[{"id":1,"keys":["旧词"],"content":"旧正文"}]}
            }}""")
            val runId = packages.importPackage(ImportRunPackageRequest("card.json", card)).getValue("run_id").jsonPrimitive.content
            val standalone = encode("""{"entries":{"1":{"uid":1,"key":["旧门"],"content":"旧门","disable":true}}}""")
            packages.importPackage(ImportRunPackageRequest("book.json", standalone, targetRunId = runId))
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                suspend fun create(source: String, request: CreateWorldBookEntryRequest) =
                    client.post("/api/web/runs/$runId/world-books/$source/entries") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(request))
                    }
                val service = packages.sillyTavern
                val books = service.listWorldBooks(runId).books.associateBy { it.source }
                val embedded = books.getValue("character_book")
                val request = CreateWorldBookEntryRequest(
                    revision = embedded.revision,
                    content = "看见蓝色路标。",
                    keywords = listOf(" 蓝牌 "),
                    secondaryKeywords = listOf("山路"),
                )
                val response = create(embedded.source, request)
                assertEquals(HttpStatusCode.OK, response.status)
                val updated = Json.decodeFromString<ManagedWorldBooksDto>(response.bodyAsText()).books.first()
                assertEquals(listOf("旧词", "蓝牌"), updated.entries.map { it.title })
                assertTrue(updated.entries.last().enabled)
                assertEquals(0, service.testWorldBooks(runId, "蓝牌").injectedCount)
                assertEquals(1, service.testWorldBooks(runId, "蓝牌 山路").injectedCount)
                assertEquals(HttpStatusCode.Conflict, create(embedded.source, request).status)
                assertEquals(HttpStatusCode.BadRequest, create(embedded.source, request.copy(revision = updated.revision, keywords = emptyList())).status)
                assertEquals(HttpStatusCode.BadRequest, create(embedded.source, request.copy(revision = updated.revision, content = " ")).status)
                assertEquals(2, service.listWorldBooks(runId).books.first().entries.size)

                val native = books.getValue("world_book")
                assertEquals(HttpStatusCode.OK, create(native.source, request.copy(
                    revision = native.revision,
                    comment = "井",
                    keywords = listOf("水井"),
                    secondaryKeywords = emptyList(),
                )).status)
                assertEquals(1, service.testWorldBooks(runId, "水井").injectedCount)
                for (book in books.values) {
                    val exported = service.exportWorldBook(runId, book.source).getValue("entries").jsonObject
                    assertEquals(setOf("1", "2"), exported.keys)
                    assertEquals("看见蓝色路标。", exported.getValue("2").jsonObject.getValue("content").jsonPrimitive.content)
                }
                val exportedNative = service.exportWorldBook(runId, native.source).getValue("entries").jsonObject
                assertTrue(exportedNative.getValue("1").jsonObject.getValue("disable").jsonPrimitive.boolean)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `entry edits preserve imported fields and refresh matching with revision checks`() {
        val path = File.createTempFile("zaomeng-world-edit-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎进店。","character_book":{
                "entries":[{"key":["旧词"],"keys":["旧词"],"content":"旧正文","enabled":true,"extensions":{"probability":100,"custom_field":"保留"}}]
            }}}""")
            val runId = packages.importPackage(ImportRunPackageRequest("card.json", card)).getValue("run_id").jsonPrimitive.content
            val standalone = encode("""{"entries":{"8":{"key":["门"],"content":"旧门","disable":true,"order":52}}}""")
            packages.importPackage(ImportRunPackageRequest("book.json", standalone, targetRunId = runId))
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                suspend fun edit(source: String, request: EditWorldBookEntryRequest) =
                    client.patch("/api/web/runs/$runId/world-books/$source/entries/0/content") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(request))
                    }
                val service = packages.sillyTavern
                val books = service.listWorldBooks(runId).books.associateBy { it.source }
                val embeddedBook = books.getValue("character_book")
                val updated = edit(embeddedBook.source, EditWorldBookEntryRequest(
                    revision = embeddedBook.revision,
                    comment = "新条目",
                    content = "第一行\n第二行",
                    keywords = listOf("新词"),
                    secondaryKeywords = listOf("条件"),
                ))
                assertEquals(HttpStatusCode.OK, updated.status)
                val updatedBook = Json.decodeFromString<ManagedWorldBooksDto>(updated.bodyAsText()).books.first()
                assertEquals("新条目", updatedBook.entries.single().title)
                assertEquals("第一行\n第二行", updatedBook.entries.single().content)
                assertEquals(0, service.testWorldBooks(runId, "旧词 条件").injectedCount)
                assertEquals(0, service.testWorldBooks(runId, "新词").injectedCount)
                assertEquals(1, service.testWorldBooks(runId, "新词 条件").injectedCount)
                val exported = service.exportWorldBook(runId, embeddedBook.source).getValue("entries").jsonObject.values.single().jsonObject
                assertEquals("保留", exported.getValue("extensions").jsonObject.getValue("custom_field").jsonPrimitive.content)
                assertEquals(HttpStatusCode.Conflict, edit(embeddedBook.source, EditWorldBookEntryRequest(embeddedBook.revision, content = "过期正文")).status)
                assertEquals(HttpStatusCode.BadRequest, edit(embeddedBook.source, EditWorldBookEntryRequest(updatedBook.revision, content = " ")).status)

                val standaloneBook = books.getValue("world_book")
                assertEquals(HttpStatusCode.OK, edit(standaloneBook.source, EditWorldBookEntryRequest(standaloneBook.revision, content = "新门")).status)
                val native = service.exportWorldBook(runId, standaloneBook.source).getValue("entries").jsonObject.getValue("8").jsonObject
                assertTrue(native.getValue("disable").jsonPrimitive.boolean)
                assertEquals("门", native.getValue("key").jsonArray.single().jsonPrimitive.content)
                assertEquals("52", native.getValue("order").jsonPrimitive.content)
                assertEquals("新门", native.getValue("content").jsonPrimitive.content)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `recursion switches update only their book and protect against stale changes`() {
        val path = File.createTempFile("zaomeng-world-recursion-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎进店。","character_book":{
                "name":"库房规则","entries":[{"keys":["库房"],"content":"库房有钥匙。"}]
            }}}""")
            val imported = packages.importPackage(ImportRunPackageRequest("card.json", card))
            val runId = imported.getValue("run_id").jsonPrimitive.content
            val book = encode("""{"name":"客栈规则","recursiveScanning":true,"entries":[{"key":["旅人"],"content":"请去库房。"}]}""")
            packages.importPackage(ImportRunPackageRequest("book.json", book, targetRunId = runId))

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                val service = packages.sillyTavern
                val dialogue = DialogueService(storage)
                val sessions = SessionManagementService(storage, dialogue)
                val session = sessions.openDialogueSession(runId = runId, mode = "insert", participants = listOf("林晚"))
                val payloads = DialoguePayloadBuilder(storage)
                suspend fun injectedSources(turnId: String): List<String> {
                    val manifest = requireNotNull(storage.readRunManifest(runId))
                    val payload = payloads.buildTurnPayload(manifest, session, turnId, "旅人到店了。")
                    return dialogue.extractWorldBookMatches(payload).map { it.source }
                }
                val initial = service.listWorldBooks(runId).books.associateBy { it.source }
                val cardBook = initial.getValue("character_book")
                val standalone = initial.getValue("world_book")
                assertFalse(cardBook.recursionEnabled)
                assertTrue(standalone.recursionEnabled)
                assertEquals(1, service.testWorldBooks(runId, "旅人").injectedCount)
                assertEquals(listOf("world_book"), injectedSources("before-toggle"))

                suspend fun setRecursion(source: String, revision: String, enabled: Boolean) =
                    client.patch("/api/web/runs/$runId/world-books/$source/recursion") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(SetWorldBookRecursionRequest(revision, enabled)))
                    }

                val cardResponse = setRecursion(cardBook.source, cardBook.revision, true)
                assertEquals(HttpStatusCode.OK, cardResponse.status)
                val enabled = Json.decodeFromString<ManagedWorldBooksDto>(cardResponse.bodyAsText()).books.associateBy { it.source }
                assertTrue(enabled.getValue(cardBook.source).recursionEnabled)
                assertNotEquals(cardBook.revision, enabled.getValue(cardBook.source).revision)
                assertEquals(standalone.revision, enabled.getValue(standalone.source).revision)
                assertEquals(2, service.testWorldBooks(runId, "旅人").injectedCount)
                assertEquals(listOf("world_book", "character_book"), injectedSources("after-enable"))
                assertEquals(HttpStatusCode.Conflict, setRecursion(cardBook.source, cardBook.revision, false).status)
                assertTrue(service.exportWorldBook(runId, cardBook.source).getValue("recursive_scanning").jsonPrimitive.boolean)
                val greetings = sessions.getCardGreetings(runId)
                assertEquals("欢迎进店。", greetings.options.single().text)

                val standaloneResponse = setRecursion(standalone.source, standalone.revision, false)
                assertEquals(HttpStatusCode.OK, standaloneResponse.status)
                val disabled = Json.decodeFromString<ManagedWorldBooksDto>(standaloneResponse.bodyAsText()).books.associateBy { it.source }
                assertFalse(disabled.getValue(standalone.source).recursionEnabled)
                assertTrue(disabled.getValue(cardBook.source).recursionEnabled)
                assertEquals(1, service.testWorldBooks(runId, "旅人").injectedCount)
                assertEquals(listOf("world_book"), injectedSources("after-disable"))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `preview revision protects both first attachment and replacement`() {
        val path = File.createTempFile("zaomeng-world-import-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"name":"林晚","description":"客栈掌柜。"}""")
            val manifest = packages.importPackage(ImportRunPackageRequest("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val current = bookRequest("当前世界书", runId)
            val incoming = bookRequest("待挂载世界书", runId)

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runManagementRoutes(RunManagementService(storage), packages) }
                }
                suspend fun preview(): SillyTavernPreviewDto {
                    val response = client.post("/api/web/runs/import/sillytavern/preview") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(SillyTavernPreviewRequest(incoming.filename, incoming.contentBase64, true, runId)))
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    return Json.decodeFromString(response.bodyAsText())
                }
                suspend fun submit(revision: String?): HttpStatusCode = client.post("/api/web/runs/import") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(incoming.copy(expectedWorldBookRevision = revision)))
                }.status

                val unattached = preview()
                assertEquals("", unattached.previousBookRevision)
                packages.importPackage(current)
                assertEquals(HttpStatusCode.Conflict, submit(unattached.previousBookRevision))

                val first = preview()
                assertEquals("当前世界书", first.previousBookName)
                assertEquals(1, first.previousEntryCount)
                val revision = assertNotNull(first.previousBookRevision)
                packages.sillyTavern.setEntryEnabled(runId, "world_book", 0, revision, false)
                assertEquals(HttpStatusCode.Conflict, submit(revision))
                val retained = packages.sillyTavern.listWorldBooks(runId).books.single()
                assertEquals("当前世界书", retained.name)
                assertEquals(false, retained.entries.single().enabled)

                val refreshed = preview()
                assertNotEquals(revision, refreshed.previousBookRevision)
                assertEquals(HttpStatusCode.Created, submit(refreshed.previousBookRevision))
                assertEquals("待挂载世界书", packages.sillyTavern.listWorldBooks(runId).books.single().name)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `runs route restores sillytavern entrypoint for card runs`() {
        val path = File.createTempFile("zaomeng-runs-route-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = encode("""{"name":"林晚","description":"客栈掌柜。"}""")
            val manifest = packages.importPackage(ImportRunPackageRequest("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            storage.updateRunManifest(runId) { JsonObject(it + ("entrypoint" to JsonPrimitive("import"))) }

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { runsRoute(storage) }
                }
                val httpList = client.get("/api/web/runs")
                assertEquals(HttpStatusCode.OK, httpList.status)
                val items = Json.parseToJsonElement(httpList.bodyAsText()).jsonObject.getValue("items").jsonArray
                val httpRestored = items.single { it.jsonObject["run_id"]?.jsonPrimitive?.content == runId }
                assertEquals("sillytavern", httpRestored.jsonObject.getValue("entrypoint").jsonPrimitive.content)

                val httpDetail = client.get("/api/web/runs/$runId")
                assertEquals(HttpStatusCode.OK, httpDetail.status)
                val detail = Json.parseToJsonElement(httpDetail.bodyAsText()).jsonObject
                assertEquals("sillytavern", detail.getValue("entrypoint").jsonPrimitive.content)
            }
        } finally {
            db.close()
        }
    }

    private fun bookRequest(name: String, runId: String) = ImportRunPackageRequest(
        "world.json",
        encode("""{"name":"$name","entries":[{"key":["雨"],"content":"雨里请撑伞。"}]}"""),
        targetRunId = runId,
    )

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.encodeToByteArray())
}
