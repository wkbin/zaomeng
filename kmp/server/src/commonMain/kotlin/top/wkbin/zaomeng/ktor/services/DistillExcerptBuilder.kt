package top.wkbin.zaomeng.ktor.services

/**
 * 蒸馏/关系原文节选构建（迁移自 Python src/skill_support/novel_preparation.py
 * 的 build_excerpt_payload_from_text）。
 *
 * 人物名支持 `本名|别名1|别名2`，并为常见中文姓名补充去姓后的称呼，
 * 避免小说主要使用名字或称呼时只命中极少量完整姓名。
 */
object DistillExcerptBuilder {

    private const val MIXED_EXCERPT_MIN_CHARS = 3000
    private const val MIXED_EXCERPT_MIN_SENTENCES = 40
    private const val FOCUSED_CONTEXT_TARGET_SENTENCES = 120
    private const val MAX_FOCUSED_CONTEXT_RADIUS = 24

    private val IGNORED_CHARS = setOf(
        ' ', '\u3000', '\u00b7', '\u2027', '\u30fb', '\'', '"', '`', '~', '!', '@', '#', '$', '%', '^', '&', '*',
        '(', ')', '_', '+', '-', '=', '[', ']', '{', '}', '\\', '|', ';', ':', ',', '.', '<', '>', '/', '?',
        '，', '。', '！', '？', '：', '；', '、', '“', '”', '‘', '’', '《', '》', '【', '】', '（', '）',
    )
    private val DIALOGUE_PATTERN = Regex("(说道|笑道|问道|答道|道：|道:|喊道|喝道|骂道|低声道|轻声道)")
    private val THOUGHT_PATTERN = Regex(
        "(心想|心道|心里|想着|只觉|觉得|不禁|暗想|思忖|寻思|素来|向来|一向|生性|性子|为人|看似|其实|原是|本就)",
    )

    data class ExcerptPayload(
        val excerpt: String,
        val requestedCharacters: List<String>,
        val matchedCharacters: List<String>,
        val missingCharacters: List<String>,
        val strategy: String,
        val excerptStages: Map<String, String>,
    )

    private data class CharacterSpec(
        val canonical: String,
        val aliases: List<String>,
    )

    fun build(
        text: String,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
    ): ExcerptPayload {
        val characterSpecs = normalizeCharacterSpecs(characters)
        val requested = characterSpecs.map(CharacterSpec::canonical)
        val clean = text.trim()
        if (clean.isEmpty()) {
            return ExcerptPayload(
                excerpt = "",
                requestedCharacters = requested,
                matchedCharacters = emptyList(),
                missingCharacters = requested,
                strategy = "empty",
                excerptStages = emptyStageBlocks(),
            )
        }
        val sentences = splitSentences(clean)
        if (characterSpecs.isNotEmpty()) {
            val focused = characterFocusedExcerpt(sentences, characterSpecs, maxSentences, maxChars)
            if (focused.excerpt.isNotEmpty()) return focused
        }
        val leading = leadingExcerpt(sentences, maxSentences, maxChars)
        return ExcerptPayload(
            excerpt = renderExcerpt(sentences, leading.second, maxChars),
            requestedCharacters = requested,
            matchedCharacters = emptyList(),
            missingCharacters = requested,
            strategy = "leading_sentences",
            excerptStages = buildStageBlocks(sentences, leading.second),
        )
    }

    private fun normalizeCharacterSpecs(characters: List<String>): List<CharacterSpec> {
        val seen = mutableSetOf<String>()
        return characters.mapNotNull { rawName ->
            val supplied = rawName.split('|').map(String::trim).filter(String::isNotEmpty).distinct()
            val canonical = supplied.firstOrNull().orEmpty()
            if (canonical.isEmpty() || !seen.add(canonical)) return@mapNotNull null
            CharacterSpec(
                canonical = canonical,
                aliases = (supplied + derivedChineseNameAliases(canonical)).distinct(),
            )
        }
    }

