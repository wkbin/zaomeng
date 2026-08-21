package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.darwin.NSObject

internal class IosPlatformTts : PlatformTts {
    private val synthesizer = AVSpeechSynthesizer()
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var activeId: String? = null

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            _isSpeaking.value = true
            _currentSpeakingId.value = activeId
        }

        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            _isSpeaking.value = false
            _currentSpeakingId.value = null
            activeId = null
        }

        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            _isSpeaking.value = false
            _currentSpeakingId.value = null
            activeId = null
        }
    }

    init {
        synthesizer.delegate = delegate
    }

    override fun speak(
        id: String,
        text: String,
        pitch: Float,
        speed: Float,
        voiceName: String,
    ) {
        val clean = cleanSpokenText(text)
        if (clean.isBlank()) return

        stop()

        activeId = id
        val utterance = AVSpeechUtterance(string = clean)
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage("zh-CN")
        utterance.pitchMultiplier = pitch.coerceIn(0.5f, 2.0f)
        utterance.rate = (speed * 0.5f).coerceIn(0.1f, 1.0f)

        synthesizer.speakUtterance(utterance)
        _isSpeaking.value = true
        _currentSpeakingId.value = id
    }

    override fun stop() {
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(platform.AVFAudio.AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        _isSpeaking.value = false
        _currentSpeakingId.value = null
        activeId = null
    }

    override fun shutdown() {
        stop()
    }
}

@Composable
actual fun rememberPlatformTts(): PlatformTts {
    val platformTts = remember { IosPlatformTts() }
    DisposableEffect(Unit) {
        onDispose {
            platformTts.stop()
        }
    }
    return platformTts
}
