package top.wkbin.zaomeng.platform

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal class AndroidPlatformTts(context: Context) : PlatformTts {
    private val appContext = context.applicationContext
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        _currentSpeakingId.value = utteranceId
                    }

                    override fun onDone(utteranceId: String?) {
                        if (_currentSpeakingId.value == utteranceId) {
                            _isSpeaking.value = false
                            _currentSpeakingId.value = null
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (_currentSpeakingId.value == utteranceId) {
                            _isSpeaking.value = false
                            _currentSpeakingId.value = null
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (_currentSpeakingId.value == utteranceId) {
                            _isSpeaking.value = false
                            _currentSpeakingId.value = null
                        }
                    }
                })
                pendingSpeak?.invoke()
                pendingSpeak = null
            }
        }
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

        val action: () -> Unit = {
            tts?.let { engine ->
                engine.stop()
                engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
                engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
                if (voiceName.isNotBlank()) {
                    val targetVoice = engine.voices?.firstOrNull { it.name.contains(voiceName, ignoreCase = true) }
                    if (targetVoice != null) {
                        engine.voice = targetVoice
                    }
                }
                val params = Bundle()
                engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id)
                _isSpeaking.value = true
                _currentSpeakingId.value = id
            }
        }

        if (isInitialized) {
            action()
        } else {
            pendingSpeak = action
        }
    }

    override fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _currentSpeakingId.value = null
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

@Composable
actual fun rememberPlatformTts(): PlatformTts {
    val context = LocalContext.current
    val platformTts = remember { AndroidPlatformTts(context) }
    DisposableEffect(Unit) {
        onDispose {
            platformTts.stop()
        }
    }
    return platformTts
}
