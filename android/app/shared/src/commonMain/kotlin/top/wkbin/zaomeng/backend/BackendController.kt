package top.wkbin.zaomeng.backend

import kotlinx.coroutines.flow.StateFlow

/** 内嵌后端生命周期控制器（三端统一由 LocalBackendController 实现）。 */
interface BackendController {
    val state: StateFlow<BackendState>

    fun start()

    fun retry()
}
