package top.wkbin.zaomeng.backend

sealed interface BackendState {
    data object Idle : BackendState
    data class Starting(val message: String) : BackendState
    data class Ready(val baseUrl: String) : BackendState
    data class Failed(val message: String) : BackendState
}
