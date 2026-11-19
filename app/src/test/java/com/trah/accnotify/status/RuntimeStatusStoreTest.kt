package com.trah.accnotify.status

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeStatusStoreTest {
    @Before
    fun setUp() {
        RuntimeStatusStore.reset()
    }

    @After
    fun tearDown() {
        RuntimeStatusStore.reset()
    }

    @Test
    fun `default status is inactive and stopped`() {
        val status = RuntimeStatusStore.state.value

        assertFalse(status.xposedActive)
        assertFalse(status.systemServerBound)
        assertEquals(WebSocketRuntimeState.STOPPED, status.webSocketState)
    }

    @Test
    fun `authenticated bridge marks module and binding active`() {
        RuntimeStatusStore.markSystemServerConnected()

        val status = RuntimeStatusStore.state.value
        assertTrue(status.xposedActive)
        assertTrue(status.systemServerBound)
    }

    @Test
    fun `disconnect keeps activation while clearing binding`() {
        RuntimeStatusStore.markSystemServerConnected()
        RuntimeStatusStore.markSystemServerDisconnected()

        val status = RuntimeStatusStore.state.value
        assertTrue(status.xposedActive)
        assertFalse(status.systemServerBound)
    }

    @Test
    fun `websocket state updates independently`() {
        RuntimeStatusStore.markSystemServerConnected()
        RuntimeStatusStore.updateWebSocket(WebSocketRuntimeState.RECONNECTING)

        val status = RuntimeStatusStore.state.value
        assertTrue(status.xposedActive)
        assertTrue(status.systemServerBound)
        assertEquals(WebSocketRuntimeState.RECONNECTING, status.webSocketState)
    }
}
