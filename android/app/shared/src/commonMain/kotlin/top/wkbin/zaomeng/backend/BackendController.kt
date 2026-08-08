package top.wkbin.zaomeng.backend

import kotlinx.coroutines.flow.StateFlow

/** 内嵌后端生命周期控制器（Android 由 BackendManager 实现，桌面/iOS 由平台引导实现）。 */
interface BackendController {
    val state: StateFlow<BackendState>

    fun start()

    fun retry()
}
