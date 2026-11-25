package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.EditWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.SillyTavernPreviewRequest
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.AuthorNotePosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.data.api.WorldBookGenerationType
import top.wkbin.zaomeng.data.api.WorldBookScanSource
import top.wkbin.zaomeng.data.api.WorldBookTestContext
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.UpdateSillyTavernCardMetadataRequest
import top.wkbin.zaomeng.data.api.SillyTavernCharacterNoteDto
import top.wkbin.zaomeng.data.api.WORLD_BOOK_CONTENT_CHAR_LIMIT
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.platform.JvmPromptSource
import top.wkbin.zaomeng.platform.SillyTavernPng
import java.io.File
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.*

class SillyTavernImportTest {
    @Test
    fun `updating card metadata preserves omitted data and clears legacy notes on request`() = runBlocking {
        val path = File.createTempFile("zaomeng-card-meta-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val importer = RunPackageService(storage)
            val tags = List(33) { JsonPrimitive("Tag $it") }
            val source = """{"name":"守夜人","description":"看守渡口。","creator":"旧作者",
                 "creatorcomment":"旧说明","tags":${JsonArray(tags)},"first_mes":"旧首句"}"""
            val runId = importer.importPackage(request("metadata.json", source)).getValue("run_id").jsonPrimitive.content
            val persona = PersonaService(storage, relations = RelationsService(storage))
            val original = assertNotNull(persona.getReview(runId, "守夜人").cardMetadata)
            assertTrue(original.tagsTruncated)
            val saved = persona.updateCardMetadata(runId, "守夜人", UpdateSillyTavernCardMetadataRequest(
                original.revision, creator = "新作者", creatorNotes = "", characterVersion = "2.0",
            ))
            assertEquals("新作者", saved.creator)
            assertEquals("", saved.creatorNotes)
            assertTrue(saved.tagsTruncated)
            assertFailsWith<CardMetadataChangedException> {
                persona.updateCardMetadata(runId, "守夜人", UpdateSillyTavernCardMetadataRequest(original.revision, creator = "冲突"))
            }
            val exported = persona.exportSillyTavernCard(runId, "守夜人").getValue("data").jsonObject
            assertEquals("新作者", exported.getValue("creator").jsonPrimitive.content)
            assertEquals("2.0", exported.getValue("character_version").jsonPrimitive.content)
            assertEquals("", exported.getValue("creator_notes").jsonPrimitive.content)
            assertEquals(JsonArray(tags), exported.getValue("tags"))
            assertEquals("旧首句", exported.getValue("first_mes").jsonPrimitive.content)

            val updated = persona.updateCardMetadata(runId, "守夜人", UpdateSillyTavernCardMetadataRequest(
                saved.revision, tags = listOf(" 港口 ", "港口", "旅行"),
            ))
            assertEquals(listOf("港口", "旅行"), updated.tags)
            assertFalse(updated.tagsTruncated)
        } finally {
            db.close()
        }
    }

    @Test
    fun `editing card openings preserves slots and existing sessions while updating exports`() = runBlocking {
        val path = File.createTempFile("zaomeng-openings-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"守夜人",
                "first_mes":"旧首句","alternate_greetings":["","旧备用"],
                "nickname":"阿夜","group_only_greetings":["大家好"],"creator_notes_multilingual":{"en":"Port setting"},
                "extensions":{"foreign":"保留"},"character_book":{"entries":[{"content":"旧码头","id":1}]}}}"""
            val runId = packages.importPackage(request("opening.json", card)).getValue("run_id").jsonPrimitive.content
            val sessions = SessionManagementService(storage, DialogueService(storage))
            val original = sessions.getCardGreetings(runId)
            assertEquals(1, original.nextAlternateIndex)
            val old = sessions.openDialogueSession(runId, "insert", listOf("守夜人"), cardGreetingIndex = 2)
            val saved = sessions.updateCardGreeting(runId, UpdateCardGreetingRequest(original.revision, 1, "新增备用"))
            assertEquals(listOf(0, 1, 2), saved.options.map { it.index })
            assertEquals(3, saved.nextAlternateIndex)
            assertFailsWith<CardGreetingChangedException> {
                sessions.updateCardGreeting(runId, UpdateCardGreetingRequest(original.revision, 2, "过期修改"))
            }
            val primary = sessions.updateCardGreeting(runId, UpdateCardGreetingRequest(saved.revision, 0, "新首句"))
            val cleared = sessions.updateCardGreeting(runId, UpdateCardGreetingRequest(primary.revision, 2, ""))
            assertEquals(listOf(0, 1), cleared.options.map { it.index })
            assertEquals(2, cleared.nextAlternateIndex)
            val fresh = sessions.openDialogueSession(runId, "insert", listOf("守夜人"), cardGreetingIndex = 1)
            fun opening(session: JsonObject) = session.getValue("transcript").jsonArray.single().jsonObject
                .getValue("message").jsonPrimitive.content
            assertEquals("新增备用", opening(fresh))
            val oldId = old.getValue("session_id").jsonPrimitive.content
            assertEquals("旧备用", opening(sessions.getDialogueSession(runId, oldId)))
            val json = PersonaService(storage, relations = RelationsService(storage)).exportSillyTavernCard(runId, "守夜人")
            assertEquals("chara_card_v3", json.getValue("spec").jsonPrimitive.content)
            assertEquals("3.0", json.getValue("spec_version").jsonPrimitive.content)
            val png = SillyTavernPng.writeCard(cardPng("chara" to card), json.toString().encodeToByteArray(), isV3 = true)
            assertContains(png.toString(Charsets.ISO_8859_1), "tEXtccv3\u0000")
            assertFalse(png.toString(Charsets.ISO_8859_1).contains("tEXtchara\u0000"))
            val exported = SillyTavernImport.parseCard(SillyTavernImport.readPngCard(png)).data
            assertEquals(json.getValue("data").jsonObject, exported)
            assertEquals("阿夜", exported.getValue("nickname").jsonPrimitive.content)
            assertEquals("大家好", exported.getValue("group_only_greetings").jsonArray.single().jsonPrimitive.content)
            assertEquals("Port setting", exported.getValue("creator_notes_multilingual").jsonObject.getValue("en").jsonPrimitive.content)
            assertEquals("新首句", exported.getValue("first_mes").jsonPrimitive.content)
            assertEquals(listOf("新增备用", ""), exported.getValue("alternate_greetings").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("保留", exported.getValue("extensions").jsonObject.getValue("foreign").jsonPrimitive.content)
            assertTrue("character_book" in exported)
        } finally {
            db.close()
        }
    }

    @Test
    fun `legacy card metadata uses creatorcomment and bounds display without shortening exports`() {
        val notes = "旧渡口".repeat(5_400)
        val card = """{"name":"守夜人","description":"看守旧渡口。","creatorcomment":"$notes"}"""
        val parsed = SillyTavernImport.parseCard(card.encodeToByteArray())
        val metadata = SillyTavernImport.cardMetadata(parsed)
        assertEquals(notes.take(16_000), metadata.creatorNotes)
        assertTrue(metadata.creatorNotesTruncated)
        assertEquals(emptyList(), metadata.tags)
        val exported = SillyTavernExport.characterCard("守夜人", emptyMap(), parsed).getValue("data").jsonObject
        assertEquals(notes, exported.getValue("creator_notes").jsonPrimitive.content)
    }

    @Test
    fun `world book decorators control activation and only the body reaches matching and injection`() {
        val world = Json.parseToJsonElement("""{"recursiveScanning":true,"entries":{
            "0":{"comment":"Forced","key":["missing"],"keysecondary":["also missing"],"order":100,
                "content":"@@activate\r\n@@dont_activate\r\nThe bell rings."},
            "1":{"comment":"Suppressed","constant":true,"content":"@@dont_activate\nFalse alarm."},
            "2":{"comment":"Disabled","disable":true,"content":"@@activate\nDisabled lore."},
            "3":{"comment":"Delayed","delay":3,"content":"@@activate\nDelayed lore."},
            "4":{"comment":"Probability","probability":0,"content":"@@activate\nUnlucky lore."},
            "5":{"comment":"Recursion","key":["bell"],"scanDepth":0,"content":"The keeper answers."},
            "6":{"comment":"Decorator key","key":["@@activate"],"scanDepth":0,"content":"Must stay out."},
            "7":{"comment":"No body","content":"@@activate\n"},
            "8":{"comment":"Later mention","constant":true,"content":"The sign reads:\n@@dont_activate"},
            "9":{"comment":"Fallback","content":"@@extension_hook\n@@@activate\nFallback lore."},
            "10":{"comment":"Unneeded fallback","content":"@@@activate\nUntriggered lore."}
        }}""").jsonObject
        val messages = listOf("Enter")
        val results = SillyTavernImport.evaluateBooks(null, world, messages).associateBy { it.title }
        val forced = results.getValue("Forced")
        assertTrue(forced.forcedActivation)
        assertTrue(forced.keywords.isEmpty())
        assertEquals("The bell rings.", forced.excerpt)
        assertEquals("injected", forced.status)
        assertEquals("decorator_suppressed", results.getValue("Suppressed").status)
        assertEquals("disabled", results.getValue("Disabled").status)
        assertEquals("activation_delay", results.getValue("Delayed").status)
        assertEquals("probability", results.getValue("Probability").status)
        assertEquals("empty", results.getValue("No body").status)
        assertEquals("no_keyword", results.getValue("Decorator key").status)
        assertEquals("no_keyword", results.getValue("Unneeded fallback").status)
        assertEquals("The sign reads:\n@@dont_activate", results.getValue("Later mention").excerpt)
        assertTrue(results.getValue("Fallback").forcedActivation)
        assertEquals(1, results.getValue("Recursion").recursionDepth)
        assertEquals(
            setOf("The bell rings.", "The sign reads:\n@@dont_activate", "Fallback lore.", "The keeper answers."),
            SillyTavernImport.evaluateBooks(null, world, messages, collectDiagnostics = false).map { it.excerpt }.toSet(),
        )
        val exportedEntry = SillyTavernExport.worldBook(world).getValue("entries").jsonObject.getValue("0").jsonObject
        assertEquals("@@activate\r\n@@dont_activate\r\nThe bell rings.", exportedEntry.getValue("content").jsonPrimitive.content)

        val embedded = Json.parseToJsonElement("""{"entries":[{"content":"@@activate\nCard lore."}]}""").jsonObject
        assertEquals("Card lore.", SillyTavernImport.evaluateBooks(embedded, null, messages).single().excerpt)
    }

    @Test
    fun `lore keeps full bodies and applies the shared text budget before recursion`() {
        val prefix = "水".repeat(300)
        val first = prefix + "远处的钟声"
        val second = prefix + "另一条记录"
        val book = Json.parseToJsonElement("""{"recursive_scanning":true,"entries":{
            "1":{"constant":true,"order":100,"content":"$first"},
            "2":{"constant":true,"order":90,"content":"$second"},
            "3":{"constant":true,"order":80,"ignoreBudget":true,"content":"${"废".repeat(WORLD_BOOK_CONTENT_CHAR_LIMIT)}虚假触发"},
            "4":{"key":["远处的钟声"],"scanDepth":0,"content":"递归命中"},
            "5":{"key":["虚假触发"],"scanDepth":0,"content":"不可达"}
        }}""").jsonObject
        val matches = SillyTavernImport.evaluateBooks(null, book, listOf("开始"))
        assertEquals(listOf(first, second, "递归命中"), matches.filter { it.status == "injected" }.map { it.excerpt })
        assertEquals("content_budget", matches.single { it.excerpt.startsWith("废") }.status)
        assertEquals("no_keyword", matches.single { it.excerpt == "不可达" }.status)

        val remaining = 320
        val big = "甲".repeat(WORLD_BOOK_CONTENT_CHAR_LIMIT - remaining)
        val noRoom = "乙".repeat(remaining + 1)
        val fits = "丙".repeat(remaining)
        val exact = listOf(big, noRoom, fits).map { text ->
            buildJsonObject {
                put("constant", true)
                put("content", text)
            }
        }
        val bounded = SillyTavernImport.evaluateLore(exact, emptyList())
        assertEquals(listOf(big, fits), bounded.filter { it.status == "injected" }.map { it.excerpt })
        assertEquals("content_budget", bounded[1].status)
    }

    @Test
    fun `world book budget exemptions survive recursion and respect the total cap`() {
        val book = Json.parseToJsonElement("""{"recursive_scanning":true,"entries":{
            "1":{"constant":true,"content":"Old map","order":10},
            "2":{"constant":true,"content":"Ordinary second","order":9},
            "3":{"key":["map"],"content":"Exempt recursive match","ignoreBudget":true,"scanDepth":0},
            "4":{"constant":true,"content":"Exempt direct match","ignoreBudget":true,"order":20},
            "5":{"constant":true,"content":"Unlucky exempt","ignoreBudget":true,"probability":0}
        }}""").jsonObject
        val results = SillyTavernImport.evaluateBooks(null, book, listOf("Enter"), availableSlots = 1)
        assertEquals(setOf("Old map", "Exempt direct match", "Exempt recursive match"),
            results.filter { it.status == "injected" }.map { it.excerpt }.toSet())
        assertEquals("budget", results.single { it.excerpt == "Ordinary second" }.status)
        assertEquals("probability", results.single { it.excerpt == "Unlucky exempt" }.status)
        assertEquals("Exempt direct match", SillyTavernImport.evaluateBooks(
            null, book, listOf("Enter"), availableSlots = 0, collectDiagnostics = false,
        ).single().excerpt)

        val excessive = List(DialoguePromptRules.WORLD_FACT_LIMIT + 2) { index ->
            buildJsonObject {
                put("constant", true)
                put("ignoreBudget", true)
                put("content", "Exemption $index")
            }
        }
        val limited = SillyTavernImport.evaluateLore(excessive, emptyList(), availableSlots = 0)
        assertEquals(DialoguePromptRules.WORLD_FACT_LIMIT, limited.count { it.status == "injected" })
        assertEquals(2, limited.count { it.status == "total_budget" })
    }

    @Test
    fun `exempt lore reaches the prompt even if locked facts fill the normal budget`() = runBlocking {
        val path = File.createTempFile("zaomeng-world-budget-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val store = RoomDocumentStore(db)
            val storage = StorageService(path, store, DomainStore(path, db.domainDao(), store))
            val packages = RunPackageService(storage)
            val fallbackLore = "甲".repeat(300) + "补充线索甲"
            val positionedLore = "乙".repeat(300) + "补充线索乙"
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "character_book":{"entries":[
                    {"id":1,"constant":true,"content":"普通条目","insertion_order":100},
                    {"id":2,"constant":true,"content":"$fallbackLore","extensions":{"ignore_budget":true}},
                    {"id":3,"content":"@@activate\n$positionedLore","extensions":{"position":0,"ignore_budget":true}}
                ]}}}"""
            val manifest = packages.importPackage(request("budget.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val world = buildJsonObject {
                put("facts", buildJsonArray {
                    repeat(DialoguePromptRules.WORLD_FACT_LIMIT) { index ->
                        add(buildJsonObject {
                            put("fact_id", "fact-$index")
                            put("summary", "锁定事实 $index。")
                            put("locked", true)
                        })
                    }
                })
            }
            storage.writeTextAtomically(storage.getRunDirectory(runId) / "world_memory.json", world.toString())
            val dialogue = DialogueService(storage)
            val session = SessionManagementService(storage, dialogue)
                .createDialogueSession(runId, mode = "insert", participants = listOf("林晚"))
            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "budget", "开始。")
            assertEquals(listOf(fallbackLore, positionedLore), dialogue.extractWorldBookMatches(payload).map { it.excerpt })
            val prompt = DialoguePromptBuilder(PromptLoader(JvmPromptSource())).buildDialogueLlmMessages(payload)
            for (text in listOf(fallbackLore, positionedLore, "锁定事实 17")) {
                assertTrue(prompt.any { it.content.orEmpty().contains(text) }, text.takeLast(10))
            }
            assertFalse(prompt.any { it.content.orEmpty().contains("@@activate") })

