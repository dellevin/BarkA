package com.trah.accnotify.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WebSocketRuntimeState {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

data class RuntimeStatus(
    val xposedActive: Boolean = false,
    val systemServerBound: Boolean = false,
    val webSocketState: WebSocketRuntimeState = WebSocketRuntimeState.STOPPED,
)

object RuntimeStatusStore {
    private val mutableState = MutableStateFlow(RuntimeStatus())
    val state: StateFlow<RuntimeStatus> = mutableState.asStateFlow()

    fun markSystemServerConnected() {
        mutableState.update { it.copy(xposedActive = true, systemServerBound = true) }
    }

    fun markSystemServerDisconnected() {
        mutableState.update { it.copy(systemServerBound = false) }
    }

    fun updateWebSocket(state: WebSocketRuntimeState) {
        mutableState.update { it.copy(webSocketState = state) }
    }

    internal fun reset() {
        mutableState.value = RuntimeStatus()
    }
}
