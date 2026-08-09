package top.wkbin.zaomeng.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath

/** Android：偏好文件放在应用私有 filesDir。 */
fun createDataStore(context: Context): DataStore<Preferences> = createDataStore(
    storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { context.filesDir.resolve(dataStoreFileName).absolutePath.toPath() },
    ),
)
