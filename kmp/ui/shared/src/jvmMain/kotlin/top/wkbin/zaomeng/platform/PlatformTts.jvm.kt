package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal class JvmPlatformTts : PlatformTts {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    override val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    private var activeJob: Job? = null
    private var activeProcess: Process? = null

    private val osName = System.getProperty("os.name", "").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac")

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

        activeJob = scope.launch {
            _isSpeaking.value = true
            _currentSpeakingId.value = id
            try {
                when {
                    isWindows -> speakWindows(clean, speed)
                    isMac -> speakMac(clean, speed, voiceName)
                    else -> speakLinux(clean, speed)
                }
            } catch (e: Exception) {
                // Ignore platform process launch/cancellation errors
            } finally {
                if (_currentSpeakingId.value == id) {
                    _isSpeaking.value = false
                    _currentSpeakingId.value = null
                }
            }
        }
    }

    private fun speakWindows(text: String, speed: Float) {
        val rate = ((speed - 1.0f) * 5).toInt().coerceIn(-10, 10)
        val escaped = text.replace("'", "''").replace("\"", "`\"")
        val script = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Rate = $rate; \$s.Speak('$escaped');"
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
        activeProcess = process
        process.waitFor()
    }

    private fun speakMac(text: String, speed: Float, voiceName: String) {
        val rate = (speed * 180).toInt().coerceIn(100, 350)
        val cmd = mutableListOf("say", "-r", rate.toString())
        if (voiceName.isNotBlank()) {
            cmd.addAll(listOf("-v", voiceName))
        }
        cmd.add(text)
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        activeProcess = process
        process.waitFor()
    }

    private fun speakLinux(text: String, speed: Float) {
        val rate = ((speed - 1.0f) * 50).toInt().coerceIn(-50, 50)
        val process = ProcessBuilder("spd-say", "-r", rate.toString(), "-l", "zh", text)
            .redirectErrorStream(true)
            .start()
        activeProcess = process
        process.waitFor()
    }

    override fun stop() {
        activeJob?.cancel()
        activeJob = null
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {}
        activeProcess = null
        _isSpeaking.value = false
        _currentSpeakingId.value = null
    }

    override fun shutdown() {
        stop()
    }
}

@Composable
actual fun rememberPlatformTts(): PlatformTts {
    val platformTts = remember { JvmPlatformTts() }
    DisposableEffect(Unit) {
        onDispose {
            platformTts.stop()
        }
    }
    return platformTts
}
