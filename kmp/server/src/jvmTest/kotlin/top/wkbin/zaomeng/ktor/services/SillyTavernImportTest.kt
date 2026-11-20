package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.SillyTavernPreviewRequest
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import top.wkbin.zaomeng.ktor.models.DialogueResponse
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
                 "first_mes":"{{user}}，欢迎进店。","alternate_greetings":["","{{char}}向{{user}}点头。"],"character_book":{"entries":[
                   {"keys":["客栈"],"content":"客栈后院有一口井。","enabled":true},
                   {"keys":["秘道"],"content":"隐藏的内容","enabled":true}
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
            assertTrue(storage.listRunIds().isEmpty())
            val manifest = importer.importPackage(request("card.json", card))
            val runId = manifest.getValue("run_id").jsonPrimitive.content
            val profile = manifest.getValue("artifact_index").jsonObject.getValue("characters").jsonArray
                .single().jsonObject.getValue("profile_file").jsonPrimitive.content.toPath()
            assertTrue(storage.readText(profile).startsWith("---\n"))
            assertEquals("ready", manifest.getValue("status").jsonPrimitive.content)

            val book = """{"name":"客栈规则","entries":{"0":{"key":["客栈"],"content":"今晚客栈打烊。","order":99}}}"""
            importer.importPackage(request("book.json", book, runId))
            val replacement = request("book.json", """{"name":"新书","entries":{}}""")
            val replacementPreview = importer.previewSillyTavern(
                SillyTavernPreviewRequest("book.json", replacement.contentBase64, worldBook = true, targetRunId = runId),
            )
            assertEquals("客栈规则", replacementPreview.previousBookName)
            assertEquals(1, replacementPreview.previousEntryCount)
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
                selfProfile = buildJsonObject { put("display_name", "旅人") },
            )
            val greeting = session.getValue("transcript").jsonArray.single().jsonObject
            assertEquals("旅人，欢迎进店。", greeting.getValue("message").jsonPrimitive.content)
            val payload = DialoguePayloadBuilder(storage).buildTurnPayload(manifest, session, "next", "客栈有空房吗？")
            val facts = (payload.getValue("memory_context") as Map<*, *>)["world_facts"] as List<*>
            assertEquals(
                listOf("今晚客栈打烊。", "客栈后院有一口井。"),
                facts.map { (it as Map<*, *>)["summary"] },
            )
            val matches = dialogue.extractWorldBookMatches(payload)
            assertEquals(listOf("world_book", "character_book"), matches.map { it.source })
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
            assertFalse(standaloneDisabled.entries.single().enabled)
            assertEquals(0, worldBooks.testWorldBooks(runId, "客栈").injectedCount)
            val persona = PersonaService(storage, relations = RelationsService(storage))
            val unchangedCard = persona.exportSillyTavernCard(runId, "林晚").getValue("data").jsonObject
            assertEquals("{{char}}是掌柜 --- 也是画师。", unchangedCard.getValue("description").jsonPrimitive.content)
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
            val exportedEmbedded = worldBooks.exportWorldBook(runId, "character_book")
            val exportedStandalone = worldBooks.exportWorldBook(runId, "world_book")
            assertTrue(exportedEmbedded.getValue("entries").jsonObject.getValue("0").jsonObject.getValue("disable").jsonPrimitive.boolean)
            assertTrue(exportedStandalone.getValue("entries").jsonObject.getValue("0").jsonObject.getValue("disable").jsonPrimitive.boolean)
            assertEquals("disabled", SillyTavernImport.evaluateBooks(null, exportedEmbedded, listOf("客栈"))
                .single { it.excerpt == "客栈后院有一口井。" }.status)
            worldBooks.setEntryEnabled(runId, embedded.source, 0, disabled.revision, true)
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
    fun `PNG ccv3 metadata precedes chara and truncated chunks fail`() {
        fun text(key: String, card: String): ByteArray =
            chunk("tEXt", "$key\u0000${Base64.getEncoder().encodeToString(card.encodeToByteArray())}".encodeToByteArray())
        val image = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val png = image.copyOf(image.size - 12) +
            text("chara", """{"name":"旧卡","description":"旧描述"}""") +
            text("ccv3", """{"spec":"chara_card_v3","data":{"name":"新卡"}}""") +
            chunk("IEND", byteArrayOf())
        assertEquals(
            "新卡",
            SillyTavernImport.parseCard(SillyTavernImport.readPngCard(png)).data.getValue("name").jsonPrimitive.content,
        )
        assertFailsWith<IllegalArgumentException> { SillyTavernImport.readPngCard(png.copyOf(png.size - 1)) }
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
        assertEquals(listOf("常驻", "水".repeat(240), "甲", "乙"), triggered.filter { it.status == "injected" }.map { it.excerpt })
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

        val source = Json.parseToJsonElement("""
            {"name":"客栈","scan_depth":9,"entries":[
                {"id":9,"keys":["inn"],"secondary_keys":["rain"],"content":"屋内","enabled":true,
                 "position":"before_char","insertion_order":5,"extensions":{"match_whole_words":true,"group":"setting","probability":75}},
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
        assertEquals(listOf("屋内"), SillyTavernImport.evaluateBooks(null, world, listOf("rain", "(inn)!"))
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
        val native = SillyTavernExport.characterCard("沈照", mapOf("core_identity" to "书院先生"), null)
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
                if (!checker.validateV2()) throw new Error(card.data.name + ': ' + checker.lastValidationError);
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

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val name = type.encodeToByteArray()
        val crc = CRC32().apply { update(name); update(data) }
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(name).put(data).putInt(crc.value.toInt()).array()
    }
}
