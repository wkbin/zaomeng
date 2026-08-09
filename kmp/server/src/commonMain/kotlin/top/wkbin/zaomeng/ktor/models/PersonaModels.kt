package top.wkbin.zaomeng.ktor.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SuggestPersonaFieldRequest(
    val field: String,
    @SerialName("current_fields") val currentFields: Map<String, String> = emptyMap()
)
