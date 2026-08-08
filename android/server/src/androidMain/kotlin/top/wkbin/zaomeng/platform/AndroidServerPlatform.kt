package top.wkbin.zaomeng.platform

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.backend.ModelApiKeyStore
import java.io.File

/** Android 实现：数据目录 = filesDir/zaomeng，提示词走 assets+文件系统回退，密钥走 Keystore。 */
class AndroidServerPlatform(context: Context) : ServerPlatform {
    private val appContext = context.applicationContext

    override val dataRoot: Path =
        File(appContext.filesDir, "zaomeng").toOkioPath()

    override val promptSource: PromptSource = AndroidPromptSource(appContext)

    private val store = ModelApiKeyStore(appContext)

    override fun secureStore(): SecureKeyValueStore = store
}

private fun File.toOkioPath(): Path = absolutePath.toPath()
