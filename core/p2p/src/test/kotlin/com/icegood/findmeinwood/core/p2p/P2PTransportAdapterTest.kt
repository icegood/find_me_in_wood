package com.icegood.findmeinwood.core.p2p

import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P2PTransportAdapterTest {

    private class FakeTransport : Transport {
        override val id = TransportId.BLUETOOTH
        var lastWire: ByteArray? = null
        var stopped = false
        override fun start(config: SessionConfig): Flow<TransportEvent> = flowOf()
        override suspend fun stop() { stopped = true }
        override suspend fun send(wireFrame: ByteArray): SendResult {
            lastWire = wireFrame
            return SendResult.Sent
        }
    }

    @Test
    fun `adapter wraps transport and sends successfully`() = runTest {
        val transport = FakeTransport()
        val adapter = P2PTransportAdapter("test-adapter", "ble", transport)
        val sent = adapter.send("peer1", byteArrayOf(1, 2, 3))
        assertTrue(sent)
        assertEquals(3, transport.lastWire!!.size)
    }

    @Test
    fun `adapter close delegates to transport stop`() = runTest {
        val transport = FakeTransport()
        val adapter = P2PTransportAdapter("test-adapter", "ble", transport)
        adapter.close()
        assertTrue(transport.stopped)
    }

    @Test
    fun `adapter isAvailable returns true`() {
        val adapter = P2PTransportAdapter("test", "tcp", FakeTransport())
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun `adapter id and protocol are set correctly`() {
        val adapter = P2PTransportAdapter("my-id", "ws", FakeTransport())
        assertEquals("my-id", adapter.id)
        assertEquals("ws", adapter.protocol)
    }

    @Test
    fun `adapter open returns flow`() = runTest {
        val adapter = P2PTransportAdapter("test", "ble", FakeTransport())
        val flow = adapter.open()
        val events = flow.toList()
        assertTrue(events.isEmpty())
    }
}
