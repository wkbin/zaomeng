package top.wkbin.zaomeng.navigation

import kotlinx.serialization.Serializable

@Serializable
data object BookshelfDestination

@Serializable
data object ImportBookDestination

@Serializable
data object OnlineLibraryDestination

@Serializable
data object CrossoverDestination

@Serializable
data object ModelSettingsDestination

@Serializable
data object ModelConfigurationDestination

@Serializable
data class ModelProfileEditorDestination(val profileId: String = "")

@Serializable
data object ChatDisplaySettingsDestination

@Serializable
data object PluginsDestination

@Serializable
data object AppearanceSettingsDestination

@Serializable
data object StartupRecoverySettingsDestination

@Serializable
data object AppSupportSettingsDestination

@Serializable
data object AppUpdateDestination

@Serializable
data class RunDetailDestination(val runId: String)

@Serializable
data class RedistillDestination(val runId: String)

@Serializable
data class RelationsDestination(val runId: String)

@Serializable
data class WorldTimelineDestination(val runId: String)

@Serializable
data class ChaptersDestination(val runId: String)

@Serializable
data object CardLibraryDestination

@Serializable
data class PersonaDestination(val runId: String, val character: String)

@Serializable
data class SessionsDestination(val runId: String = "")

@Serializable
data class ChatDestination(val runId: String, val sessionId: String)

@Serializable
data class StoryRecapDestination(val runId: String, val sessionId: String)
