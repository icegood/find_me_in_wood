package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.crypto.MgmtCodec
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeTransportMgmtTest {
    @Test
    fun `deliverWire accepts raw management bytes`() = runBlocking {
        val t = FakeTransport(TransportId.BLUETOOTH)
        val job = launch {
            t.start(SessionConfig(NetworkId(ByteArray(8)), emptySet())).collect { }
        }
        val mgmt = byteArrayOf(0x46, 0x4D, 2, 1) + ByteArray(60)
        assertTrue(MgmtCodec.isMgmt(mgmt))
        t.deliverWire(mgmt)
        job.cancel()
    }

    @Test
    fun `mgmt frame event carries the raw wire`() {
        val wire = byteArrayOf(0x46, 0x4D, 2, 1, 9)
        val ev = TransportEvent.MgmtFrameReceived(wire, TransportId.LORA)
        assertTrue(ev.wire.contentEquals(wire))
        assertEquals(TransportId.LORA, ev.source)
        val twin = TransportEvent.MgmtFrameReceived(wire.copyOf(), TransportId.LORA)
        assertTrue(twin.wire.contentEquals(ev.wire))
        assertEquals(twin.source, ev.source)
        assertTrue(ev.toString().isNotEmpty())
    }
}
