package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

@Composable
actual fun rememberZipFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    val chooser = JFileChooser()
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val file = chooser.selectedFile
                        if (file.length() > 10L * 1024 * 1024) {
                            error("插件内容超过 10 MB。")
                        }
                        file.name to file.readBytes()
                    } else {
                        null
                    }
                }
                if (picked != null) onPicked(picked.first, picked.second)
            }
        }
    }
}
