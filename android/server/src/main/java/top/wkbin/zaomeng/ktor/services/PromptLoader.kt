package top.wkbin.zaomeng.ktor.services

import android.content.Context
import android.util.Log
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Prompt configuration loader.
 *
 * Loads prompt templates from YAML files in the prompts/ directory.
 * This mirrors the Python prompts/loader.py functionality.
 *
 * 读取顺序：
 * 1. Android assets（打包进 APK 的 prompts/ 资源，真机运行时使用）
 * 2. 文件系统回退（开发机/测试环境使用）
 */
class PromptLoader(private val context: Context) {
    companion object {
        private const val TAG = "PromptLoader"
    }

    private val yaml = Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))
    private val promptsRoot: File by lazy {
        // 开发机回退路径：prompts 目录位于 Android 项目上级
        val filesDir = context.filesDir
        val projectRoot = filesDir.parentFile?.parentFile?.parentFile?.parentFile
        projectRoot?.resolve("prompts")
            ?: throw IllegalStateException("Could not locate prompts directory")
    }

    /**
     * Load a prompt configuration file as a Map.
     */
    private fun loadPromptConfig(category: String, name: String): Map<String, Any> {
        val source = readPromptSource("$category/$name.yaml")
            ?: throw IllegalStateException("Prompt config not found: $category/$name.yaml")
        return try {
            @Suppress("UNCHECKED_CAST")
            source.use { yaml.load(it) as Map<String, Any> }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse prompt config: $category/$name.yaml", e)
            throw IllegalStateException("Failed to parse prompt config: $name.yaml", e)
        }
    }

    /**
     * 读取提示词资源：优先 assets（打包进 APK 的 prompts 目录内容），回退文件系统。
     */
    private fun readPromptSource(relativePath: String): java.io.InputStream? {
        // 1. assets 优先（prompts 目录整体打包为 assets 根内容）
        runCatching {
            return context.assets.open(relativePath)
        }.onFailure { /* fall through to filesystem */ }
        // 2. 文件系统回退
        val configFile = promptsRoot.resolve(relativePath)
        if (!configFile.exists()) return null
        return configFile.inputStream()
    }

    private fun Map<String, Any>.getString(key: String): String {
        return this[key] as? String ?: throw IllegalStateException("Missing key: $key")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.getList(key: String): List<Map<String, Any>> {
        return this[key] as? List<Map<String, Any>> ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.getMap(key: String): Map<String, Any> {
        return this[key] as? Map<String, Any> ?: emptyMap()
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
}
