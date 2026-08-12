package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.Preferences

/** DataStore 偏好文件名（三端一致）。 */
const val dataStoreFileName = "zaomeng.preferences_pb"

/**
 * 通用 DataStore 创建入口：文件系统 API 因平台而异，
 * 各平台源集只负责构造 [Storage]，这里统一交给 DataStoreFactory。
 */
fun createDataStore(storage: Storage<Preferences>): DataStore<Preferences> =
    DataStoreFactory.create(storage = storage)
