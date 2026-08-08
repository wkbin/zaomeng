package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePicker(onPicked: (uri: String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    val chooser = JFileChooser().apply {
                        fileFilter = FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "webp", "gif", "bmp")
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        chooser.selectedFile.absolutePath
                    } else {
                        null
                    }
                }
                if (path != null) onPicked(path)
            }
        }
    }
}
