package top.wkbin.zaomeng.feature.chat

import top.wkbin.zaomeng.data.api.DialogueStreamEvent

/** Owns mutable delta batching state so the ViewModel only applies completed UI batches. */
internal class ChatStreamEngine {
    private val pending = mutableListOf<DialogueStreamEvent.Delta>()
    private var displayedFirstDelta = false

    /** Returns true when this is the first visible delta and should be flushed immediately. */
    fun enqueue(event: DialogueStreamEvent.Delta): Boolean {
        pending += event
        if (displayedFirstDelta) return false
        displayedFirstDelta = true
        return true
    }

    fun drain(): List<DialogueStreamEvent.Delta> {
        if (pending.isEmpty()) return emptyList()
        return pending.toList().also { pending.clear() }
    }

    fun reset() {
        pending.clear()
        displayedFirstDelta = false
    }
}