    private fun derivedChineseNameAliases(canonical: String): List<String> {
        if (canonical.length !in 3..4 || canonical.any { it !in '\u3400'..'\u9fff' }) return emptyList()
        val surnameLength = if (COMPOUND_SURNAMES.any(canonical::startsWith)) 2 else 1
        val givenName = canonical.drop(surnameLength)
        return listOfNotNull(givenName.takeIf { it.length >= 2 })
    }

    fun splitSentences(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\u3000', ' ')
        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()
        for (char in normalized) {
            buffer.append(char)
            if (char in charArrayOf('。', '！', '？', '…', '\n')) {
                val sentence = buffer.toString().trim()
                if (sentence.isNotEmpty()) sentences.add(sentence)
                buffer.setLength(0)
            }
        }
        if (buffer.isNotBlank()) sentences.add(buffer.toString().trim())
        return sentences
    }

    private fun normalizeMatchText(text: String): String =
        text.lowercase().filter { it !in IGNORED_CHARS }

    private fun sentenceMentionsCharacter(sentence: String, character: String): Boolean {
        val normalizedSentence = normalizeMatchText(sentence)
        val normalizedAlias = normalizeMatchText(character)
        return normalizedAlias.isNotEmpty() && normalizedSentence.contains(normalizedAlias)
    }

    private fun sentenceMentionsCharacter(sentence: String, character: CharacterSpec): Boolean =
        character.aliases.any { sentenceMentionsCharacter(sentence, it) }

    private fun leadingExcerpt(sentences: List<String>, maxSentences: Int, maxChars: Int): Pair<String, List<Int>> {
        val selected = mutableListOf<String>()
        val indices = mutableListOf<Int>()
        var total = 0
        for ((idx, sentence) in sentences.withIndex()) {
            if (selected.size >= maxSentences) break
            val projected = total + sentence.length + if (selected.isNotEmpty()) 1 else 0
            if (selected.isNotEmpty() && projected > maxChars) break
            if (selected.isEmpty() && sentence.length > maxChars) {
                indices.add(idx)
                return sentence.take(maxChars) to indices
            }
            selected.add(sentence)
            indices.add(idx)
            total = projected
        }
        if (indices.isEmpty() && sentences.isNotEmpty()) {
            indices.add(0)
            return sentences.first().take(maxChars) to indices
        }
        return selected.joinToString("\n") to indices
    }

    private fun characterFocusedExcerpt(
        sentences: List<String>,
        characterSpecs: List<CharacterSpec>,
        maxSentences: Int,
        maxChars: Int,
    ): ExcerptPayload {
        val characters = characterSpecs.map(CharacterSpec::canonical)
        val specsByCanonical = characterSpecs.associateBy(CharacterSpec::canonical)
        val characterHits = characters.associateWith { mutableListOf<Int>() }.toMutableMap()
        for ((idx, sentence) in sentences.withIndex()) {
            for (canon in characters) {
                if (sentenceMentionsCharacter(sentence, specsByCanonical.getValue(canon))) {
                    characterHits.getValue(canon).add(idx)
                }
            }
        }
        val matched = characters.filter { characterHits.getValue(it).isNotEmpty() }
        val missing = characters.filter { characterHits.getValue(it).isEmpty() }
        if (matched.isEmpty()) {
            return ExcerptPayload(
                excerpt = "",
                requestedCharacters = characters,
                matchedCharacters = emptyList(),
                missingCharacters = missing,
                strategy = "leading_sentences",
                excerptStages = emptyStageBlocks(),
            )
        }

        val centerBudget = maxOf(1, minOf(maxSentences, maxChars / 48))
        val representativeHits = buildRepresentativeHitPlan(characterHits, matched, centerBudget)
        val candidateIndices = candidateIndicesFromCenters(representativeHits, sentences.size)

        val selected = mutableListOf<Int>()
        val used = mutableSetOf<Int>()
        var total = 0
        for (idx in candidateIndices) {
            if (selected.size >= maxSentences || idx in used) continue
            val sentence = sentences[idx]
            val projected = total + sentence.length + if (selected.isNotEmpty()) 1 else 0
            if (selected.isNotEmpty() && projected > maxChars) continue
            if (selected.isEmpty() && sentence.length > maxChars) {
                selected.add(idx)
                used.add(idx)
                total = maxChars
                break
            }
            selected.add(idx)
            used.add(idx)
            total = projected
        }
        selected.sort()
        var augmented = false
        if (needsAugmentation(sentences, selected, maxSentences, maxChars)) {
            val augmentedIndices = augmentCharacterExcerptIndices(
                sentences, selected, characterHits, matched, characterSpecs, maxSentences, maxChars,
            )
            selected.clear()
            selected.addAll(augmentedIndices)
            augmented = true
        }
        return buildResult(
            sentences = sentences,
            selectedIndices = selected,
            requestedCharacters = characters,
            matchedCharacters = matched,
            missingCharacters = missing,
            strategy = if (augmented) "character_windows_mixed" else "character_windows",
            maxChars = maxChars,
        )
    }

