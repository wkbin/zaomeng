package top.wkbin.zaomeng.platform

import kotlinx.coroutines.CoroutineDispatcher

/** 平台 IO 调度器（Android/JVM 为 Dispatchers.IO；iOS 上 IO 为 internal，用 Default）。 */
expect val platformIoDispatcher: CoroutineDispatcher