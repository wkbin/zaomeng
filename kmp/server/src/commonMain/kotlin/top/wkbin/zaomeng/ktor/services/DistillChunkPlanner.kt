package top.wkbin.zaomeng.ktor.services

internal data class DistillChunkEntry(
    val label: String,
    val payload: DistillPayload,
)

/** Pure chunk planning policy shared by character-profile and relation distillation. */
internal object DistillChunkPlanner {
    fun shouldUse(text: String, triggerChars: Int, triggerSentences: Int): Boolean {
        val excerpt = text.trim()
        if (excerpt.isEmpty()) return false
        val sentenceCount = DistillExcerptBuilder.splitSentences(excerpt).size
        return excerpt.length > triggerChars || sentenceCount > triggerSentences
    }

    fun buildCharacter(
        payload: DistillPayload,
        maxChars: Int,
        maxSentences: Int,
    ): List<DistillChunkEntry> = build(
        payload = payload,
        maxChars = maxChars,
        maxSentences = maxSentences,
        focusStrategy = "chunked_character_windows",
        fallbackLabel = "证据块",
    )

    fun buildRelations(
        payload: DistillPayload,
        maxChars: Int,
        maxSentences: Int,
    ): List<DistillChunkEntry> = build(
        payload = payload,
        maxChars = maxChars,
        maxSentences = maxSentences,
        focusStrategy = "chunked_relation_windows",
        fallbackLabel = "关系块",
    )

    private fun build(
        payload: DistillPayload,
        maxChars: Int,
        maxSentences: Int,
        focusStrategy: String,
        fallbackLabel: String,
    ): List<DistillChunkEntry> {
        val request = payload.request.toMutableMap()
        val excerpt = request["excerpt"]?.toString()?.trim().orEmpty()
        val stages = (request["excerpt_stages"] as? Map<*, *>)
            ?.mapKeys { it.key.toString() }
            .orEmpty()
        val entries = mutableListOf<DistillChunkEntry>()
        for ((stageKey, stageLabel) in STAGES) {
            val stageText = stages[stageKey]?.toString()?.trim().orEmpty()
            if (stageText.isEmpty()) continue
            val chunks = split(stageText, maxChars, maxSentences)
            chunks.forEachIndexed { index, chunkText ->
                entries += createEntry(
                    payload = payload,
                    request = request,
                    chunkText = chunkText,
                    stageKey = stageKey,
                    chunkIndex = index,
                    chunkTotal = chunks.size,
                    focusStrategy = focusStrategy,
                    label = if (chunks.size > 1) "$stageLabel-${index + 1}" else stageLabel,
                )
            }
        }
        if (entries.isNotEmpty()) return entries

        val chunks = split(excerpt, maxChars, maxSentences)
        return chunks.mapIndexed { index, chunkText ->
            val chunkRequest = request.toMutableMap().apply {
                put("excerpt", chunkText)
                put("excerpt_stages", emptyStages())
            }
            val chunkMeta = payload.meta.toMutableMap().apply {
                put("chunk_index", index + 1)
                put("chunk_total", chunks.size)
            }
            DistillChunkEntry(
                label = "$fallbackLabel-${index + 1}",
                payload = payload.copy(request = chunkRequest, meta = chunkMeta),
            )
        }
    }

    private fun createEntry(
        payload: DistillPayload,
        request: Map<String, Any?>,
        chunkText: String,
        stageKey: String,
        chunkIndex: Int,
        chunkTotal: Int,
        focusStrategy: String,
        label: String,
    ): DistillChunkEntry {
        val chunkRequest = request.toMutableMap().apply {
            put("excerpt", chunkText)
            put("excerpt_stages", emptyStages().apply { put(stageKey, chunkText) })
            val focus = ((request["excerpt_focus"] as? Map<*, *>)
                ?.mapKeys { it.key.toString() }
                ?.toMutableMap()
                ?: mutableMapOf())
            focus["strategy"] = focusStrategy
            put("excerpt_focus", focus)
        }
        val chunkMeta = payload.meta.toMutableMap().apply {
            put("chunk_stage", stageKey)
            put("chunk_index", chunkIndex + 1)
            put("chunk_total", chunkTotal)
        }
        return DistillChunkEntry(
            label = label,
            payload = payload.copy(request = chunkRequest, meta = chunkMeta),
        )
    }

    private fun split(text: String, maxChars: Int, maxSentences: Int): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        var sentences = DistillExcerptBuilder.splitSentences(clean)
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (sentences.isEmpty()) {
            sentences = clean.split("\n").map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf(clean) }
        }
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var currentChars = 0
        for (sentence in sentences) {
            val units = if (sentence.length > maxChars) sentence.chunked(maxChars) else listOf(sentence)
            for (rawUnit in units) {
                val unit = rawUnit.trim()
                if (unit.isEmpty()) continue
                val projected = currentChars + unit.length + if (current.isNotEmpty()) 1 else 0
                if (current.isNotEmpty() && (current.size >= maxSentences || projected > maxChars)) {
                    chunks += current.joinToString("\n").trim()
                    current.clear()
                    currentChars = 0
                }
                current += unit
                currentChars += unit.length + if (current.size > 1) 1 else 0
            }
        }
        if (current.isNotEmpty()) chunks += current.joinToString("\n").trim()
        return chunks.filter(String::isNotEmpty)
    }

    private fun emptyStages(): LinkedHashMap<String, String> = linkedMapOf(
        "start" to "",
        "mid" to "",
        "end" to "",
    )

    private val STAGES = listOf("start" to "前段", "mid" to "中段", "end" to "后段")
}
