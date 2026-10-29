package com.icegood.findmeinwood.transport.api

import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryApiTest {
    @Test
    fun `findability and join links are no-ops for radios without them, and so are group roles`() {
        val t = FakeTransport(TransportId.LORA)
        t.setFindable(byteArrayOf(1))
        t.setFindable(null)
        t.connect("AA:BB")
        t.setGroups(GroupRole.entries.map { GroupPlan(byteArrayOf(1), it) })
        t.serveNow(0)
        t.closeLink("AA:BB")
        assertEquals(GroupPlan(byteArrayOf(1), GroupRole.HOST, 2), GroupPlan(byteArrayOf(1), GroupRole.HOST, 2))
        assertTrue(GroupPlan(byteArrayOf(1), GroupRole.JOIN) != GroupPlan(byteArrayOf(2), GroupRole.JOIN))
        assertEquals(GroupPlan(byteArrayOf(1), GroupRole.JOIN).hashCode(), GroupPlan(byteArrayOf(1), GroupRole.JOIN).hashCode())
        t.setGroupSecrets(byteArrayOf(1), listOf(byteArrayOf(2)))
        assertEquals("DIRECT-fm-0a0b0c0d", GroupPlan(byteArrayOf(10, 11, 12, 13, 14), GroupRole.JOIN).networkName)
        kotlinx.coroutines.runBlocking { assertEquals(null, t.relay(byteArrayOf(1), except = "AA:BB"), "radios relay back only when they can") }
        assertEquals("L", TransportEvent.FrameReceived(
            com.icegood.findmeinwood.core.model.EncryptedFrame(NetworkId(ByteArray(8)), com.icegood.findmeinwood.core.model.MemberId(ByteArray(32)), 1u, 3u, 0L, ByteArray(16)),
            TransportId.WIFI_DIRECT, "L",
        ).link)
        val ev = TransportEvent.NetworkAdvertSeen("AA:BB", byteArrayOf(1, 2), TransportId.BLUETOOTH)
        assertEquals("AA:BB", ev.radioPeerId)
        assertEquals(TransportId.BLUETOOTH, ev.transport)
        assertEquals(2, ev.info.size)
        val cfg = SessionConfig(NetworkId(ByteArray(8)), emptySet(), listening = true)
        assertTrue(cfg.listening)
        assertTrue(!SessionConfig(NetworkId(ByteArray(8)), emptySet()).listening)
    }
}