    private fun buildResult(
        sentences: List<String>,
        selectedIndices: List<Int>,
        requestedCharacters: List<String>,
        matchedCharacters: List<String>,
        missingCharacters: List<String>,
        strategy: String,
        maxChars: Int,
    ): ExcerptPayload {
        val excerpt = renderExcerpt(sentences, selectedIndices, maxChars)
        return ExcerptPayload(
            excerpt = excerpt,
            requestedCharacters = requestedCharacters,
            matchedCharacters = matchedCharacters,
            missingCharacters = missingCharacters,
            strategy = strategy,
            excerptStages = buildStageBlocks(sentences, selectedIndices),
        )
    }

    private fun renderExcerpt(sentences: List<String>, selectedIndices: List<Int>, maxChars: Int): String {
        val selectedSentences = selectedIndices.filter { it in sentences.indices }.mapIndexed { index, idx ->
            val sentence = if (index == 0 && maxChars > 0 && sentences[idx].length > maxChars) {
                sentences[idx].take(maxChars).trim()
            } else {
                sentences[idx]
            }
            evidenceTaggedSentence(idx, sentence)
        }
        return selectedSentences.filter { it.isNotBlank() }.joinToString("\n").take(maxChars).trim()
    }

    private fun evidenceTaggedSentence(index: Int, sentence: String): String =
        "[S${(index + 1).toString().padStart(6, '0')}] ${sentence.trim()}"

    private fun buildStageBlocks(sentences: List<String>, selectedIndices: List<Int>): Map<String, String> {
        val ordered = selectedIndices.filter { it in sentences.indices }.sorted()
        if (ordered.isEmpty()) return emptyStageBlocks()
        val minimum = ordered.first()
        val maximum = ordered.last()
        val span = maxOf(1, maximum - minimum)
        val buckets = linkedMapOf("start" to mutableListOf<String>(), "mid" to mutableListOf<String>(), "end" to mutableListOf<String>())
        for (idx in ordered) {
            val ratio = (idx - minimum).toDouble() / span
            val stage = when {
                ratio <= 0.34 -> "start"
                ratio >= 0.67 -> "end"
                else -> "mid"
            }
            val sentence = evidenceTaggedSentence(idx, sentences[idx])
            if (sentence.isNotEmpty() && sentence !in buckets.getValue(stage)) buckets.getValue(stage).add(sentence)
        }
        return buckets.mapValues { (_, values) -> values.joinToString("\n").trim() }
    }

    private fun emptyStageBlocks(): Map<String, String> = linkedMapOf(
        "start" to "", "mid" to "", "end" to "",
    )

