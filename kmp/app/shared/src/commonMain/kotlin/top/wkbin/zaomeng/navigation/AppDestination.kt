package top.wkbin.zaomeng.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * 顶层三页（书架 / 对话 / 更多）的宿主。
 *
 * 这三个 tab 不再各自作为导航栈条目，而是同一个条目里的 HorizontalPager 页面，
 * 这样切换 tab 是“翻页”而不是 NavDisplay 的整页推入动画；详情页仍压在它上面。
 */
@Serializable
data object TopLevelDestination : NavKey

@Serializable
data object BookshelfDestination : NavKey

@Serializable
data object MoreDestination : NavKey

@Serializable
data object ImportBookDestination : NavKey

@Serializable
data object OnlineLibraryDestination : NavKey

@Serializable
data object CrossoverDestination : NavKey

@Serializable
data object ModelSettingsDestination : NavKey

@Serializable
data object ModelConfigurationDestination : NavKey

@Serializable
data class ModelProfileEditorDestination(val profileId: String = "") : NavKey

@Serializable
data object ChatDisplaySettingsDestination : NavKey

@Serializable
data object PluginsDestination : NavKey

@Serializable
data object PluginBuilderDestination : NavKey

@Serializable
data object AppearanceSettingsDestination : NavKey

@Serializable
data object StartupRecoverySettingsDestination : NavKey

@Serializable
data object AppSupportSettingsDestination : NavKey

@Serializable
data object AppUpdateDestination : NavKey

@Serializable
data class RunDetailDestination(val runId: String) : NavKey

@Serializable
data class RedistillDestination(val runId: String) : NavKey

@Serializable
data class RelationsDestination(val runId: String) : NavKey

@Serializable
data class WorldTimelineDestination(val runId: String) : NavKey

@Serializable
data class OriginalKnowledgeDestination(val runId: String) : NavKey

@Serializable
data class ChaptersDestination(val runId: String) : NavKey

@Serializable
data object CardLibraryDestination : NavKey

@Serializable
data class PersonaDestination(val runId: String, val character: String) : NavKey

@Serializable
data class SessionsDestination(val runId: String = "") : NavKey

@Serializable
data class ChatDestination(val runId: String, val sessionId: String) : NavKey

@Serializable
data class StoryRecapDestination(val runId: String, val sessionId: String) : NavKey
