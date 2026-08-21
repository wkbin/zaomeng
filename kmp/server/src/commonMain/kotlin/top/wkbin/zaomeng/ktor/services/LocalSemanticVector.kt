package top.wkbin.zaomeng.ktor.services

import kotlin.math.sqrt

/**
 * 端侧轻量语义向量与余弦相似度计算引擎。
 * 100% 纯 Kotlin Multiplatform 实现，通过多阶字符 N-Gram（单字、双字、三字子词）频率特征加权
 * 与稠密/稀疏向量余弦相似度，在零外部模型依赖下提供精确、零哈希噪点且毫秒级的语义相关度计算。
 */
object LocalSemanticVector {

    /**
     * 抽取加权 N-Gram 特征映射。
     * - 1-gram（单字）：捕获基础字义，权重 0.6
     * - 2-gram（双字）：核心构词与意图单元，权重 1.2
     * - 3-gram（三字）：高阶语义搭配，权重 1.8
     */
    fun extractNgramWeights(text: String): Map<String, Float> {
        val normalized = text.lowercase().trim()
        if (normalized.isBlank()) return emptyMap()

        val weights = HashMap<String, Float>()

        // 1-gram
        for (i in normalized.indices) {
            val char = normalized[i]
            if (!char.isWhitespace()) {
                val key = char.toString()
                weights[key] = (weights[key] ?: 0f) + 0.6f
            }
        }

        // 2-gram
        for (i in 0 until normalized.length - 1) {
            val bigram = normalized.substring(i, i + 2)
            if (!bigram.any(Char::isWhitespace)) {
                weights[bigram] = (weights[bigram] ?: 0f) + 1.2f
            }
        }

        // 3-gram
        for (i in 0 until normalized.length - 2) {
            val trigram = normalized.substring(i, i + 3)
            if (!trigram.any(Char::isWhitespace)) {
                weights[trigram] = (weights[trigram] ?: 0f) + 1.8f
            }
        }

        return weights
    }

    /**
     * 计算两段文本在多阶 N-Gram 空间下的余弦相似度，区间严格落在 [0.0, 1.0]。
     * 无字词交集时严格返回 0.0f，杜绝哈希投影碰撞引起的误召回。
     */
    fun similarity(text1: String, text2: String): Float {
        if (text1.isBlank() || text2.isBlank()) return 0.0f
        val w1 = extractNgramWeights(text1)
        val w2 = extractNgramWeights(text2)
        if (w1.isEmpty() || w2.isEmpty()) return 0.0f

        var dot = 0.0f
        var magSq1 = 0.0f
        var magSq2 = 0.0f

        for (value in w1.values) {
            magSq1 += value * value
        }
        for (value in w2.values) {
            magSq2 += value * value
        }

        // 遍历较小的集合以加速点积计算
        val (smaller, larger) = if (w1.size <= w2.size) (w1 to w2) else (w2 to w1)
        for ((ngram, weight) in smaller) {
            val otherWeight = larger[ngram] ?: continue
            dot += weight * otherWeight
        }

        val denominator = sqrt(magSq1) * sqrt(magSq2)
        if (denominator <= 1e-6f) return 0.0f

        return (dot / denominator).coerceIn(0.0f, 1.0f)
    }
}
