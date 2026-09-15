package com.icegood.findmeinwood.core.p2p

import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiProtocolP2PManagerTest {

    private class FakeTransport(override val id: com.icegood.findmeinwood.core.model.TransportId) : Transport {
        var lastWire: ByteArray? = null
        var stopped = false
        override fun start(config: SessionConfig): Flow<TransportEvent> = flowOf()
        override suspend fun stop() { stopped = true }
        override suspend fun send(wireFrame: ByteArray): SendResult {
            lastWire = wireFrame
            return SendResult.Sent
        }
    }

    private class FakeChannel(
        override val id: String,
        override val protocol: String,
    ) : P2PChannel {
        var sentTo: String? = null
        var sentPayload: ByteArray? = null
        var closed = false
        override fun open(): Flow<P2PEvent> = flowOf()
        override suspend fun send(peerId: String, payload: ByteArray): Boolean {
            sentTo = peerId
            sentPayload = payload
            return true
        }
        override suspend fun close() { closed = true }
        override fun isAvailable(): Boolean = true
    }

    @Test
    fun `send via best transport picks first ready`() = runTest {
        val t1 = FakeTransport(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH)
        val t2 = FakeTransport(com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT)
        val mgr = MultiProtocolP2PManager(
            transports = mapOf(
                com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH to t1,
                com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT to t2,
            ),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        val result = mgr.sendViaBestTransport(byteArrayOf(1, 2, 3))
        assertTrue(result is SendResult.Sent)
        assertTrue(t1.lastWire != null || t2.lastWire != null)
    }

    @Test
    fun `send via specific transport`() = runTest {
        val t1 = FakeTransport(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH)
        val mgr = MultiProtocolP2PManager(
            transports = mapOf(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH to t1),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        val result = mgr.sendViaTransport(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH, byteArrayOf(4, 5))
        assertTrue(result is SendResult.Sent)
        assertEquals(2, t1.lastWire!!.size)
    }

    @Test
    fun `send via channel`() = runTest {
        val ch = FakeChannel("ch1", "ble")
        val mgr = MultiProtocolP2PManager(emptyMap(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        mgr.registerChannel(ch)
        val ok = mgr.sendViaChannel("ch1", "peer1", byteArrayOf(10))
        assertTrue(ok)
        assertEquals("peer1", ch.sentTo)
    }

    @Test
    fun `stop all closes channels and transports`() = runTest {
        val t1 = FakeTransport(com.icegood.findmeinwood.core.model.TransportId.LORA)
        val ch = FakeChannel("ch1", "tcp")
        val mgr = MultiProtocolP2PManager(
            transports = mapOf(com.icegood.findmeinwood.core.model.TransportId.LORA to t1),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        mgr.registerChannel(ch)
        mgr.stopAll()
        assertTrue(ch.closed)
        assertTrue(t1.stopped)
    }

    @Test
    fun `available protocols includes transport and channel protocols`() {
        val t1 = FakeTransport(com.icegood.findmeinwood.core.model.TransportId.INTERNET)
        val ch = FakeChannel("ch1", "ws")
        val mgr = MultiProtocolP2PManager(
            transports = mapOf(com.icegood.findmeinwood.core.model.TransportId.INTERNET to t1),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        mgr.registerChannel(ch)
        val protocols = mgr.getAvailableProtocols()
        assertTrue("INTERNET" in protocols)
        assertTrue("ws" in protocols)
    }

    @Test
    fun `send via specific transport returns failed for nonexistent transport`() = runTest {
        val mgr = MultiProtocolP2PManager(emptyMap(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val result = mgr.sendViaTransport(com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH, byteArrayOf(1))
        assertTrue(result is com.icegood.findmeinwood.transport.api.SendResult.Failed)
    }

    @Test
    fun `send via best transport returns failed when no transports`() = runTest {
        val mgr = MultiProtocolP2PManager(emptyMap(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val result = mgr.sendViaBestTransport(byteArrayOf(1))
        assertTrue(result is com.icegood.findmeinwood.transport.api.SendResult.Failed)
    }

    @Test
    fun `send via channel returns false for nonexistent channel`() = runTest {
        val mgr = MultiProtocolP2PManager(emptyMap(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val ok = mgr.sendViaChannel("nonexistent", "peer1", byteArrayOf(1))
        assertFalse(ok)
    }

    @Test
    fun `getProtocolForPeer returns empty for unknown peer`() {
        val mgr = MultiProtocolP2PManager(emptyMap(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val protocols = mgr.getProtocolForPeer("unknown-peer")
        assertTrue(protocols.isEmpty())
    }

    @Test
    fun `empty transports and channels produce empty protocols`() {
        val mgr = MultiProtocolP2PManager(emptyMap(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertTrue(mgr.getAvailableProtocols().isEmpty())
    }
}
