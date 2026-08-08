package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.PromptSource
import top.wkbin.zaomeng.platform.parseYaml

/**
 * Prompt configuration loader.
 *
 * Loads prompt templates from YAML files in the prompts/ directory.
 * This mirrors the Python prompts/loader.py functionality.
 *
 * 读取顺序由 PromptSource 平台实现决定：
 * 1. Android assets（打包进 APK 的 prompts/ 资源，真机运行时使用）
 * 2. 文件系统回退（开发机/测试环境使用）
 *
 * 缓存策略与迁移前一致：按 mtime 失效（assets 视为构建期静态，mtime=0）。
 */
class PromptLoader(private val source: PromptSource) {
    companion object {
        private const val TAG = "PromptLoader"
    }

    /**
     * 提示词缓存：key = 相对路径，value = Pair<mtime, 解析结果>。
     */
    private val promptCache = HashMap<String, Pair<Long, Any?>>()

    /**
     * Load a prompt configuration file as a Map.
     */
    private fun loadPromptConfig(category: String, name: String): Map<String, Any> {
        val key = "$category/$name.yaml"
        @Suppress("UNCHECKED_CAST")
        return cached(
            key,
            load = { content -> parseYaml(content) as Map<String, Any> },
        ) as? Map<String, Any> ?: throw IllegalStateException("Prompt config not found: $key")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.getList(key: String): List<Map<String, Any>> {
        return this[key] as? List<Map<String, Any>> ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.getMap(key: String): Map<String, Any> {
        return this[key] as? Map<String, Any> ?: emptyMap()
    }

    private fun Map<String, Any>.getString(key: String): String {
        return this[key] as? String ?: throw IllegalStateException("Missing key: $key")
    }

    /**
     * Get dialogue director prompt.
     */
    fun getDialogueDirectorPrompt(optionCount: Int = 3, retry: Boolean = false): String {
        val config = loadPromptConfig("dialogue", "director")
        val parts = mutableListOf(
            config.getString("system_prompt"),
            config.getString("option_count_instruction").replace("{option_count}", optionCount.toString()),
            config.getString("output_format")
        )
        if (retry) {
            parts.add(config.getString("retry_instruction"))
        }
        return parts.joinToString("\n")
    }

    /**
     * Get dialogue suggestions prompt.
     */
    fun getDialogueSuggestionsPrompt(
        optionCount: Int = 3,
        retry: Boolean = false,
        generationGoal: String = "",
        outputRule: String = ""
    ): String {
        val config = loadPromptConfig("dialogue", "suggestions")
        val parts = mutableListOf(
            config.getString("system_prompt"),
            config.getString("option_count_instruction").replace("{option_count}", optionCount.toString()),
            config.getString("additional_rules"),
            config.getString("output_format"),
            generationGoal,
            outputRule
        )
        if (retry) {
            parts.add(config.getString("retry_instruction"))
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * Get consistency review prompt.
     */
    fun getConsistencyReviewPrompt(): String {
        val config = loadPromptConfig("dialogue", "consistency_review")
        return listOf(
            config.getString("system_prompt"),
            config.getString("output_format")
        ).joinToString("\n")
    }

    /**
     * Get inner thought rule.
     */
    fun getInnerThoughtRule(): String {
        val config = loadPromptConfig("dialogue", "inner_thought_rule")
        return config.getString("rule")
    }

    /**
     * Get novel rewrite prompt.
     */
    fun getNovelRewritePrompt(): String {
        val config = loadPromptConfig("chapters", "novel_rewrite")
        val principles = config.getList("core_principles")
        val principlesText = principles.mapIndexed { index, principle ->
            val name = principle["name"] as? String ?: ""
            val rule = principle["rule"] as? String ?: ""
            "${index + 1}. $name。$rule"
        }.joinToString("\n")

        return listOf(
            config.getString("system_prompt"),
            "",
            "核心原则：",
            principlesText,
            "",
            config.getString("output_rules")
        ).joinToString("\n")
    }

    /**
     * Get scene card generation prompt.
     */
    fun getSceneCardGenerationPrompt(): String {
        val config = loadPromptConfig("review", "scene_card_generation")
        return config.getString("system_prompt")
    }

    /**
     * Get self card generation prompt.
     */
    fun getSelfCardGenerationPrompt(): String {
        val config = loadPromptConfig("review", "self_card_generation")
        return config.getString("system_prompt")
    }

    /**
     * Get persona completion prompt.
     *
     * @param mode knowledge_based / web_based / simple
     */
    fun getPersonaCompletionPrompt(mode: String = "knowledge_based"): String {
        val config = loadPromptConfig("review", "persona_completion")
        val modeConfig = config.getMap(mode)
        return modeConfig.getString("system_prompt")
    }

    // ------------------------------------------------------------------
    // 2026-08-08 追加：对话 turn-system 文本块 / 卡片 / 人物补全 / 问书卷 / 章节改写 / 蒸馏
    // ------------------------------------------------------------------

    /**
     * 读取提示词配置的单个文本 key（如 turn_system.yaml 的规则块）。
     */
    fun getPromptText(category: String, name: String, key: String): String {
        val config = loadPromptConfig(category, name)
        return config[key] as? String ?: ""
    }

    /**
     * 读取原始提示词文件（assets 优先，回退 prompts/ 与 zaomeng-skill/）。
     * 用于整篇文档类提示词（蒸馏/关系的 .md），保持 md 原样。
     */
    fun loadRawPrompt(relativePath: String): String? {
        return cached(
            relativePath,
            load = { content -> content.trim() },
        ) as? String
    }

    /**
     * 按 mtime 缓存的读取辅助：assets（mtime=0）命中即永久复用；
     * 文件系统回退路径在 mtime 变化时重新加载。
     */
    private fun cached(
        key: String,
        load: (String) -> Any?,
    ): Any? {
        val entry = source.read(key)
        val mtime = entry?.second ?: 0L
        synchronized(promptCache) {
            promptCache[key]?.let { (cachedMtime, value) ->
                if (cachedMtime == mtime) return value
            }
        }
        val text = entry?.first ?: return synchronized(promptCache) { promptCache[key]?.second }
        val loaded = runCatching { load(text) }.getOrElse { e ->
            PlatformLog.e(TAG, "Failed to parse prompt config: $key", e)
            synchronized(promptCache) { promptCache[key]?.second }
        }
        synchronized(promptCache) {
            promptCache[key] = mtime to loaded
        }
        return loaded
    }

    /** 对话回复/续写建议的 turn-system 文本块。 */
    fun getTurnSystemRule(key: String): String = getPromptText("dialogue", "turn_system", key)

    /** 卡片生成 user 指令（{field_lines} 由调用方填充）。 */
    fun getCardInstruction(kind: String, fieldLines: String): String {
        val config = loadPromptConfig("review", "card_instructions")
        val card = config[kind] as? Map<*, *> ?: return ""
        val instruction = card["instruction"] as? String ?: return ""
        return instruction.replace("{field_lines}", fieldLines)
    }

    /** 人物字段补全 user 模板。 */
    fun getPersonaSuggestFieldTemplate(): String = getPromptText("review", "persona_suggest", "template")

    /** 问书卷 user 模板。 */
    fun getAskBookTemplate(): String = getPromptText("chapters", "ask", "template")

    /** 章节改写 user 模板。 */
    fun getChapterRewriteUserTemplate(): String = getPromptText("chapters", "rewrite_user", "template")

    /** 蒸馏/关系 guidance 文本块（YAML）。 */
    fun getDistillGuidance(): Map<String, String> {
        val config = loadPromptConfig("distill", "guidance")
        return config.mapValues { (_, value) -> value.toString() }
    }
}