    private fun buildRepresentativeHitPlan(
        characterHits: Map<String, List<Int>>,
        matchedCharacters: List<String>,
        centerBudget: Int,
    ): List<Int> {
        val perCharacter = matchedCharacters.associateWith { name ->
            spreadSampleIndices(
                characterHits.getValue(name),
                sampleCap = maxOf(1, minOf(characterHits.getValue(name).distinct().size, centerBudget)),
            )
        }
        val ordered = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        val maxSlots = perCharacter.values.maxOfOrNull { it.size } ?: 0
        for (slot in 0 until maxSlots) {
            for (name in matchedCharacters) {
                val indices = perCharacter.getValue(name)
                if (slot >= indices.size) continue
                val idx = indices[slot]
                if (idx in seen) continue
                seen.add(idx)
                ordered.add(idx)
                if (ordered.size >= centerBudget) return ordered
            }
        }
        return ordered
    }

    private fun spreadSampleIndices(indices: List<Int>, sampleCap: Int): List<Int> {
        val unique = indices.distinct().sorted()
        if (unique.size <= sampleCap) return unique
        if (sampleCap <= 1) return listOf(unique.first())
        val samples = mutableListOf<Int>()
        val total = unique.size - 1
        for (slot in 0 until sampleCap) {
            val position = kotlin.math.round(total.toDouble() * slot / (sampleCap - 1)).toInt()
            val candidate = unique[position]
            if (candidate !in samples) samples.add(candidate)
        }
        return samples
    }

    private fun candidateIndicesFromCenters(centers: List<Int>, totalSentences: Int): List<Int> {
        val ordered = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        val maxRadius = contextRadiusForCenters(centers, totalSentences)
        for (radius in 0..maxRadius) {
            for (center in centers) {
                for (idx in windowIndices(center, totalSentences, radius)) {
                    if (idx in seen) continue
                    seen.add(idx)
                    ordered.add(idx)
                }
            }
        }
        return ordered
    }

    private fun contextRadiusForCenters(centers: List<Int>, totalSentences: Int): Int {
        if (centers.isEmpty() || totalSentences <= 0) return 1
        val targetSentences = minOf(totalSentences, FOCUSED_CONTEXT_TARGET_SENTENCES)
        val sentencesPerCenter = (targetSentences + centers.size - 1) / centers.size
        return ((sentencesPerCenter - 1) / 2).coerceIn(1, MAX_FOCUSED_CONTEXT_RADIUS)
    }

    private fun windowIndices(center: Int, total: Int, radius: Int): List<Int> {
        val start = maxOf(0, center - radius)
        val end = minOf(total, center + radius + 1)
        return (start until end).toList()
    }

    private fun needsAugmentation(
        sentences: List<String>,
        selectedIndices: List<Int>,
        maxSentences: Int,
        maxChars: Int,
    ): Boolean {
        if (selectedIndices.isEmpty()) return false
        val targetChars = minOf(maxChars, MIXED_EXCERPT_MIN_CHARS)
        val targetSentences = minOf(maxSentences, MIXED_EXCERPT_MIN_SENTENCES)
        val excerpt = renderExcerpt(sentences, selectedIndices, maxChars)
        val currentSentences = splitSentences(excerpt).count { it.isNotBlank() }
        return excerpt.length < targetChars || currentSentences < targetSentences
    }

