package top.wkbin.zaomeng.plugins.api

import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// 清单（对应 Python plugin.json）
// ---------------------------------------------------------------------------

/** 插件设置项描述（boolean / integer / enum）。 */
data class PluginSettingDescriptor(
    val key: String,
    val label: String,
    val type: String, // boolean | integer | enum
    val defaultValue: String = "",
    val options: List<String> = emptyList(), // enum 选项
)

/** 聊天动作贡献点（对应 Python execute_chat_action）。 */
data class ChatActionDescriptor(
    val id: String,
    val title: String,
    val placement: String = "composer",
    val icon: String = "",
)

/** 生成增强器贡献点（对应 Python enhance_generation；Ktor 端由状态驱动，不动态调用）。 */
data class GenerationEnhancerDescriptor(
    val id: String,
    val title: String,
    val icon: String = "",
)

/** 临时 NPC 生成器贡献点（对应 Python generate_temporary_npc）。 */
data class TemporaryNpcGeneratorDescriptor(
    val id: String,
    val title: String,
    val icon: String = "",
)

data class PluginContributions(
    val chatActions: List<ChatActionDescriptor> = emptyList(),
    val generationEnhancers: List<GenerationEnhancerDescriptor> = emptyList(),
    val temporaryNpcGenerators: List<TemporaryNpcGeneratorDescriptor> = emptyList(),
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val apiVersion: String = "1",
    val description: String = "",
    val permissions: List<String> = emptyList(),
    val settings: List<PluginSettingDescriptor> = emptyList(),
    val contributes: PluginContributions = PluginContributions(),
    val defaultEnabled: Boolean = false,
)

// ---------------------------------------------------------------------------
// 请求 / 结果
// ---------------------------------------------------------------------------

data class ChatActionRequest(
    val runId: String = "",
    val sessionId: String = "",
    val seedText: String = "",
    val direction: String = "",
    /** 两阶段动作中由用户选择的值；首轮为空时插件可返回 [ChatActionResult.choices]。 */
    val selection: String = "",
    /** 插件配置（server 从插件 config.json 注入，如 optionCount/strength/eventType/npcStyle）。 */
    val config: Map<String, Any?> = emptyMap(),
)

data class SuggestionOption(
    val label: String = "",
    val suggestion: String = "",
)

/** 需要用户选择后继续执行的动作选项。 */
data class ChatActionChoice(
    val label: String = "",
    val value: String = "",
    val description: String = "",
)

data class ChatActionResult(
    val suggestion: String = "",
    val suggestions: List<SuggestionOption> = emptyList(),
    val notice: String = "",
    val character: String = "",
    val session: JsonObject = JsonObject(emptyMap()),
    val choicePrompt: String = "",
    val choices: List<ChatActionChoice> = emptyList(),
)

data class NpcGeneratorRequest(
    val runId: String = "",
    val sessionId: String = "",
    val direction: String = "",
    /** 插件配置（server 从插件 config.json 注入，如 npcStyle）。 */
    val config: Map<String, Any?> = emptyMap(),
)

/** 临时 NPC 生成结果：npc 为要写入会话 temporary_npcs 的对象（server 负责落库并返回更新后的会话）。 */
data class NpcGeneratorResult(
    val npc: JsonObject,
    val notice: String = "",
)

data class PluginPersonaSummary(
    val name: String,
    val preview: String = "",
)

data class PluginSessionCharacterSummary(
    val name: String,
    val muted: Boolean = false,
    val canMute: Boolean = true,
)

data class PluginReplyAsCharacterResult(
    val character: String,
    val text: String,
)

// ---------------------------------------------------------------------------
// 宿主（由 server 实现并注入；提供插件需要的模型能力，对齐 Python ZaomengPluginHost）
// ---------------------------------------------------------------------------

interface PluginHost {
    /** 读对话上下文并调用模型生成一段建议文本（对齐 Python read_dialogue_context + invoke_model "dialogue_suggestion"）。 */
    suspend fun invokeSuggestion(runId: String, sessionId: String, seedText: String, direction: String): String

    /** 多候选回复：生成 2-4 个候选（label + suggestion，对齐 Python "dialogue_reply_variants"）。 */
    suspend fun invokeVariants(runId: String, sessionId: String, seedText: String, direction: String): List<SuggestionOption>

    /** 临时 NPC 生成：按方向生成 NPC 并写入会话 temporary_npcs，返回 NPC 对象（键值均为字符串）。 */
    suspend fun invokeNpc(runId: String, sessionId: String, direction: String): JsonObject

    /** 读取插件自己的受限数据区。key 只允许安全标识符，路径不能逃逸插件目录。 */
    suspend fun readPluginData(pluginId: String, key: String): String? = null

    /** 写入插件自己的受限数据区。value 为普通字符串，由插件负责编码。 */
    suspend fun writePluginData(pluginId: String, key: String, value: String) = Unit

    /** 受 network.access 权限约束的公开 HTTP 请求，返回响应文本。 */
    suspend fun invokeHttp(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): String? = null

    /** 列出当前 run 已蒸馏人物及其简短预览。 */
    suspend fun listRunPersonas(runId: String): List<PluginPersonaSummary> = emptyList()

    /** 列出已蒸馏但不在当前场景中的人物。 */
    suspend fun listOffScenePersonas(runId: String, sessionId: String): List<PluginPersonaSummary> = emptyList()

    /** 列出当前场景人物及其禁言状态。 */
    suspend fun listSessionCharacters(runId: String, sessionId: String): List<PluginSessionCharacterSummary> = emptyList()

    /** 以用户明确选择的已蒸馏人物口吻生成回复草稿。 */
    suspend fun invokeReplyAsCharacter(
        runId: String,
        sessionId: String,
        character: String,
        seedText: String,
        direction: String,
    ): PluginReplyAsCharacterResult? = null

    /** 禁言或解除禁言当前会话中的指定人物。返回更新后的 session manifest。 */
    suspend fun setSessionCharacterMuted(
        runId: String,
        sessionId: String,
        character: String,
        muted: Boolean,
    ): JsonObject? = null

    /** 插件日志（写入该插件 plugin-logs.jsonl）。 */
    fun log(pluginId: String, level: String, message: String)
}

// ---------------------------------------------------------------------------
// 插件接口
// ---------------------------------------------------------------------------

interface Plugin {
    val manifest: PluginManifest

    /** 聊天动作（插件菜单点击触发）。未贡献该 action 时返回默认空结果。 */
    suspend fun executeChatAction(actionId: String, request: ChatActionRequest, host: PluginHost): ChatActionResult

    /** 临时 NPC 生成。未贡献该 generator 时返回空 npc。 */
    suspend fun generateTemporaryNpc(generatorId: String, request: NpcGeneratorRequest, host: PluginHost): NpcGeneratorResult
}
