package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** 桌面：默认存到用户主目录 ~/.zaomeng/ 下，可通过参数覆盖路径。 */
fun createDataStore(
    path: Path = (System.getProperty("user.home") ?: ".").toPath() / ".zaomeng" / dataStoreFileName,
): DataStore<Preferences> = createDataStore(
    storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { path },
    ),
)
