package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.DialogueStreamEvent

class ChatStreamEngineTest {
    @Test
    fun `first delta flushes immediately and later deltas batch`() {
        val engine = ChatStreamEngine()
        assertTrue(engine.enqueue(delta("a")))
        assertEquals(listOf("a"), engine.drain().map { it.text })
        assertFalse(engine.enqueue(delta("b")))
        assertFalse(engine.enqueue(delta("c")))
        assertEquals(listOf("b", "c"), engine.drain().map { it.text })
    }

    @Test
    fun `reset restores first-delta behavior`() {
        val engine = ChatStreamEngine()
        engine.enqueue(delta("a"))
        engine.reset()
        assertTrue(engine.enqueue(delta("b")))
        assertEquals(listOf("b"), engine.drain().map { it.text })
    }

    private fun delta(text: String) = DialogueStreamEvent.Delta(
        index = 0,
        speaker = "角色",
        role = "character",
        text = text,
    )
}
