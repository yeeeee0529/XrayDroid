package io.github.xraydroid.runtime

import io.github.xraydroid.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServerPhase { STOPPED, STARTING, RUNNING, STOPPING, WAITING_FOR_NETWORK, ERROR }

data class ServerState(
    val phase: ServerPhase = ServerPhase.STOPPED,
    val message: TextResource? = TextResource(R.string.server_message_idle),
    val logs: List<String> = emptyList(),
    val panelUrl: String = "http://127.0.0.1:2053/",
    val startedAt: Long? = null,
    val outboundNetworkLabel: TextResource? = null
)

object ServerStore {
    private val mutableState = MutableStateFlow(ServerState())
    val state = mutableState.asStateFlow()

    fun transition(phase: ServerPhase, message: TextResource?, outboundNetworkLabel: TextResource? = null) {
        mutableState.update {
            it.copy(
                phase = phase,
                message = message,
                startedAt = if (phase == ServerPhase.RUNNING) System.currentTimeMillis() else null,
                outboundNetworkLabel = if (phase == ServerPhase.RUNNING) outboundNetworkLabel else null
            )
        }
    }

    fun log(message: String) {
        mutableState.update { it.copy(logs = (it.logs + message).takeLast(80)) }
    }
}
