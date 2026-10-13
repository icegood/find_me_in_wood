package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryApiTest {
    @Test
    fun `findability and join links are no-ops for radios without them`() {
        val t = FakeTransport(TransportId.LORA)
        t.setFindable(byteArrayOf(1))
        t.setFindable(null)
        t.connect("AA:BB")
        val ev = TransportEvent.NetworkAdvertSeen("AA:BB", byteArrayOf(1, 2), TransportId.BLUETOOTH)
        assertEquals("AA:BB", ev.radioPeerId)
        assertEquals(TransportId.BLUETOOTH, ev.transport)
        assertEquals(2, ev.info.size)
        val cfg = SessionConfig(NetworkId(ByteArray(8)), emptySet(), listening = true)
        assertTrue(cfg.listening)
        assertTrue(!SessionConfig(NetworkId(ByteArray(8)), emptySet()).listening)
    }
}