            val service = packages.sillyTavern
            val book = service.listWorldBooks(runId).books.single()
            assertTrue(book.entries[1].ignoreBudget)
            service.editBookEntry(runId, book.source, 1, EditWorldBookEntryRequest(book.revision, ignoreBudget = false))
            val exported = service.exportWorldBook(runId, book.source).getValue("entries").jsonObject
            assertFalse(exported.getValue("2").jsonObject.getValue("ignoreBudget").jsonPrimitive.boolean)
            assertTrue(exported.getValue("3").jsonObject.getValue("ignoreBudget").jsonPrimitive.boolean)
        } finally {
            db.close()
        }
    }

    @Test
    fun `world book scans only enabled card and session sources`() = runBlocking {
        val path = File.createTempFile("zaomeng-world-sources-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val store = RoomDocumentStore(db)
            val storage = StorageService(path, store, DomainStore(path, db.domainDao(), store))
            val packages = RunPackageService(storage)
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "description":"有一把银钥匙","personality":"极为谨慎","creator_notes":"附录标记，完整作者备注。",
                "scenario":"原始场景","extensions":{"depth_prompt":{"prompt":"秘密线索","depth":4}},
                "character_book":{"entries":[
                    {"id":1,"keys":["银钥匙"],"secondary_keys":["附录标记"],"content":"角色资料条目",
                        "extensions":{"match_character_description":true,"match_creator_notes":true}},
                    {"id":2,"keys":["极为谨慎"],"secondary_keys":["秘密线索"],"content":"深度条目",
                        "extensions":{"match_character_personality":true,"match_character_depth_prompt":true}},
                    {"id":3,"keys":["治钟匠"],"secondary_keys":["临时车站"],"content":"会话条目",
                        "extensions":{"match_persona_description":true,"match_scenario":true}},
                    {"id":4,"keys":["附录标记"],"content":"未开启条目"},
                    {"id":5,"keys":["附录标记"],"content":"零深度条目","extensions":{"scan_depth":0,"match_creator_notes":true}}
                ]}}}"""
            val manifest = packages.importPackage(request("sources.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val dialogue = DialogueService(storage)
            val session = SessionManagementService(storage, dialogue).createDialogueSession(
                runId, mode = "insert", participants = listOf("林晚"),
                sceneProfile = buildJsonObject { put("location", "临时车站") },
                selfProfile = buildJsonObject {
                    put("display_name", "旅人")
                    put("scene_identity", "治钟匠")
                },
            )
            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "scan", "开始。")
            val excerpts = dialogue.extractWorldBookMatches(payload).map { it.excerpt }.toSet()
            assertEquals(setOf("角色资料条目", "深度条目", "会话条目"), excerpts)
            val prompt = DialoguePromptBuilder(PromptLoader(JvmPromptSource())).buildDialogueLlmMessages(payload)
            assertTrue(prompt.none { it.content.orEmpty().contains("完整作者备注") })

            val service = packages.sillyTavern
            assertEquals(2, service.testWorldBooks(runId, "开始。").injectedCount)
            val context = WorldBookTestContext("治钟匠", "临时车站")
            assertEquals(3, service.testWorldBooks(runId, "开始。", context = context).injectedCount)
            val book = service.listWorldBooks(runId).books.single()
            val matching = book.entries.first().matching
            assertEquals(listOf(WorldBookScanSource.CharacterDescription, WorldBookScanSource.CreatorNotes), matching.sources)
            service.editBookEntry(runId, book.source, 0, EditWorldBookEntryRequest(
                book.revision, matching = matching.copy(sources = listOf(WorldBookScanSource.CharacterDescription)),
            ))
            val exported = service.exportWorldBook(runId, book.source)
                .getValue("entries").jsonObject.getValue("1").jsonObject
            assertTrue(exported.getValue("matchCharacterDescription").jsonPrimitive.boolean)
            assertFalse(exported.getValue("matchCreatorNotes").jsonPrimitive.boolean)
            assertEquals(2, service.testWorldBooks(runId, "开始。", context = context).injectedCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun `world book restricts generation types for replies and suggestion workflows`() = runBlocking {
        val path = File.createTempFile("zaomeng-world-type-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val store = RoomDocumentStore(db)
            val storage = StorageService(path, store, DomainStore(path, db.domainDao(), store))
            val packages = RunPackageService(storage)
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "character_book":{"entries":[
                    {"id":1,"constant":true,"content":"正常","extensions":{"triggers":["normal"]}},
                    {"id":2,"constant":true,"content":"续写","extensions":{"triggers":["continue"]}},
                    {"id":3,"constant":true,"content":"修正","extensions":{"triggers":["regenerate"]}},
                    {"id":4,"constant":true,"content":"代写","extensions":{"triggers":["impersonate"]}},
                    {"id":5,"constant":true,"content":"建议","extensions":{"triggers":["quiet"]}},
                    {"id":6,"constant":true,"content":"备用","extensions":{"triggers":["swipe"]}},
                    {"id":7,"constant":true,"content":"未知","extensions":{"triggers":["future_mode"]}}
                ]}}}"""
            val manifest = packages.importPackage(request("type.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val dialogue = DialogueService(storage)
            val session = SessionManagementService(storage, dialogue)
                .openDialogueSession(runId, mode = "insert", participants = listOf("林晚"))
            val payloadBuilder = DialoguePayloadBuilder(storage)
            fun lore(payload: Map<String, Any?>) = dialogue.extractWorldBookMatches(payload).single().excerpt
            assertEquals("正常", lore(payloadBuilder.buildTurnPayload(manifest, session, "normal", "请说。")))
            val opening = JsonObject(session + ("transcript" to JsonArray(emptyList())))
            assertEquals("正常", lore(payloadBuilder.buildTurnPayload(manifest, opening, "open", "开场。", countsCurrentInput = false)))
            assertEquals("续写", lore(payloadBuilder.buildTurnPayload(manifest, session, "cont", "继续。", countsCurrentInput = false)))
            assertEquals("修正", lore(payloadBuilder.buildTurnPayload(
                manifest, session, "regen", "请说。", generationType = WorldBookGenerationType.Regenerate,
            )))
            assertEquals("代写", lore(payloadBuilder.buildSuggestionPayload(manifest, session)))
            assertEquals("建议", lore(payloadBuilder.buildDirectorPayload(manifest, session, "继续情节")))

            val service = packages.sillyTavern
            val embedded = service.listWorldBooks(runId).books.single()
            assertTrue(embedded.entries.last().hasUnknownTriggers)
            val swipeTrial = service.testWorldBooks(runId, "试", generationType = WorldBookGenerationType.Swipe)
            assertEquals("备用", swipeTrial.entries.single { it.status == "injected" }.excerpt)
            assertEquals("generation_type", swipeTrial.entries.single { it.excerpt == "未知" }.status)
            service.editBookEntry(runId, embedded.source, 6, EditWorldBookEntryRequest(
                embedded.revision, comment = "保留未知类型",
            ))
            assertEquals("future_mode", service.exportWorldBook(runId, embedded.source)
                .getValue("entries").jsonObject.getValue("7").jsonObject.getValue("triggers").jsonArray.single().jsonPrimitive.content)
            val revision = service.listWorldBooks(runId).books.single().revision
            val unrestricted = service.editBookEntry(runId, embedded.source, 6, EditWorldBookEntryRequest(
                revision, triggers = emptyList(),
            ))
            assertFalse(unrestricted.books.single().entries.last().hasUnknownTriggers)
            assertEquals(2, service.testWorldBooks(runId, "试").injectedCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun `world book groups score and choose across sources before probability`() {
        val card = Json.parseToJsonElement("""{"entries":[
            {"id":1,"keys":["gate"],"content":"Lower score","insertion_order":200,
             "extensions":{"group":"Gate","group_override":true,"use_group_scoring":true}},
            {"id":2,"keys":["gate"],"content":"Zero weight","extensions":{"group":"Weather","group_weight":0}},
            {"id":3,"keys":["gate"],"content":"Prioritized","extensions":{"group":"Prop","group_override":true,"probability":0}},
            {"id":4,"keys":["gate"],"content":"Other prop","extensions":{"group":"Prop"}}
        ]}""").jsonObject
        val world = Json.parseToJsonElement("""{"entries":{
            "1":{"key":["gate","lantern"],"content":"Higher score","group":"Gate","useGroupScoring":true},
            "2":{"key":["gate"],"content":"Nonzero weight","group":"Weather","groupWeight":100}
        }}""").jsonObject
        val results = SillyTavernImport.evaluateBooks(card, world, listOf("gate and lantern"), probabilitySeed = "turn")
        val statuses = results.associate { it.excerpt to it.status }
        assertEquals("injected", statuses["Higher score"])
        assertEquals("group_suppressed", statuses["Lower score"])
        assertEquals("injected", statuses["Nonzero weight"])
        assertEquals("group_suppressed", statuses["Zero weight"])
        assertEquals("probability", statuses["Prioritized"])
        assertEquals("group_suppressed", statuses["Other prop"])
        assertEquals(results, SillyTavernImport.evaluateBooks(card, world, listOf("gate and lantern"), probabilitySeed = "turn"))
    }

    @Test
    fun `world book group selected directly excludes recursive alternatives in overlapping groups`() {
        val world = Json.parseToJsonElement("""{"recursive_scanning":true,"entries":{
            "1":{"key":["gate"],"content":"A bell rings","group":"Gate, Sound","order":90,"groupOverride":true},
            "2":{"key":["gate"],"content":"A different gate","group":"Gate","order":20},
            "3":{"key":["bell"],"content":"A different sound","group":"Sound","order":100,"groupOverride":true},
            "4":{"key":["bell"],"content":"An ungrouped entry"}
        }}""").jsonObject
        val results = SillyTavernImport.evaluateBooks(null, world, listOf("gate"), probabilitySeed = "turn")
        assertEquals(setOf("A bell rings", "An ungrouped entry"), results.filter { it.status == "injected" }.map { it.excerpt }.toSet())
        assertEquals("group_suppressed", results.single { it.excerpt == "A different sound" }.status)
    }

    @Test
    fun `world book sticky and cooldown consume only committed history and rewind on fork`() = runBlocking {
        val path = File.createTempFile("zaomeng-world-timing-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "character_book":{"entries":[
                    {"id":7,"keys":["钟楼"],"content":"钟楼有三扇门。",
                        "extensions":{"sticky":4,"cooldown":3,"group":"Tower","scan_depth":1}},
                    {"id":8,"keys":["向前走"],"content":"道路向前延伸。",
                        "extensions":{"group":"Tower","group_override":true,"scan_depth":1}}
                ]}}}"""
            val manifest = packages.importPackage(request("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val dialogue = DialogueService(storage)
            val sessions = SessionManagementService(storage, dialogue)
            val session = sessions.openDialogueSession(runId, mode = "insert", participants = listOf("林晚"))
            val sessionId = session.getValue("session_id").jsonPrimitive.content
            val openingId = session.getValue("current_turn_id").jsonPrimitive.content
            val payloadBuilder = DialoguePayloadBuilder(storage)
            suspend fun payload(turn: String, message: String, id: String = sessionId) =
                payloadBuilder.buildTurnPayload(manifest, storage.loadSessionManifest(runId, id), turn, message)
            suspend fun commit(turn: String, message: String, payload: Map<String, Any?>) {
                dialogue.commitTurn(
                    runId, sessionId, turn, message, messageKind = "dialogue", inputSpeaker = "旅人",
                    responses = listOf(DialogueResponse("林晚", "明白。")),
                    worldBookMatches = dialogue.extractWorldBookMatches(payload),
                    worldBookTimingDelta = payload[WorldBookTiming.DELTA] as? JsonObject,
                )
            }
            val first = payload("trigger", "钟楼")
            assertFalse(dialogue.extractWorldBookMatches(first).single().stickyActive)
            assertNotNull(first[WorldBookTiming.DELTA])
            assertNull(storage.loadSessionManifest(runId, sessionId)[WorldBookTiming.FIELD])
            assertEquals(first[WorldBookTiming.DELTA], payload("trigger", "钟楼")[WorldBookTiming.DELTA])
            commit("trigger", "钟楼", first)
            val started = storage.loadSessionManifest(runId, sessionId)
            assertNull(leanSession(started)[WorldBookTiming.FIELD])
            assertTrue(transcriptOf(withTranscriptCount(started)).none { WorldBookTiming.DELTA in it })
            commit("trigger", "钟楼", first)
            assertEquals(started[WorldBookTiming.FIELD], storage.loadSessionManifest(runId, sessionId)[WorldBookTiming.FIELD])
            val duringSticky = payload("sticky", "向前走")
            assertTrue(dialogue.extractWorldBookMatches(duringSticky).single().stickyActive)
            assertNull(duringSticky[WorldBookTiming.DELTA])
            commit("sticky", "向前走", duringSticky)
            val expired = payload("cool-start", "钟楼")
            assertTrue(dialogue.extractWorldBookMatches(expired).isEmpty())
            assertNotNull(expired[WorldBookTiming.DELTA])
            commit("cool-start", "钟楼", expired)
            val cooling = payload("cool-active", "钟楼")
            assertTrue(dialogue.extractWorldBookMatches(cooling).isEmpty())
            commit("cool-active", "钟楼", cooling)
            assertFalse(dialogue.extractWorldBookMatches(payload("cool-end", "钟楼")).single().stickyActive)

            storage.mutateSessionHistory(runId, sessionId) { current ->
                val intervening = buildJsonObject {
                    put("role", "character")
                    put("message", "往前走。")
                }
                val extended = current.manifest.getValue("transcript").jsonArray + List(130) { intervening }
                SessionHistoryMutation(JsonObject(current.manifest + ("transcript" to JsonArray(extended))), Unit)
            }
            assertTrue(storage.loadSessionManifest(runId, sessionId).getValue("transcript_start").jsonPrimitive.int > 0)
            val advanced = DialogueAdvancedService(storage, llm = null, prompts = null)
            val oldBranch = advanced.branchFromTurn(runId, sessionId, openingId).getValue("session_id").jsonPrimitive.content
            assertEquals("道路向前延伸。", dialogue.extractWorldBookMatches(payload("branch-before", "向前走", oldBranch)).single().excerpt)
            val stickyBranch = advanced.branchFromTurn(runId, sessionId, "trigger").getValue("session_id").jsonPrimitive.content
            assertTrue(dialogue.extractWorldBookMatches(payload("branch-after", "向前走", stickyBranch)).single().stickyActive)
        } finally {
            db.close()
        }
    }

    @Test
    fun `author note persists and its world book anchors share the selected role and depth`() = runBlocking {
        val path = File.createTempFile("zaomeng-author-note-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "character_book":{"entries":[
                    {"id":1,"constant":true,"content":"留意门外。","extensions":{"position":2}},
                    {"id":2,"constant":true,"content":"不要替旅人回答。","position":3}
                ]}
            }}"""
            val manifest = packages.importPackage(request("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val dialogue = DialogueService(storage)
            val sessions = SessionManagementService(storage, dialogue)
            val fresh = sessions.openDialogueSession(runId, mode = "insert", participants = listOf("林晚"))
            val sessionId = fresh.getValue("session_id").jsonPrimitive.content
            val note = AuthorNoteDto("保持{{char}}的口吻。", depth = 1, role = WorldBookInjectionRole.User)
            assertFailsWith<IllegalArgumentException> { sessions.updateAuthorNote(runId, sessionId, note.copy(depth = -1)) }
            assertEquals(note, sessions.updateAuthorNote(runId, sessionId, note))
            val persisted = sessions.getDialogueSession(runId, sessionId)
            assertEquals(note, Json.decodeFromJsonElement<AuthorNoteDto>(persisted.getValue("author_note")))
            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, persisted, "turn", "开门。")
            val builder = DialoguePromptBuilder(PromptLoader(JvmPromptSource()))
            val messages = builder.buildDialogueLlmMessages(payload)
            assertEquals("开门。", messages.last().content)
            val noteMessage = messages[messages.lastIndex - 1]
            assertEquals("user", noteMessage.role)
            assertEquals("留意门外。\n保持林晚的口吻。\n不要替旅人回答。", noteMessage.content)
            assertEquals(listOf(WorldBookInjectionPosition.BeforeAuthorNote, WorldBookInjectionPosition.AfterAuthorNote),
                dialogue.extractWorldBookMatches(payload).map { it.position })
            dialogue.commitTurn(runId, sessionId, "turn", "开门。", messageKind = "dialogue", inputSpeaker = "旅人",
                responses = listOf(DialogueResponse("林晚", "请进。")))
            assertEquals(note, Json.decodeFromJsonElement<AuthorNoteDto>(
                sessions.getDialogueSession(runId, sessionId).getValue("author_note"),
            ))
            val intervalNote = note.copy(interval = 3)
            assertFailsWith<IllegalArgumentException> {
                sessions.updateAuthorNote(runId, sessionId, intervalNote.copy(interval = 0))
            }
            assertEquals(intervalNote, sessions.updateAuthorNote(runId, sessionId, intervalNote))
            val payloadBuilder = DialoguePayloadBuilder(storage)
            val early = payloadBuilder.buildTurnPayload(manifest, storage.loadSessionManifest(runId, sessionId), "second", "谢谢。")
            assertTrue(dialogue.extractWorldBookMatches(early).isEmpty())
            assertFalse(builder.buildDialogueLlmMessages(early).any { it.content.orEmpty().contains("保持林晚的口吻。") })
            dialogue.commitTurn(runId, sessionId, "second", "谢谢。", messageKind = "dialogue", inputSpeaker = "旅人",
                responses = listOf(DialogueResponse("林晚", "不用谢。"), DialogueResponse("旁白", "灯亮了。")))
            val afterSecond = storage.loadSessionManifest(runId, sessionId)
            val due = payloadBuilder.buildTurnPayload(manifest, afterSecond, "third", "坐下。")
            assertEquals(2, dialogue.extractWorldBookMatches(due).size)
            assertTrue(builder.buildDialogueLlmMessages(due).any { it.content == noteMessage.content })
            val continued = payloadBuilder.buildTurnPayload(manifest, afterSecond, "continue", "继续。", countsCurrentInput = false)
            assertTrue(dialogue.extractWorldBookMatches(continued).isEmpty())
            val delegated = payloadBuilder.buildTurnPayload(manifest, afterSecond, "delegated", "坐下。", speakerOverride = "林晚")
            assertTrue(dialogue.extractWorldBookMatches(delegated).isEmpty())
            for (position in listOf(AuthorNotePosition.BeforePrompt, AuthorNotePosition.AfterPrompt)) {
                sessions.updateAuthorNote(runId, sessionId, intervalNote.copy(position = position))
                val snapshot = storage.loadSessionManifest(runId, sessionId)
                val prompt = builder.buildDialogueLlmMessages(payloadBuilder.buildTurnPayload(manifest, snapshot, "third", "坐下。"))
                assertEquals("system", prompt.first().role)
                val system = prompt.first().content.orEmpty()
                val noteIndex = system.indexOf(assertNotNull(noteMessage.content))
                val characterContextIndex = system.indexOf("STATIC_CHARACTER_CONTEXT")
                assertTrue(noteIndex >= 0)
                when (position) {
                    AuthorNotePosition.BeforePrompt -> assertTrue(characterContextIndex > noteIndex)
                    AuthorNotePosition.AfterPrompt -> assertTrue(characterContextIndex in 0 until noteIndex)
                    AuthorNotePosition.InChat -> error("Unexpected position")
                }
                assertTrue(prompt.drop(1).none { it.content.orEmpty().contains("保持林晚的口吻。") })
            }
            sessions.updateAuthorNote(runId, sessionId, note.copy(enabled = false))
            val disabled = DialoguePayloadBuilder(storage)
                .buildTurnPayload(manifest, sessions.getDialogueSession(runId, sessionId), "next", "谢谢。")
            assertTrue(dialogue.extractWorldBookMatches(disabled).isEmpty())
            assertFalse(builder.buildDialogueLlmMessages(disabled).any { it.content.orEmpty().contains("留意门外。") })
        } finally {
            db.close()
        }
    }

    @Test
    fun `world book examples wrap card examples in SillyTavern order and work without card examples`() {
        val embedded = Json.parseToJsonElement("""{"entries":[
            {"id":1,"constant":true,"content":"<START>\n{{char}}: 角色前高","insertion_order":90,
             "position":"before_char","extensions":{"position":5}},
            {"id":2,"constant":true,"content":"{{char}}: 角色前低","insertion_order":30,"position":5},
            {"id":3,"constant":true,"content":"<START>","insertion_order":100,"position":5}
        ]}""").jsonObject
        val native = Json.parseToJsonElement("""{"entries":{
            "8":{"constant":true,"content":"{{user}}: 示例后高","position":6,"order":80},
            "9":{"constant":true,"content":"<start>\n{{user}}: 示例后低","position":6,"order":20}
        }}""").jsonObject
        val matches = SillyTavernImport.evaluateBooks(embedded, native, emptyList())
        assertEquals("empty", matches.single { it.excerpt == "<START>" }.status)
        val selected = matches.filter { it.status == "injected" }
        val facts = selected.map {
            mapOf("category" to "sillytavern_lore", "summary" to it.excerpt, "world_book_position" to it.position)
        }
        val payload = mapOf(
            "input" to mapOf("speaker" to "旅人", "participants" to listOf("林晚"), "message" to "试聊"),
            "sillytavern_card" to mapOf("name" to "林晚", "mes_example" to "<START>\n林晚: 原卡示例"),
            "world_book_matches" to selected,
            "memory_context" to mapOf("world_facts" to facts),
        )
        val builder = DialoguePromptBuilder(PromptLoader(JvmPromptSource()))
        val messages = builder.buildDialogueLlmMessages(payload)
        val text = messages.first().content.orEmpty()
        val lines = listOf("林晚: 角色前高", "林晚: 角色前低", "林晚: 原卡示例", "旅人: 示例后低", "旅人: 示例后高")
        assertContains(text, "【对话示例】\n" + lines.joinToString("\n\n") { "<START>\n$it" })
        assertFalse(messages.drop(1).any { it.content.orEmpty().contains("示例后低") })
        assertContains(
            builder.buildDialogueLlmMessages(payload - "sillytavern_card").first().content.orEmpty(),
            "【对话示例】\n" + lines.filterNot { it == "林晚: 原卡示例" }.joinToString("\n\n") { "<START>\n$it" },
        )
        val card = SillyTavernImport.parseCard(buildJsonObject {
            put("name", "林晚")
            put("first_mes", "欢迎。")
            put("character_book", embedded)
        }.toString().encodeToByteArray())
        val exported = SillyTavernExport.characterCard("林晚", emptyMap(), card)
        val cardEntry = exported.getValue("data").jsonObject.getValue("character_book").jsonObject
            .getValue("entries").jsonArray[1].jsonObject
        assertEquals(5, cardEntry.getValue("extensions").jsonObject.getValue("position").jsonPrimitive.int)
        val exportedNative = SillyTavernExport.worldBook(native).getValue("entries").jsonObject
        assertEquals(6, exportedNative.getValue("8").jsonObject.getValue("position").jsonPrimitive.int)
    }

    @Test
    fun `only referenced world book outlets expand in native card prompts`() = runBlocking {
        val path = File.createTempFile("zaomeng-world-outlet-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "scenario":"门前\n{{OUTLET:: Gate }}\n门后{{outlet::gate}}",
                "post_history_instructions":"保持简洁。",
                "character_book":{"entries":[
                    {"id":1,"constant":true,"content":"{{char}}值夜。","insertion_order":90,
                     "position":"after_char","extensions":{"position":7,"outletName":"Gate"}},
                    {"id":2,"constant":true,"content":"没有引用的内容。","insertion_order":60,"position":7,"outletName":"unused"},
                    {"id":3,"constant":true,"content":"没有名称的内容。","position":7},
                    {"id":4,"constant":true,"content":"{{char}}值夜。","insertion_order":120,"position":7,"outletName":"other"}
                ]}
            }}"""
            val manifest = packages.importPackage(request("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val standalone = """{"entries":{"8":{"constant":true,"content":"{{user}}可入。","position":7,"outletName":"Gate","order":30}}}"""
            val updatedManifest = packages.importPackage(request("world.json", standalone, runId))
            val dialogue = DialogueService(storage)
            val session = SessionManagementService(storage, dialogue).openDialogueSession(
                runId, mode = "insert", participants = listOf("林晚"),
                selfProfile = buildJsonObject { put("display_name", "旅人") },
            )
            val input = "当前输入 {{outlet::Gate}}"
            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(updatedManifest, session, "turn", input)
            assertEquals(listOf("Gate", "Gate"), dialogue.extractWorldBookMatches(payload).map { it.outletName })
            val request = DialoguePromptBuilder(PromptLoader(JvmPromptSource())).buildDialogueLlmMessages(payload)
            assertContains(request.first().content.orEmpty(), "【场景】\n门前\n林晚值夜。\n旅人可入。\n门后")
            assertEquals(input, request.last { it.role == "user" }.content)
            val body = request.joinToString("\n") { it.content.orEmpty() }
            assertFalse("没有引用的内容" in body)
            assertFalse("没有名称的内容" in body)
            assertFalse("{{outlet::gate}}" in body)
            assertEquals(1, body.split("林晚值夜。").size - 1)
            val trial = packages.sillyTavern.testWorldBooks(runId, "测试")
            assertEquals("missing_outlet", trial.entries.single { it.excerpt == "没有名称的内容。" }.status)
            val exported = packages.sillyTavern.exportWorldBook(runId, "character_book")
                .getValue("entries").jsonObject.getValue("1").jsonObject
            assertEquals(7, exported.getValue("position").jsonPrimitive.int)
            assertEquals("Gate", exported.getValue("outletName").jsonPrimitive.content)
            val exportedCard = PersonaService(storage, relations = RelationsService(storage)).exportSillyTavernCard(runId, "林晚")
            val exportedEntries = exportedCard.getValue("data").jsonObject.getValue("character_book").jsonObject.getValue("entries").jsonArray
            val extensions = exportedEntries[1].jsonObject.getValue("extensions").jsonObject
            assertEquals(7, extensions.getValue("position").jsonPrimitive.int)
            assertEquals("unused", extensions.getValue("outletName").jsonPrimitive.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun `depth lore keeps role order in requests and round trips both export formats`() {
        val embedded = Json.parseToJsonElement("""{"entries":[
            {"id":1,"constant":true,"content":"先注入的用户条目","insertion_order":30,
             "position":"after_char","extensions":{"position":4,"depth":1,"role":1}},
            {"id":2,"constant":true,"content":"后注入的用户条目","insertion_order":80,"position":4,"depth":1,"role":1},
            {"id":3,"constant":true,"content":"零深度助手条目","position":4,"depth":0,"role":2}
        ]}""").jsonObject
        val standalone = Json.parseToJsonElement("""{"entries":{"8":{
            "constant":true,"content":"默认深度系统条目","position":4
        }}}""").jsonObject
        val matches = SillyTavernImport.evaluateBooks(embedded, standalone, emptyList(), collectDiagnostics = false)
        assertEquals(setOf(WorldBookInjectionPosition.AtDepth), matches.map { it.position }.toSet())
        assertEquals(4, matches.single { it.excerpt == "默认深度系统条目" }.injectionDepth)
        assertEquals(WorldBookInjectionRole.System, matches.single { it.excerpt == "默认深度系统条目" }.injectionRole)
        val payload = mapOf(
            "input" to mapOf("message" to "当前输入"),
            "history" to listOf(mapOf("role" to "character", "speaker" to "林晚", "message" to "先前的回复")),
            "sillytavern_card" to mapOf(
                "name" to "林晚", "post_history_instructions" to "后置指令", "depth_prompt" to "默认角色注记",
            ),
            "world_book_matches" to matches,
            "memory_context" to mapOf("world_facts" to matches.map {
                mapOf("category" to "sillytavern_lore", "summary" to it.excerpt, "world_book_position" to it.position)
            }),
        )
        val builder = DialoguePromptBuilder(PromptLoader(JvmPromptSource()))
        val prompt = builder.buildDialogueLlmMessages(payload)
        assertEquals(
            listOf(
                "system" to "默认角色注记\n默认深度系统条目",
                "assistant" to "先前的回复",
                "user" to "先注入的用户条目\n后注入的用户条目",
                "user" to "当前输入",
                "assistant" to "零深度助手条目",
            ),
            prompt.drop(2).dropLast(1).map { it.role to it.content },
        )
        assertTrue(prompt.last().content.orEmpty().startsWith("后置指令"))
        assertEquals(prompt.last(), DialogueService.applyPacingInstruction(prompt, "brief").last())
        for (entry in matches) {
            assertEquals(1, prompt.sumOf { it.content.orEmpty().split(entry.excerpt).size - 1 })
        }
        val groupPrompt = builder.buildDialogueLlmMessages(payload - "sillytavern_card")
        assertTrue(groupPrompt.none { it.content.orEmpty().contains("默认角色注记") })
        assertEquals("林晚：先前的回复", groupPrompt[3].content)
        val sharedBoundary = matches.map {
            if (it.injectionRole == WorldBookInjectionRole.System) it.copy(injectionDepth = 0) else it
        }
        val sharedPrompt = builder.buildDialogueLlmMessages(payload + ("world_book_matches" to sharedBoundary))
        assertEquals(listOf("assistant", "system"), sharedPrompt.dropLast(1).takeLast(2).map { it.role })

        val nativeBook = SillyTavernExport.worldBook(embedded)
        val native = nativeBook.getValue("entries").jsonObject
        assertEquals(4, native.getValue("1").jsonObject.getValue("position").jsonPrimitive.int)
        assertEquals(1, native.getValue("1").jsonObject.getValue("depth").jsonPrimitive.int)
        val original = buildJsonObject {
            put("name", "林晚")
            put("first_mes", "欢迎。")
            put("character_book", embedded)
        }
        val parsed = SillyTavernImport.parseCard(original.toString().encodeToByteArray())
        val exportedCard = SillyTavernExport.characterCard("林晚", emptyMap(), parsed)
        val book = exportedCard.getValue("data").jsonObject.getValue("character_book").jsonObject
        val depthEntry = book.getValue("entries").jsonArray.last().jsonObject.getValue("extensions").jsonObject
        assertEquals(4, depthEntry.getValue("position").jsonPrimitive.int)
        assertEquals(0, depthEntry.getValue("depth").jsonPrimitive.int)
        assertEquals(2, depthEntry.getValue("role").jsonPrimitive.int)
        val expectedPlacement = matches.filter { it.source == "character_book" }
            .associate { it.excerpt to Triple(it.position, it.injectionDepth, it.injectionRole) }
        val roundTrips = listOf(
            SillyTavernImport.evaluateBooks(book, null, emptyList()),
            SillyTavernImport.evaluateBooks(null, nativeBook, emptyList()),
        )
        for (roundTrip in roundTrips) {
            assertEquals(expectedPlacement, roundTrip.associate {
                it.excerpt to Triple(it.position, it.injectionDepth, it.injectionRole)
            })
        }
    }

    @Test
    fun `world book priority selects entries before insertion order lays out the prompt`() {
        val book = Json.parseToJsonElement("""{"entries":{
            "0":{"constant":true,"content":"后排列的世界书条目","position":0,"order":90},
            "1":{"constant":true,"content":"先排列的世界书条目","position":0,"order":30},
            "2":{"constant":true,"content":"因预算被排除的条目","position":0,"order":10}
        }}""").jsonObject
        val matches = SillyTavernImport.evaluateBooks(
            null, book, emptyList(), availableSlots = 2, collectDiagnostics = false,
        )
        assertEquals(listOf(90, 30), matches.map { it.insertionOrder })
        val prompt = DialoguePromptBuilder(PromptLoader(JvmPromptSource())).buildDialogueLlmMessages(
            mapOf("world_book_matches" to matches),
        ).first().content.orEmpty()
        val first = prompt.indexOf("先排列的世界书条目")
        val second = prompt.indexOf("后排列的世界书条目")
        assertTrue(first in 0 until second)
        assertTrue(second < prompt.indexOf("STATIC_CHARACTER_CONTEXT"))
        assertFalse("因预算被排除的条目" in prompt)
    }

    @Test
    fun `JSON card is playable and both lore formats feed turn context`() = runBlocking {
        val path = File.createTempFile("zaomeng-card-", ".db").apply { deleteOnExit() }
            .absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val store = RoomDocumentStore(db)
            val storage = StorageService(path, store, DomainStore(path, db.domainDao(), store))
            val importer = RunPackageService(storage)
            val card = """
                {"spec":"chara_card_v2","data":{"name":"林晚","description":"{{char}}是掌柜 --- 也是画师。",
                 "creator":"北港工作室","character_version":"1.2","creator_notes":"从客栈开场。\n随时可以查看世界书。",
                 "tags":["客栈","旅行","客栈",3,""],
                 "scenario":"{{user}}来到客栈。","mes_example":"<START>\n{{user}}: 有空房吗？\n{{char}}: 请稍等。",
                 "system_prompt":"{{original}}\n以{{char}}的口吻应答。","post_history_instructions":"让{{user}}自行决定下一步。",
                 "extensions":{"depth_prompt":{"prompt":"{{char}}等{{user}}提问。","depth":0,"role":"assistant","custom":true},"keep":"扩展原值"},
                 "first_mes":"{{user}}，欢迎进店。","alternate_greetings":["","{{char}}向{{user}}点头。"],"character_book":{"entries":[
                   {"keys":["客栈"],"content":"客栈后院有一口井。","enabled":true,"position":"before_char","extensions":{"position":1}},
                   {"keys":["秘道"],"content":"隐藏的内容","enabled":true,"position":"before_char"}
                 ]}}}
            """.trimIndent()
            val cardPreview = importer.previewSillyTavern(
                SillyTavernPreviewRequest("card.json", request("card.json", card).contentBase64, testText = "客栈"),
            )
            assertEquals("林晚", cardPreview.characterName)
            assertEquals("你，欢迎进店。", cardPreview.greeting)
            assertEquals(1, cardPreview.alternateGreetingCount)
            assertEquals(2, cardPreview.entryCount)
            assertEquals(listOf("injected", "no_keyword"), cardPreview.testedEntries?.map { it.status })
            assertEquals(
                listOf(WorldBookInjectionPosition.AfterCharacter, WorldBookInjectionPosition.BeforeCharacter),
                cardPreview.testedEntries?.map { it.position },
            )
            assertFalse("高级插入位置" in cardPreview.unsupportedRules)
            assertTrue(storage.listRunIds().isEmpty())
            val manifest = importer.importPackage(request("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val profile = manifest.getValue("artifact_index").jsonObject.getValue("characters").jsonArray
                .single().jsonObject.getValue("profile_file").jsonPrimitive.content.toPath()
            assertTrue(storage.readText(profile).startsWith("---\n"))
            assertEquals("ready", manifest.getValue("status").jsonPrimitive.content)

            val book = """{"name":"客栈规则","entries":{
                "0":{"key":["客栈"],"content":"今晚客栈打烊。","order":99,"position":0},
                "1":{"key":["客栈"],"content":"不该注入的设定。","order":100,"probability":0,"useProbability":true,"position":8}
            }}"""
            val upload = request("book.json", book, runId)
            val trial = importer.previewSillyTavern(
                SillyTavernPreviewRequest(upload.filename, upload.contentBase64, worldBook = true, targetRunId = runId, testText = "客栈"),
            )
            val blocked = trial.testedEntries.orEmpty().single { it.status == "probability" }
            assertEquals(0.0, blocked.triggerProbability)
            assertEquals(listOf("客栈"), blocked.keywords)
            assertFalse("概率触发" in trial.unsupportedRules)
            assertContains(trial.unsupportedRules, "高级插入位置")
            importer.importPackage(upload)
            val replacement = request("book.json", """{"name":"新书","entries":{}}""")
            val replacementPreview = importer.previewSillyTavern(
                SillyTavernPreviewRequest("book.json", replacement.contentBase64, worldBook = true, targetRunId = runId),
            )
            assertEquals("客栈规则", replacementPreview.previousBookName)
            assertEquals(2, replacementPreview.previousEntryCount)
            assertEquals(0, replacementPreview.entryCount)
            assertFailsWith<IllegalArgumentException> { importer.importPackage(request("invalid.json", "{}", runId)) }
            val dialogue = DialogueService(storage)
            val sessions = SessionManagementService(storage, dialogue)
            assertEquals(listOf(0, 2), sessions.getCardGreetings(runId).options.map { it.index })
            val alternate = sessions.openDialogueSession(
                runId = runId,
                mode = "insert",
                participants = listOf("林晚"),
                selfProfile = buildJsonObject { put("display_name", "旅人") },
                cardGreetingIndex = 2,
            )
            assertEquals("林晚向旅人点头。", alternate.getValue("transcript").jsonArray.single().jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals(2, alternate.getValue("card_greeting_index").jsonPrimitive.int)
            val alternateId = alternate.getValue("session_id").jsonPrimitive.content
            assertEquals(2, sessions.listDialogueSessions(runId, offset = 0, limit = 10).items
                .single { it.sessionId == alternateId }.cardGreetingIndex)
            assertFailsWith<IllegalArgumentException> {
                sessions.openDialogueSession(runId, mode = "insert", participants = listOf("林晚"), cardGreetingIndex = 1)
            }
            assertFailsWith<IllegalArgumentException> {
                sessions.openDialogueSession(
                    runId, mode = "insert", participants = listOf("林晚"), sceneCardId = "custom", cardGreetingIndex = 2,
                )
            }
            assertEquals(1, storage.listDialogueSessionManifests(runId).size)
            val session = sessions.openDialogueSession(
                runId = runId,
                mode = "insert",
                participants = listOf("林晚"),
                selfProfile = buildJsonObject {
                    put("display_name", "旅人")
                    put("scene_identity", "来店避雨的旅人")
                },
            )
            val greeting = session.getValue("transcript").jsonArray.single().jsonObject
            assertEquals("旅人，欢迎进店。", greeting.getValue("message").jsonPrimitive.content)
            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "next", "客栈有空房吗？")
            val prompt = DialoguePromptBuilder(PromptLoader(JvmPromptSource())).buildDialogueLlmMessages(payload)
            assertEquals(listOf("system", "system", "assistant", "user", "assistant", "system"), prompt.map { it.role })
            assertContains(prompt.first().content.orEmpty(), "以林晚的口吻应答。")
            assertContains(prompt.first().content.orEmpty(), "旅人来到客栈。")
            assertContains(prompt.first().content.orEmpty(), "林晚: 请稍等。")
            assertContains(prompt.first().content.orEmpty(), "来店避雨的旅人")
            val rolePrompt = prompt.first().content.orEmpty()
            assertTrue(rolePrompt.indexOf("今晚客栈打烊。") in 0 until rolePrompt.indexOf("【角色描述】"))
            assertTrue(rolePrompt.indexOf("客栈后院有一口井。") in
                (rolePrompt.indexOf("【角色描述】") + 1) until rolePrompt.indexOf("【对话示例】"))
            for (text in listOf("今晚客栈打烊。", "客栈后院有一口井。")) {
                assertEquals(1, prompt.sumOf { message -> message.content.orEmpty().split(text).size - 1 })
            }
            assertFalse("{{original}}" in prompt.first().content.orEmpty())
            assertEquals("旅人，欢迎进店。", prompt[2].content)
            assertEquals("客栈有空房吗？", prompt[3].content)
            assertEquals("林晚等旅人提问。", prompt[4].content)
            assertEquals(1, prompt.count { it.content.orEmpty().contains("林晚等旅人提问。") })
            assertContains(prompt.last().content.orEmpty(), "让旅人自行决定下一步。")
            assertContains(prompt.last().content.orEmpty(), "NDJSON")
            assertEquals(prompt.last(), DialogueService.applyPacingInstruction(prompt, "brief").last())
            val plot = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "plot", "转到次日", messageKind = "plot")
            assertNull(plot["sillytavern_card"])
            val facts = (payload.getValue("memory_context") as Map<*, *>)["world_facts"] as List<*>
            assertEquals(
                listOf("今晚客栈打烊。", "客栈后院有一口井。"),
                facts.map { (it as Map<*, *>)["summary"] },
            )
            val matches = dialogue.extractWorldBookMatches(payload)
            assertEquals(listOf("world_book", "character_book"), matches.map { it.source })
            assertEquals(
                listOf(WorldBookInjectionPosition.BeforeCharacter, WorldBookInjectionPosition.AfterCharacter),
                matches.map { it.position },
            )
            assertEquals(listOf("客栈"), matches.first().keywords)
            val committed = dialogue.commitTurn(
                runId = runId,
                sessionId = session.getValue("session_id").jsonPrimitive.content,
                turnId = "next",
                message = "客栈有空房吗？",
                messageKind = "dialogue",
                inputSpeaker = "旅人",
                responses = listOf(DialogueResponse("林晚", "今晚打烊了。")),
                worldBookMatches = matches,
            )
            val reply = Json.decodeFromJsonElement<TranscriptItemDto>(
                committed.getValue("transcript").jsonArray.last(),
            )
            assertEquals(matches, reply.worldBookMatches)
            val newest = pageTranscript(committed, offset = 0, limit = 1, order = "desc").items.single()
            assertEquals(matches, newest.worldBookMatches)

            val worldBooks = importer.sillyTavern
            val listed = worldBooks.listWorldBooks(runId).books
            val embedded = listed.single { it.source == "character_book" }
            val standalone = listed.single { it.source == "world_book" }
            assertEquals(listOf("客栈"), embedded.entries.first().keywords)
            assertEquals(2, worldBooks.testWorldBooks(runId, "客栈").injectedCount)
            val disabled = worldBooks.setEntryEnabled(runId, embedded.source, 0, embedded.revision, false)
                .books.single { it.source == embedded.source }
            assertFalse(disabled.entries.first().enabled)
            assertEquals("隐藏的内容", disabled.entries[1].content)
            assertEquals(1, worldBooks.testWorldBooks(runId, "客栈").injectedCount)
            assertFailsWith<WorldBookChangedException> {
                worldBooks.setEntryEnabled(runId, embedded.source, 0, embedded.revision, true)
            }
            val changedRun = requireNotNull(storage.readRunManifest(runId))
            val disabledContext = DialoguePayloadBuilder(storage).buildTurnPayload(changedRun, committed, "later", "客栈")
            assertEquals(listOf("world_book"), dialogue.extractWorldBookMatches(disabledContext).map { it.source })
            val standaloneDisabled = worldBooks.setEntryEnabled(runId, standalone.source, 0, standalone.revision, false)
                .books.single { it.source == standalone.source }
            assertFalse(standaloneDisabled.entries.first().enabled)
            assertEquals(0, worldBooks.testWorldBooks(runId, "客栈").injectedCount)
            val persona = PersonaService(storage, relations = RelationsService(storage))
            assertFalse(persona.getQualityReport(runId, "林晚").applicable)
            val review = persona.getReview(runId, "林晚")
            assertEquals("{{char}}是掌柜 --- 也是画师。", review.cardFields?.get("description"))
            assertEquals("{{original}}\n以{{char}}的口吻应答。", review.cardFields?.get("system_prompt"))
            assertEquals("让{{user}}自行决定下一步。", review.cardFields?.get("post_history_instructions"))
            val metadata = assertNotNull(review.cardMetadata)
            assertEquals("北港工作室", metadata.creator)
            assertEquals("1.2", metadata.characterVersion)
            assertEquals("从客栈开场。\n随时可以查看世界书。", metadata.creatorNotes)
            assertEquals(listOf("客栈", "旅行"), metadata.tags)
            assertFalse(metadata.creatorNotesTruncated)
            val unchangedCard = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
            assertEquals("{{char}}是掌柜 --- 也是画师。", unchangedCard.getValue("description").jsonPrimitive.content)
            assertEquals(
                "{{char}}等{{user}}提问。",
                unchangedCard.getValue("extensions").jsonObject.getValue("depth_prompt").jsonObject.getValue("prompt").jsonPrimitive.content,
            )
            persona.saveReview(runId, "林晚", mapOf("temperament_type" to "沉稳"))
            val personalityEdited = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
            assertEquals("{{char}}是掌柜 --- 也是画师。", personalityEdited.getValue("description").jsonPrimitive.content)
            assertEquals("沉稳", personalityEdited.getValue("personality").jsonPrimitive.content)
            persona.saveReview(runId, "林晚", mapOf("core_identity" to "改过的人物设定"))
            val exportedCard = persona.exportSillyTavernCard(runId, "林晚")
            val cardData = SillyTavernImport.parseCard(exportedCard.toString().encodeToByteArray()).data
            assertEquals("chara_card_v2", exportedCard.getValue("spec").jsonPrimitive.content)
            assertEquals("改过的人物设定", cardData.getValue("description").jsonPrimitive.content)
            assertEquals("沉稳", cardData.getValue("personality").jsonPrimitive.content)
            assertEquals("{{user}}，欢迎进店。", cardData.getValue("first_mes").jsonPrimitive.content)
            assertEquals("{{char}}向{{user}}点头。", cardData.getValue("alternate_greetings").jsonArray[1].jsonPrimitive.content)
            val cardBook = cardData.getValue("character_book").jsonObject
            assertFalse(cardBook.getValue("entries").jsonArray.first().jsonObject.getValue("enabled").jsonPrimitive.boolean)
            val editedDescription = "{{char}}看守旧桥。\n\n" + "保留卡片原文".repeat(260) + "末尾"
            val saved = persona.saveReview(runId, "林晚", mapOf("description" to editedDescription, "scenario" to "雨后的桥头"))
            assertEquals(editedDescription, saved.cardFields?.get("description"))
            assertEquals("沉稳", saved.cardFields?.get("personality"))
            assertTrue(saved.fields.getValue("core_identity").startsWith("林晚看守旧桥。"))
            val editedCard = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
            assertEquals(editedDescription, editedCard.getValue("description").jsonPrimitive.content)
            assertEquals("雨后的桥头", editedCard.getValue("scenario").jsonPrimitive.content)
            assertEquals("{{user}}，欢迎进店。", editedCard.getValue("first_mes").jsonPrimitive.content)
            assertEquals(metadata.creator, editedCard.getValue("creator").jsonPrimitive.content)
            assertEquals(metadata.creatorNotes, editedCard.getValue("creator_notes").jsonPrimitive.content)
            assertEquals(metadata.characterVersion, editedCard.getValue("character_version").jsonPrimitive.content)
            assertEquals(listOf("客栈", "旅行", "客栈", ""), editedCard.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
            val editedSystem = "{{original}}\n\n请用{{char}}的短句。"
            val editedInstruction = "等{{user}}先开口。"
            persona.saveReview(runId, "林晚", mapOf("system_prompt" to editedSystem, "post_history_instructions" to editedInstruction))
            val updatedProfile = requireNotNull(persona.getReview(runId, "林晚").cardFields)
            assertEquals(editedSystem, updatedProfile["system_prompt"])
            assertEquals(editedInstruction, updatedProfile["post_history_instructions"])
            val editedPromptPayload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "edited-prompt", "有人吗？")
            val promptBuilder = DialoguePromptBuilder(PromptLoader(JvmPromptSource()))
            val editedPrompt = promptBuilder.buildDialogueLlmMessages(editedPromptPayload)
            assertContains(editedPrompt.first().content.orEmpty(), "请用林晚的短句。")
            assertContains(editedPrompt.last().content.orEmpty(), "等旅人先开口。")
            val exportedPrompts = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
            assertEquals(editedSystem, exportedPrompts.getValue("system_prompt").jsonPrimitive.content)
            assertEquals(editedInstruction, exportedPrompts.getValue("post_history_instructions").jsonPrimitive.content)

            persona.saveReview(runId, "林晚", mapOf("post_history_instructions" to ""))
            val clearedPayload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "cleared-prompt", "有人吗？")
            val clearedPrompt = promptBuilder.buildDialogueLlmMessages(clearedPayload)
            assertContains(clearedPrompt.first().content.orEmpty(), "请用林晚的短句。")
            assertTrue(clearedPrompt.none { it.content.orEmpty().contains("等旅人先开口。") })
            assertTrue(clearedPrompt.none { it.content.orEmpty().contains("让旅人自行决定下一步。") })
            val exportedCleared = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
            assertEquals("", exportedCleared.getValue("post_history_instructions").jsonPrimitive.content)
            assertEquals(editedSystem, exportedCleared.getValue("system_prompt").jsonPrimitive.content)
            val note = metadata.characterNote
            assertEquals(SillyTavernCharacterNoteDto("{{char}}等{{user}}提问。", 0, WorldBookInjectionRole.Assistant), note)
            val changedNote = SillyTavernCharacterNoteDto("等{{user}}敲门。", 1, WorldBookInjectionRole.User)
            assertFailsWith<IllegalArgumentException> {
                persona.updateCardMetadata(runId, "林晚", UpdateSillyTavernCardMetadataRequest(
                    metadata.revision, characterNote = changedNote.copy(depth = -1),
                ))
            }
            val refreshed = persona.updateCardMetadata(runId, "林晚", UpdateSillyTavernCardMetadataRequest(
                metadata.revision, characterNote = changedNote,
            ))
            assertEquals(changedNote, refreshed.characterNote)
            assertFailsWith<CardMetadataChangedException> {
                persona.updateCardMetadata(runId, "林晚", UpdateSillyTavernCardMetadataRequest(
                    metadata.revision, characterNote = note,
                ))
            }
            suspend fun notePrompt() = promptBuilder.buildDialogueLlmMessages(
                DialoguePayloadBuilder(storage).buildTurnPayload(
                    requireNotNull(storage.readRunManifest(runId)), session, "note-edit", "还营业吗？",
                )
            )
            assertEquals(
                listOf("user" to "等旅人敲门。", "user" to "还营业吗？"),
                notePrompt().takeLast(2).map { it.role to it.content },
            )
            val extensions = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
                .getValue("extensions").jsonObject
            assertEquals("扩展原值", extensions.getValue("keep").jsonPrimitive.content)
            val exportedNote = extensions.getValue("depth_prompt").jsonObject
            assertTrue(exportedNote.getValue("custom").jsonPrimitive.boolean)
            assertEquals(changedNote.text, exportedNote.getValue("prompt").jsonPrimitive.content)
            assertEquals(1, exportedNote.getValue("depth").jsonPrimitive.int)
            assertEquals("user", exportedNote.getValue("role").jsonPrimitive.content)
            persona.updateCardMetadata(runId, "林晚", UpdateSillyTavernCardMetadataRequest(
                refreshed.revision, characterNote = changedNote.copy(text = ""),
            ))
            assertTrue(notePrompt().none { it.content.orEmpty().contains("等旅人敲门。") })
            val exportedEmbedded = worldBooks.exportWorldBook(runId, "character_book")
            val exportedStandalone = worldBooks.exportWorldBook(runId, "world_book")
            assertTrue(exportedEmbedded.getValue("entries").jsonObject.getValue("0").jsonObject.getValue("disable").jsonPrimitive.boolean)
            assertTrue(exportedStandalone.getValue("entries").jsonObject.getValue("0").jsonObject.getValue("disable").jsonPrimitive.boolean)
            assertEquals("disabled", SillyTavernImport.evaluateBooks(null, exportedEmbedded, listOf("客栈"))
                .single { it.excerpt == "客栈后院有一口井。" }.status)
            val latestEmbedded = worldBooks.listWorldBooks(runId).books.single { it.source == embedded.source }
            worldBooks.setEntryEnabled(runId, embedded.source, 0, latestEmbedded.revision, true)
            // A refresh could replace a book with another book whose first index also exists.
            importer.importPackage(replacement.copy(targetRunId = runId))
            assertFailsWith<WorldBookChangedException> { worldBooks.removeStandaloneBook(runId, standaloneDisabled.revision) }
            val replacementBook = worldBooks.listWorldBooks(runId).books.single { it.source == "world_book" }
            val remaining = worldBooks.removeStandaloneBook(runId, replacementBook.revision)
            assertEquals(listOf("character_book"), remaining.books.map { it.source })
            assertEquals(listOf("character_book"), worldBooks.testWorldBooks(runId, "客栈").entries
                .filter { it.status == "injected" }.map { it.source })

            val lockedFacts = buildJsonArray {
                repeat(DialoguePromptRules.WORLD_FACT_LIMIT) { index ->
                    add(buildJsonObject {
                        put("fact_id", "locked-$index")
                        put("summary", "锁定设定 $index")
                        put("locked", true)
                    })
                }
            }
            storage.writeTextAtomically(
                storage.getRunDirectory(runId) / "world_memory.json",
                buildJsonObject { put("facts", lockedFacts) }.toString(),
            )
            val fullContext = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, committed, "later", "客栈")
            assertTrue(dialogue.extractWorldBookMatches(fullContext).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `restoring a role card book keeps its chat entry point greetings and world books`() = runBlocking {
        val path = File.createTempFile("zaomeng-card-package-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val store = RoomDocumentStore(db)
            val storage = StorageService(path, store, DomainStore(path, db.domainDao(), store))
            val packages = RunPackageService(storage)
            val runs = RunManagementService(storage)
            val exporter = RunOperationsService(storage, runs, packages, DistillExecutor(storage, llm = null, promptLoader = null))
            val sessions = SessionManagementService(storage, DialogueService(storage))
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎进店。",
                "alternate_greetings":["{{char}}向{{user}}点头。"],"character_book":{"entries":[
                {"keys":["客栈"],"content":"后院有一口井。"}]}}}"""
            val avatar = cardPng("chara" to card)
            val original = packages.importPackage(ImportRunPackageRequest("card.png", Base64.getEncoder().encodeToString(avatar)))
            val originalId = original.getValue("run_id").jsonPrimitive.content
            packages.importPackage(request("world.json", """{"name":"关门时间","entries":[{"key":["客栈"],"content":"今天打烊了。"}]}""", originalId))
            sessions.openDialogueSession(runId = originalId, mode = "insert", participants = listOf("林晚"))

            val (archive, filename) = exporter.exportRunPackage(originalId, builtin = false, includeDialogue = false)
            val restored = packages.importPackage(ImportRunPackageRequest(filename, Base64.getEncoder().encodeToString(archive)))
            val restoredId = restored.getValue("run_id").jsonPrimitive.content
            assertNotEquals(originalId, restoredId)
            assertTrue(Json { ignoreUnknownKeys = true }.decodeFromJsonElement<RunManifestDto>(restored).isSillyTavernCharacterCard)
            assertEquals(filename, restored.getValue("imported_from").jsonObject.getValue("package_filename").jsonPrimitive.content)
            assertContentEquals(avatar, storage.readBytes(storage.avatarFile(restoredId, "林晚")))
            assertTrue(storage.listDialogueSessionManifests(restoredId).isEmpty())
            assertEquals(listOf(0, 1), sessions.getCardGreetings(restoredId).options.map { it.index })
            assertEquals(setOf("world_book", "character_book"), packages.sillyTavern.testWorldBooks(restoredId, "客栈")
                .entries.filter { it.status == "injected" }.map { it.source }.toSet())
            val firstChat = sessions.openDialogueSession(
                runId = restoredId,
                mode = "insert",
                participants = listOf("林晚"),
                selfProfile = buildJsonObject { put("display_name", "旅人") },
                cardGreetingIndex = 1,
            )
            assertEquals("林晚向旅人点头。", firstChat.getValue("transcript").jsonArray.single().jsonObject.getValue("message").jsonPrimitive.content)

            // Book packages restored before this change stored only the generic import entry point.
            storage.updateRunManifest(restoredId) { JsonObject(it + ("entrypoint" to JsonPrimitive("import"))) }
            assertEquals("sillytavern", runs.getRun(restoredId).getValue("entrypoint").jsonPrimitive.content)
            val listed = runs.listRuns().single { it["run_id"]?.jsonPrimitive?.content == restoredId }
            assertEquals("sillytavern", listed.getValue("entrypoint").jsonPrimitive.content)

            val (archiveWithDialogue, fullFilename) = exporter.exportRunPackage(originalId, builtin = false, includeDialogue = true)
            val restoredWithDialogue = packages.importPackage(
                ImportRunPackageRequest(fullFilename, Base64.getEncoder().encodeToString(archiveWithDialogue)),
            )
            val fullId = restoredWithDialogue.getValue("run_id").jsonPrimitive.content
            val restoredSession = storage.listDialogueSessionManifests(fullId).single()
            assertEquals(fullId, restoredSession.getValue("run_id").jsonPrimitive.content)
            assertEquals("欢迎进店。", restoredSession.getValue("transcript").jsonArray.single().jsonObject.getValue("message").jsonPrimitive.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun `importing a package rewrites operation receipts to the new run`() = runBlocking {
        val path = File.createTempFile("zaomeng-package-receipts-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val store = RoomDocumentStore(db)
            val storage = StorageService(path, store, DomainStore(path, db.domainDao(), store))
            val packages = RunPackageService(storage)
            val runs = RunManagementService(storage)
            val exporter = RunOperationsService(storage, runs, packages, DistillExecutor(storage, llm = null, promptLoader = null))
            val sessions = SessionManagementService(storage, DialogueService(storage))
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎进店。"}}"""
            val original = packages.importPackage(
                ImportRunPackageRequest("card.png", Base64.getEncoder().encodeToString(cardPng("chara" to card))),
            )
            val originalId = original.getValue("run_id").jsonPrimitive.content
            val opened = sessions.openDialogueSession(runId = originalId, mode = "insert", participants = listOf("林晚"))
            val sessionId = opened.getValue("session_id").jsonPrimitive.content
            val sessionDir = storage.getDialogueSessionsDirectory(originalId) / sessionId
            val receipt = """{"run_id":"$originalId","session_id":"$sessionId","operation_id":"op-1","turn_id":"turn-1","status":"failed"}"""
            storage.writeTextAtomically(sessionDir / "operations/op-1/receipt.json", receipt)
            storage.writeTextAtomically(sessionDir / "derived-operations/turn-1--1/receipt.json", receipt)

            val (archive, filename) = exporter.exportRunPackage(originalId, builtin = false, includeDialogue = true)
            val restored = packages.importPackage(
                ImportRunPackageRequest(filename, Base64.getEncoder().encodeToString(archive)),
            )
            val restoredId = restored.getValue("run_id").jsonPrimitive.content
            val restoredDir = storage.getDialogueSessionsDirectory(restoredId) / sessionId

            val replyReceipt = Json.parseToJsonElement(storage.readText(restoredDir / "operations/op-1/receipt.json")).jsonObject
            assertEquals(restoredId, replyReceipt.getValue("run_id").jsonPrimitive.content)
            assertEquals(sessionId, replyReceipt.getValue("session_id").jsonPrimitive.content)
            val derivedReceipt = Json.parseToJsonElement(storage.readText(restoredDir / "derived-operations/turn-1--1/receipt.json")).jsonObject
            assertEquals(restoredId, derivedReceipt.getValue("run_id").jsonPrimitive.content)
            // 导入只改新书卷副本，原书卷回执保持不变。
            val sourceReceipt = Json.parseToJsonElement(storage.readText(sessionDir / "operations/op-1/receipt.json")).jsonObject
            assertEquals(originalId, sourceReceipt.getValue("run_id").jsonPrimitive.content)
        } finally {
            db.close()
        }
    }

    @Test
    fun `PNG ccv3 metadata precedes chara and truncated chunks fail`() {
        val png = cardPng(
            "chara" to """{"name":"旧卡","description":"旧描述"}""",
            "ccv3" to """{"spec":"chara_card_v3","data":{"name":"新卡"}}""",
        )
        assertEquals(
            "新卡",
            SillyTavernImport.parseCard(SillyTavernImport.readPngCard(png)).data.getValue("name").jsonPrimitive.content,
        )
        assertFailsWith<IllegalArgumentException> { SillyTavernImport.readPngCard(png.copyOf(png.size - 1)) }
    }

    @Test
    fun `PNG export keeps image pixels and replaces both old card payloads`() {
        val png = cardPng(
            "chara" to """{"name":"旧版角色","description":"旧描述"}""",
            "ccv3" to """{"spec":"chara_card_v3","data":{"name":"过期版本"}}""",
        )
        val json = """{"spec":"chara_card_v2","data":{"name":"新角色","description":"已保存的人设",
            "first_mes":"你好","alternate_greetings":["请进"],"character_book":{"entries":[{"content":"北港"}]}}}"""
            .encodeToByteArray()
        val updated = SillyTavernPng.writeCard(png, json)
        assertContentEquals(json, SillyTavernImport.readPngCard(updated))
        assertContentEquals(updated, SillyTavernPng.writeCard(updated, json))

        val sourceImage = ImageIO.read(png.inputStream())
        val exportedImage = ImageIO.read(updated.inputStream())
        assertEquals(sourceImage.width, exportedImage.width)
        assertEquals(sourceImage.height, exportedImage.height)
        assertContentEquals(
            sourceImage.getRGB(0, 0, sourceImage.width, sourceImage.height, null, 0, sourceImage.width),
            exportedImage.getRGB(0, 0, exportedImage.width, exportedImage.height, null, 0, exportedImage.width),
        )
        assertFailsWith<IllegalArgumentException> { SillyTavernPng.writeCard(png.copyOf(png.size - 1), json) }
        assertFailsWith<IllegalArgumentException> { SillyTavernPng.writeCard(png, ByteArray(SillyTavernPng.MAX_JSON_BYTES + 1)) }
    }

    @Test
    fun `lore honors disabled entries secondary keys and per turn limits`() {
        val book = Json.parseToJsonElement("""
            {"entries":[
              {"constant":true,"content":"常驻","enabled":true,"insertion_order":9},
              {"constant":true,"content":"禁用","disable":true},
              {"key":["井"],"keysecondary":["月亮","倒影"],"selectiveLogic":3,"content":"双条件"},
              {"keys":["井"],"content":"${"水".repeat(300)}","insertion_order":8},
              {"keys":["井"],"content":"甲","insertion_order":7},
              {"keys":["井"],"content":"乙","insertion_order":6},
              {"keys":["井"],"content":"丙","insertion_order":5}
            ]}
        """.trimIndent()).jsonObject
        val entries = SillyTavernImport.loreEntries(book)
        val quiet = SillyTavernImport.evaluateLore(entries, listOf("去街上"))
        assertEquals(listOf("常驻"), quiet.filter { it.status == "injected" }.map { it.excerpt })
        val triggered = SillyTavernImport.evaluateLore(entries, listOf("井里有月亮"))
        assertEquals(listOf("常驻", "水".repeat(300), "甲", "乙"), triggered.filter { it.status == "injected" }.map { it.excerpt })
        assertEquals("secondary", triggered.single { it.excerpt == "双条件" }.status)
        assertEquals("disabled", triggered.single { it.excerpt == "禁用" }.status)
        assertEquals("budget", triggered.single { it.excerpt == "丙" }.status)
        val matched = SillyTavernImport.evaluateLore(listOf(entries[2]), listOf("井", "月亮与倒影")).single()
        assertEquals("injected", matched.status)
        assertEquals(listOf("井", "月亮", "倒影"), matched.keywords)
        assertEquals("budget", SillyTavernImport.evaluateLore(entries, listOf("井"), availableSlots = 0).first().status)
    }

    @Test
    fun `whole words and depth apply to native and embedded books`() {
        val embedded = Json.parseToJsonElement("""
            {"scan_depth":2,"entries":[
              {"keys":["inn"],"content":"embedded","extensions":{"match_whole_words":true}},
              {"keys":["red inn"],"content":"phrase","extensions":{"match_whole_words":true}},
              {"keys":["inn"],"content":"zero","extensions":{"scan_depth":0}}
            ]}
        """.trimIndent()).jsonObject
        val standalone = Json.parseToJsonElement("""
            {"entries":{
              "a":{"key":["inn"],"content":"standalone","scanDepth":1,"matchWholeWords":true},
              "b":{"key":["INN"],"content":"case","scanDepth":1,"caseSensitive":true,"matchWholeWords":true},
              "c":{"key":["any"],"content":"constant","constant":true,"scanDepth":0}
            }}
        """.trimIndent()).jsonObject
        val selected = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("red innkeeper", "INNkeeper"))
            .filter { it.status == "injected" }.map { it.excerpt }
        assertEquals(listOf("phrase", "constant"), selected)
        val matched = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("former", "(INN)!"))
            .filter { it.status == "injected" }.map { it.excerpt }
        assertEquals(listOf("embedded", "standalone", "case", "constant"), matched)
        assertEquals(
            matched,
            SillyTavernImport.evaluateBooks(embedded, standalone, listOf("former", "(INN)!"), collectDiagnostics = false)
                .map { it.excerpt },
        )
        val deep = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("inn", "recent", "current"))
            .filter { it.status == "injected" }.map { it.excerpt }
        assertEquals(listOf("constant"), deep)
        val capped = Json.parseToJsonElement("""{"scan_depth":1000,"entries":[{"keys":["old"],"content":"too far"}]}""").jsonObject
        assertEquals(
            "no_keyword",
            SillyTavernImport.evaluateBooks(capped, null, listOf("old") + List(32) { "new" }).single().status,
        )
    }

    @Test
    fun `recursion follows selected entries across enabled books while honoring gates and budget`() {
        val embedded = Json.parseToJsonElement("""{"recursive_scanning":true,"entries":[
            {"comment":"入口","keys":["旅人"],"content":"请去库房。"},
            {"comment":"拒绝递归","keys":["库房"],"content":"不应命中。","extensions":{"exclude_recursion":true}}
        ]}""").jsonObject
        val standalone = Json.parseToJsonElement("""{"recursiveScanning":true,"entries":[
            {"comment":"库房","key":["库房"],"content":"旅人，里面有门锁。","scanDepth":0,"delayUntilRecursion":true,"order":10},
            {"comment":"门锁","key":["门锁"],"content":"需要钥匙。","preventRecursion":true},
            {"comment":"钥匙","key":["钥匙"],"content":"不应传播。"}
        ]}""").jsonObject
        val diagnostic = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("旅人到了")).associateBy { it.title }
        assertEquals(0, diagnostic.getValue("入口").recursionDepth)
        assertEquals(1, diagnostic.getValue("库房").recursionDepth)
        assertEquals(2, diagnostic.getValue("门锁").recursionDepth)
        assertEquals("injected", diagnostic.getValue("门锁").status)
        assertEquals("no_keyword", diagnostic.getValue("拒绝递归").status)
        assertEquals("no_keyword", diagnostic.getValue("钥匙").status)
        val runtime = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("旅人到了"), collectDiagnostics = false)
        assertEquals(listOf("入口", "库房", "门锁"), runtime.map { it.title })
        val limited = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("旅人到了"), availableSlots = 1)
        assertEquals(listOf("入口"), limited.filter { it.status == "injected" }.map { it.title })
        val off = JsonObject(standalone + ("recursive_scanning" to JsonPrimitive(false)))
        val unlinked = SillyTavernImport.evaluateBooks(embedded, off, listOf("旅人到了"))
        assertEquals(listOf("入口"), unlinked.filter { it.status == "injected" }.map { it.title })
        assertEquals("recursion_only", unlinked.single { it.title == "库房" }.status)
    }

    @Test
    fun `higher recursion delay levels wait until the current level stops and zero probability cannot seed a scan`() {
        val book = Json.parseToJsonElement("""{"recursive_scanning":true,"entries":[
            {"comment":"入口","keys":["开始"],"content":"城门，钟声。"},
            {"comment":"城门","keys":["城门"],"content":"回到开始，看钟楼。","extensions":{"delay_until_recursion":true}},
            {"comment":"钟楼","keys":["钟楼"],"content":"钟楼开门。"},
            {"comment":"钟声","keys":["钟声"],"content":"听见晚钟。","extensions":{"delay_until_recursion":5}}
        ]}""").jsonObject
        val chain = SillyTavernImport.evaluateBooks(book, null, listOf("开始"), collectDiagnostics = false)
        assertEquals(listOf("入口", "城门", "钟楼", "钟声"), chain.map { it.title })
        assertEquals(listOf(0, 1, 2, 4), chain.map { it.recursionDepth })
        val entries = SillyTavernImport.loreEntries(book)
        val blocked = JsonObject(book + ("entries" to JsonArray(
            listOf(JsonObject(entries.first() + ("probability" to JsonPrimitive(0)))) + entries.drop(1),
        )))
        val none = SillyTavernImport.evaluateBooks(blocked, null, listOf("开始"))
        assertEquals("probability", none.first().status)
        assertTrue(none.none { it.status == "injected" })
    }

    @Test
    fun `probability supports both schemas boundaries and stable turn rolls`() {
        val embedded = Json.parseToJsonElement("""{"entries":[
            {"comment":"常驻为零","constant":true,"content":"不注入","extensions":{"probability":0}},
            {"comment":"部分概率","keys":["雨"],"content":"雨天设定","extensions":{"probability":42.5,"useProbability":true}}
        ]}""").jsonObject
        val standalone = Json.parseToJsonElement("""{"entries":[
            {"comment":"关闭概率","key":["雨"],"content":"总能命中","probability":0,"useProbability":false},
            {"comment":"概率全中","key":["雨"],"content":"也能命中","probability":100},
            {"comment":"没有关键词","key":["雪"],"content":"不相关","probability":0}
        ]}""").jsonObject
        val results = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("下雨"), probabilitySeed = "turn-1")
            .associateBy { it.title }
        assertEquals("probability", results.getValue("常驻为零").status)
        assertEquals("injected", results.getValue("关闭概率").status)
        assertNull(results.getValue("关闭概率").triggerProbability)
        assertEquals("injected", results.getValue("概率全中").status)
        assertEquals("no_keyword", results.getValue("没有关键词").status)
        assertEquals(42.5, results.getValue("部分概率").triggerProbability)
        val statuses = (0 until 32).map { turn ->
            fun evaluate() = SillyTavernImport.evaluateBooks(embedded, standalone, listOf("下雨"), probabilitySeed = "turn-$turn")
            val first = evaluate()
            assertEquals(first, evaluate())
            first.single { it.title == "部分概率" }.status
        }
        assertEquals(setOf("injected", "probability"), statuses.toSet())
        val exported = SillyTavernExport.worldBook(embedded).getValue("entries").jsonObject
        assertEquals(42.5, exported.getValue("1").jsonObject.getValue("probability").jsonPrimitive.double)
    }

    @Test
    fun `export converts native persona fields and world info extensions`() {
        val native = SillyTavernExport.characterCard("沈照", mapOf(
            "core_identity" to "书院先生",
            "story_role" to "在书院教书",
            "key_bonds" to "林晚",
        ), original = null).getValue("data").jsonObject
        assertEquals("书院先生\n重要关系：林晚", native.getValue("description").jsonPrimitive.content)
        assertEquals("在书院教书", native.getValue("scenario").jsonPrimitive.content)
        assertEquals("", native.getValue("first_mes").jsonPrimitive.content)
        assertTrue(native.getValue("extensions").jsonObject.isEmpty())
        val playable = SillyTavernExport.characterCard("沈照", mapOf(
            "core_identity" to "书院先生",
            "speech_style" to "说话简短",
            "card_first_mes" to "庭院里灯还亮着。\\n“请进。”",
            "card_mes_example" to "<START>\\n{{user}}: 读什么？\\n{{char}}: 从第一页读起。",
        ), original = null).getValue("data").jsonObject
        assertEquals("庭院里灯还亮着。\n“请进。”", playable.getValue("first_mes").jsonPrimitive.content)
        assertEquals("<START>\n{{user}}: 读什么？\n{{char}}: 从第一页读起。", playable.getValue("mes_example").jsonPrimitive.content)
        assertContains(playable.getValue("description").jsonPrimitive.content, "说话方式：说话简短")
        val authored = SillyTavernExport.characterCard("沈照", mapOf(
            "core_identity" to "蒸馏档案身份",
            "key_bonds" to "蒸馏档案关系",
            "card_description" to "{{char}}在书院守夜。",
            "card_personality" to "克制寡言",
            "card_scenario" to "{{user}}来借书。",
        ), original = null).getValue("data").jsonObject
        assertEquals("{{char}}在书院守夜。", authored.getValue("description").jsonPrimitive.content)
        assertEquals("克制寡言", authored.getValue("personality").jsonPrimitive.content)
        assertEquals("{{user}}来借书。", authored.getValue("scenario").jsonPrimitive.content)

        val source = Json.parseToJsonElement("""
            {"name":"客栈","scan_depth":9,"entries":[
                {"id":9,"keys":["inn"],"secondary_keys":["rain"],"content":"屋内","enabled":true,
                 "position":"before_char","insertion_order":5,"extensions":{"match_whole_words":true,"group":"setting","probability":75,"exclude_recursion":true}},
                {"id":9,"keys":["hall"],"content":"门厅","enabled":false}
            ]}
        """.trimIndent()).jsonObject
        val world = SillyTavernExport.worldBook(source)
        val entries = world.getValue("entries").jsonObject
        assertEquals(setOf("9", "0"), entries.keys)
        val main = entries.getValue("9").jsonObject
        assertEquals(0, main.getValue("position").jsonPrimitive.int)
        assertEquals(9, main.getValue("scanDepth").jsonPrimitive.int)
        assertEquals("setting", main.getValue("group").jsonPrimitive.content)
        assertEquals(75, main.getValue("probability").jsonPrimitive.int)
        assertTrue(main.getValue("matchWholeWords").jsonPrimitive.boolean)
        assertTrue(main.getValue("excludeRecursion").jsonPrimitive.boolean)
        assertEquals(listOf("屋内"), SillyTavernImport.evaluateBooks(null, world, listOf("rain", "(inn)!"), probabilitySeed = "export-2")
            .filter { it.status == "injected" }.map { it.excerpt })
    }

    @Test
    fun `bundled SillyTavern export shapes are accepted`() {
        val checkout = System.getenv("SILLYTAVERN_CHECKOUT")?.let(::File)
        assumeTrue("Set SILLYTAVERN_CHECKOUT to run against real upstream fixtures", checkout != null)
        val content = requireNotNull(checkout).resolve("default/content")
        val png = content.resolve("default_Seraphina.png").readBytes()
        val card = SillyTavernImport.parseCard(SillyTavernImport.readPngCard(png))
        assertTrue(SillyTavernImport.cardGreetings(card).options.any { it.index == 0 })
        val standalone = Json.parseToJsonElement(content.resolve("Eldoria.json").readText(Charsets.UTF_8)).jsonObject
        assertTrue(SillyTavernImport.loreEntries(standalone).isNotEmpty())
        val exportedWorld = SillyTavernExport.worldBook(standalone)
        assertEquals(SillyTavernImport.loreEntries(standalone).size, exportedWorld.getValue("entries").jsonObject.size)

        val name = card.data.getValue("name").jsonPrimitive.content.trim()
        val edited = SillyTavernExport.characterCard(name, SillyTavernImport.profileFields(card.data), card)
        assertEquals(card.original.getValue("spec"), edited.getValue("spec"))
        val native = SillyTavernExport.characterCard("沈照", mapOf(
            "card_description" to "{{char}}是书院先生。",
            "card_personality" to "沉静",
            "card_scenario" to "{{user}}到书院借书。",
            "card_first_mes" to "“进来吧。”",
            "card_mes_example" to "<START>\\n{{user}}: 可以借阅吗？\\n{{char}}: 请先登记。",
        ), null)
        val withBook = SillyTavernImport.parseCard(
            """{"spec":"chara_card_v2","data":{"name":"林晚","character_book":{"entries":[]}}}""".encodeToByteArray(),
        )
        val looseBook = SillyTavernExport.characterCard("林晚", emptyMap(), withBook)
        val validator = checkout.resolve("src/validator/TavernCardValidator.js").toURI().toASCIIString()
        val node = ProcessBuilder("node", "--input-type=module", "-e", """
            const { TavernCardValidator } = await import(process.argv[1]);
            const chunks = [];
            for await (const chunk of process.stdin) chunks.push(chunk);
            for (const card of JSON.parse(Buffer.concat(chunks).toString('utf8'))) {
                const checker = new TavernCardValidator(card);
                const valid = card.spec === 'chara_card_v3' ? checker.validateV3() : checker.validateV2();
                if (!valid) throw new Error(card.data.name + ': ' + checker.lastValidationError);
            }
        """.trimIndent(), validator).redirectErrorStream(true).start()
        try {
            node.outputStream.use { it.write(JsonArray(listOf(edited, native, looseBook)).toString().encodeToByteArray()) }
            assertTrue(node.waitFor(15, TimeUnit.SECONDS), "SillyTavern validator did not finish")
            assertEquals(0, node.exitValue(), node.inputStream.bufferedReader().use { it.readText() })
        } finally {
            if (node.isAlive) node.destroyForcibly()
        }
    }

    private fun request(filename: String, json: String, targetRunId: String? = null) = ImportRunPackageRequest(
        filename,
        Base64.getEncoder().encodeToString(json.encodeToByteArray()),
        targetRunId = targetRunId,
    )

    private fun cardPng(vararg cards: Pair<String, String>): ByteArray {
        val image = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val metadata = cards.fold(ByteArray(0)) { bytes, (key, card) ->
            val encoded = Base64.getEncoder().encodeToString(card.encodeToByteArray())
            bytes + chunk("tEXt", "$key\u0000$encoded".encodeToByteArray())
        }
        return image.copyOf(image.size - 12) + metadata + chunk("IEND", byteArrayOf())
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val name = type.encodeToByteArray()
        val crc = CRC32().apply { update(name); update(data) }
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(name).put(data).putInt(crc.value.toInt()).array()
    }
}