    private fun augmentCharacterExcerptIndices(
        sentences: List<String>,
        selectedIndices: List<Int>,
        characterHits: Map<String, List<Int>>,
        matchedCharacters: List<String>,
        characterSpecs: List<CharacterSpec>,
        maxSentences: Int,
        maxChars: Int,
    ): List<Int> {
        val used = selectedIndices.filter { it in sentences.indices }.toMutableSet()
        var ordered = used.sorted()

        fun currentChars(): Int = renderExcerpt(sentences, ordered, maxChars).length
        fun enough(): Boolean {
            val targetChars = minOf(maxChars, MIXED_EXCERPT_MIN_CHARS)
            val targetSentences = minOf(maxSentences, MIXED_EXCERPT_MIN_SENTENCES)
            return ordered.size >= targetSentences && currentChars() >= targetChars
        }
        fun tryAdd(index: Int) {
            if (index in used || index !in sentences.indices) return
            if (ordered.size >= maxSentences) return
            val sentence = sentences[index].trim()
            if (sentence.isEmpty()) return
            val projected = currentChars() + sentence.length + if (ordered.isNotEmpty()) 1 else 0
            if (ordered.isNotEmpty() && projected > maxChars) return
            used.add(index)
            ordered = used.sorted()
        }
        fun addCandidates(indices: List<Int>, radius: Int = 0) {
            for (center in indices) {
                for (idx in windowIndices(center, sentences.size, radius)) {
                    tryAdd(idx)
                    if (enough()) return
                }
            }
        }

        val denseCenters = buildDenseHitPlan(characterHits, matchedCharacters, sampleCap = 8)
        addCandidates(denseCenters, radius = 1)
        if (enough()) return ordered

        val timeline = if (sentences.isNotEmpty()) {
            listOf(0, sentences.size / 2, sentences.size - 1).distinct()
        } else {
            emptyList()
        }
        addCandidates(timeline, radius = 1)
        if (enough()) return ordered

        addCandidates(dialogueCandidateIndices(sentences, characterSpecs), radius = 0)
        if (enough()) return ordered

        addCandidates(thoughtOrEvaluationIndices(sentences, characterSpecs), radius = 0)
        return ordered
    }

    private fun buildDenseHitPlan(
        characterHits: Map<String, List<Int>>,
        matchedCharacters: List<String>,
        sampleCap: Int,
    ): List<Int> {
        val ordered = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        for (name in matchedCharacters) {
            for (idx in spreadSampleIndices(characterHits.getValue(name), sampleCap)) {
                if (idx in seen) continue
                seen.add(idx)
                ordered.add(idx)
            }
        }
        return ordered
    }

    private fun looksLikeDialogueSentence(text: String): Boolean {
        val sample = text.trim()
        if (sample.isEmpty()) return false
        if (listOf('"', '“', '”', '「', '」').any { sample.contains(it) }) return true
        return DIALOGUE_PATTERN.containsMatchIn(sample)
    }

    private fun looksLikeThoughtOrEvaluationSentence(text: String): Boolean {
        val sample = text.trim()
        if (sample.isEmpty()) return false
        return THOUGHT_PATTERN.containsMatchIn(sample)
    }

    private fun dialogueCandidateIndices(sentences: List<String>, characters: List<CharacterSpec>): List<Int> {
        val primary = mutableListOf<Int>()
        val secondary = mutableListOf<Int>()
        for ((idx, sentence) in sentences.withIndex()) {
            val text = sentence.trim()
            if (text.isEmpty() || !looksLikeDialogueSentence(text)) continue
            if (characters.any { sentenceMentionsCharacter(text, it) }) primary.add(idx) else secondary.add(idx)
        }
        return primary + secondary
    }

    private fun thoughtOrEvaluationIndices(sentences: List<String>, characters: List<CharacterSpec>): List<Int> {
        val primary = mutableListOf<Int>()
        val secondary = mutableListOf<Int>()
        for ((idx, sentence) in sentences.withIndex()) {
            val text = sentence.trim()
            if (text.isEmpty() || !looksLikeThoughtOrEvaluationSentence(text)) continue
            if (characters.any { sentenceMentionsCharacter(text, it) }) primary.add(idx) else secondary.add(idx)
        }
        return primary + secondary
    }

    private val COMPOUND_SURNAMES = setOf(
        "欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公羊", "赫连", "澹台",
        "公冶", "宗政", "濮阳", "淳于", "单于", "太叔", "申屠", "公孙", "仲孙", "轩辕",
        "令狐", "钟离", "宇文", "长孙", "慕容", "鲜于", "闾丘", "司徒", "司空", "亓官",
        "司寇", "南宫", "西门", "东郭", "左丘", "东门", "第五",
    )
}
