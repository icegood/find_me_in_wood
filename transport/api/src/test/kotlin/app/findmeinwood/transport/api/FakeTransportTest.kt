package app.findmeinwood.transport.api

import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class FakeTransportTest {
    private val config = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.BLUETOOTH))

    @Test
    fun `start emits ready stop emits stopped`() = runTest {
        val t = FakeTransport(TransportId.BLUETOOTH)
        val ev = t.start(config).first() as TransportEvent.StateChanged
        assertEquals(RadioState.READY, ev.state)
        t.stop()
    }

    @Test
    fun `deliver injects received frames`() = runTest {
        val t = FakeTransport(TransportId.BLUETOOTH)
        t.start(config)
        val frame = app.findmeinwood.core.model.EncryptedFrame(
            NetworkId(ByteArray(8)), app.findmeinwood.core.model.MemberId(ByteArray(32)),
            0u, 1u, 0L, ByteArray(2),
        )
        t.deliver(frame)
        val ev = t.start(config).first { it is TransportEvent.FrameReceived }
            as TransportEvent.FrameReceived
        assertEquals(frame, ev.frame)
    }

    @Test
    fun `send while link up records and streams`() = runTest {
        val t = FakeTransport(TransportId.LORA)
        t.start(config)
        val wire = byteArrayOf(1, 2, 3)
        assertIs<SendResult.Sent>(t.send(wire))
        assertEquals(listOf(wire.toList()), t.sentHistory.map { it.toList() })
        assertEquals(wire.toList(), t.sentWire.first().toList())
    }

    @Test
    fun `send fails when stopped or link down`() = runTest {
        val t = FakeTransport(TransportId.WIFI_DIRECT)
        assertIs<SendResult.Failed>(t.send(byteArrayOf(1))) // never started
        t.start(config)
        t.linkUp = false
        val r = t.send(byteArrayOf(1))
        assertIs<SendResult.Failed>(r)
        assertTrue(t.sentHistory.isEmpty())
        t.stop()
    }
}
