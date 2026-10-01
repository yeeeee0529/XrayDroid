package io.github.xraydroid.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServerPhase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class ServerState(
    val phase: ServerPhase = ServerPhase.STOPPED,
    val message: String = "啟動後即可開啟管理面板",
    val logs: List<String> = emptyList(),
    val panelUrl: String = "http://127.0.0.1:2053/",
    val startedAt: Long? = null
)

object ServerStore {
    private val mutableState = MutableStateFlow(ServerState())
    val state = mutableState.asStateFlow()

    fun transition(phase: ServerPhase, message: String) {
        mutableState.update {
            it.copy(
                phase = phase,
                message = message,
                startedAt = if (phase == ServerPhase.RUNNING) System.currentTimeMillis() else null
            )
        }
    }

    fun log(message: String) {
        mutableState.update { it.copy(logs = (it.logs + message).takeLast(80)) }
    }
}
