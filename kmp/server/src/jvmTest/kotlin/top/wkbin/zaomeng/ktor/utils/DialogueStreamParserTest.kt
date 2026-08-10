package top.wkbin.zaomeng.ktor.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogueStreamParserTest {
    @Test
    fun projectsPartialMessageWithoutWaitingForCompleteJson() {
        val parser = DialogueStreamParser(chunkSize = 24)

        val first = parser.feed("[{\"speaker\":\"林夕\",\"message\":\"你")
        val second = parser.feed("好")

        assertEquals("你", first.joinToString("") { it.text })
        assertEquals("好", second.joinToString("") { it.text })
    }

    @Test
    fun preservesWhitespaceAndNewlinesAcrossDeltas() {
        val parser = DialogueStreamParser(chunkSize = 24)
        val events = buildList {
            addAll(parser.feed("[{\"speaker\":\"林夕\",\"message\":\"hello"))
            addAll(parser.feed(" "))
            addAll(parser.feed("world\\nnext\"}]"))
        }

        assertEquals("hello world\nnext", events.joinToString("") { it.text })
        assertTrue(events.all { it.kind == "delta" })
    }

    @Test
    fun projectsNdjsonBeforeCurrentLineIsClosed() {
        val parser = DialogueStreamParser(chunkSize = 24)

        val first = parser.feed("{\"speaker\":\"林夕\",\"message\":\"你")
        val second = parser.feed("好\"}\n{\"speaker\":\"旁白\",\"message\":\"风")

        assertEquals("你", first.joinToString("") { it.text })
        assertEquals("好风", second.joinToString("") { it.text })
        assertEquals(listOf(0, 1), second.map { it.index }.distinct())
    }
}
