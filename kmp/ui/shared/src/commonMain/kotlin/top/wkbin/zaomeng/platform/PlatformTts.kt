package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨平台文字转语音 (TTS) 播放器抽象。
 */
@Stable
interface PlatformTts {
    /** 当前是否正在播放语音 */
    val isSpeaking: StateFlow<Boolean>

    /** 当前正在播放的语句/消息 ID */
    val currentSpeakingId: StateFlow<String?>

    /**
     * 播放指定文本。
     * @param id 语句唯一标识（用于跟踪播放状态与 UI 动效高亮）
     * @param text 待朗读文本（会自动过滤舞台提示与动作括号）
     * @param pitch 音调倍率 (0.5 ~ 1.5，标准为 1.0)
     * @param speed 语速倍率 (0.5 ~ 2.0，标准为 1.0)
     * @param voiceName 声线预设或系统音色标识
     */
    fun speak(
        id: String,
        text: String,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        voiceName: String = "",
    )

    /** 停止当前播放 */
    fun stop()

    /** 释放 TTS 引擎资源 */
    fun shutdown()
}

/**
 * 智能清洗朗读文本：剔除中文全角括号（...）、半角括号 (...)、方括号 [...] 中的动作与神态描写，
 * 仅保留角色真实说出的台词。
 */
fun cleanSpokenText(raw: String): String {
    if (raw.isBlank()) return ""
    val stripped = raw
        .replace(Regex("（[^）]*）"), "")
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[[^\\]]*\\]"), "")
        .replace(Regex("〔[^〕]*〕"), "")
        .replace(Regex("【[^】]*】"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return stripped.ifBlank { raw.trim() }
}

@Composable
expect fun rememberPlatformTts(): PlatformTts
