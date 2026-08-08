package top.wkbin.zaomeng.plugins.builtin

import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.ChatActionResult
import top.wkbin.zaomeng.plugins.api.GenerationEnhancerDescriptor
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorResult
import top.wkbin.zaomeng.plugins.api.Plugin
import top.wkbin.zaomeng.plugins.api.PluginContributions
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginManifest

/**
 * 角色读心（迁移自 main 分支 src/builtin_plugins/inner_thoughts/main.py）。
 * 生成增强器：启用后对话回复附带内心活动（include_inner_thoughts）。
 * 该插件无聊天动作，由 server 在构建对话请求时读取会话内 enhancer 状态驱动。
 */
class InnerThoughtsPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.zaomeng.inner-thoughts",
        name = "角色读心",
        description = "仅对当前聊天生效，开启后回复附带角色未说出口的内心活动。",
        permissions = listOf("chat.context.read", "generation.enhance"),
        contributes = PluginContributions(
            generationEnhancers = listOf(
                GenerationEnhancerDescriptor(id = "inner-thoughts", title = "角色读心", icon = "visibility"),
            ),
        ),
        defaultEnabled = false,
    )

    override suspend fun executeChatAction(actionId: String, request: ChatActionRequest, host: PluginHost): ChatActionResult = ChatActionResult()

    override suspend fun generateTemporaryNpc(generatorId: String, request: NpcGeneratorRequest, host: PluginHost): NpcGeneratorResult =
        NpcGeneratorResult(npc = JsonObject(emptyMap()))
}
