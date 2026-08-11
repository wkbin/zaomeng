package top.wkbin.zaomeng.data.api

import okio.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class PluginsResponse(
    val items: List<PluginDto> = emptyList(),
)

@Serializable
data class PluginDto(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    @SerialName("apiVersion") val apiVersion: String = "",
    val description: String = "",
    val permissions: List<String> = emptyList(),
    val settings: List<PluginSettingDto> = emptyList(),
    val config: JsonObject = JsonObject(emptyMap()),
    val contributes: PluginContributionsDto = PluginContributionsDto(),
    @SerialName("defaultEnabled") val defaultEnabled: Boolean = false,
    val enabled: Boolean = false,
    val status: String = "disabled",
    val error: String = "",
    val source: String = "third-party",
    val executable: Boolean = false,
    @SerialName("executionMode") val executionMode: String = "unsupported",
    @SerialName("capabilityNotice") val capabilityNotice: String = "",
)

@Serializable
data class PluginSettingDto(
    val key: String = "",
    val title: String = "",
    val type: String = "",
    val default: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull,
    val min: Int? = null,
    val max: Int? = null,
    val options: List<PluginSettingOptionDto> = emptyList(),
)

@Serializable
data class PluginSettingOptionDto(
    val value: String = "",
    val label: String = "",
)

@Serializable
data class InspectPluginPackageRequest(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
)

@Serializable
data class PluginPackageInspectionDto(
    val token: String = "",
    val plugin: PluginDto = PluginDto(),
    val operation: String = "install",
    val blockedReason: String = "",
    val currentVersion: String = "",
    val compatible: Boolean = true,
    val hostApiVersion: String = "1",
    val fileCount: Int = 0,
    val extractedBytes: Long = 0,
)

@Serializable
data class InstallPluginPackageRequest(
    @SerialName("confirm_permissions") val confirmPermissions: Boolean,
    @SerialName("allow_update") val allowUpdate: Boolean,
)

@Serializable
data class UninstallPluginResponse(
    val status: String = "",
    val pluginId: String = "",
    val recoverablePath: String = "",
    val uninstalledAt: String = "",
)

@Serializable
data class PluginLogsResponse(
    val items: List<PluginLogDto> = emptyList(),
)

@Serializable
data class PluginConfigResponse(
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class UpdatePluginConfigRequest(
    val config: JsonObject,
)

@Serializable
data class PluginLogDto(
    val timestamp: String = "",
    val pluginId: String = "",
    val level: String = "info",
    val event: String = "",
    val message: String = "",
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PluginContributionsDto(
    @SerialName("chatActions") val chatActions: List<PluginChatActionDto> = emptyList(),
    @SerialName("generationEnhancers")
    val generationEnhancers: List<PluginGenerationEnhancerDto> = emptyList(),
    @SerialName("temporaryNpcGenerators")
    val temporaryNpcGenerators: List<PluginTemporaryNpcGeneratorDto> = emptyList(),
)

@Serializable
data class PluginChatActionDto(
    val id: String = "",
    val title: String = "",
    val placement: String = "composer",
    val icon: String = "",
)

@Serializable
data class PluginGenerationEnhancerDto(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val icon: String = "",
    @SerialName("defaultActive") val defaultActive: Boolean = false,
)

@Serializable
data class PluginTemporaryNpcGeneratorDto(
    val id: String = "",
    val title: String = "",
    val icon: String = "",
)

@Serializable
data class SetGenerationEnhancerStateRequest(val enabled: Boolean)

