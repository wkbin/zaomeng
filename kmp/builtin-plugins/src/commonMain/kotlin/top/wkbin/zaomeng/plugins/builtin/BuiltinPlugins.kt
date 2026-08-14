package top.wkbin.zaomeng.plugins.builtin

import top.wkbin.zaomeng.plugins.api.Plugin

/**
 * 内置插件注册表：server 通过它加载全部官方内置插件（source = "official"）。
 * 新增内置插件 = 在此列表追加一个实现。
 */
object BuiltinPlugins {
    val all: List<Plugin> = listOf(
        AiAssociationPlugin(),
        ReplyVariantsPlugin(),
        VoicePolishPlugin(),
        PlotDicePlugin(),
        RandomNpcPlugin(),
        InnerThoughtsPlugin(),
        ReplyAsCharacterPlugin(),
        CharacterMutePlugin(),
    )
}
