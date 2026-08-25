package app.findmeinwood.core.session

import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.TransportId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeManagerTest {
    private var now = 1_000_000L
    private val self = MemberId(ByteArray(32) { 1 })
    private val peer = MemberId(ByteArray(32) { 2 })
    private val em = EdgeManager(self, { now })

    @Test
    fun `agreement flow - waiting until peer frame confirms`() {
        em.setTransport(peer, TransportId.LORA)
        assertEquals(EdgeManager.State.WAITING_PEER, em.edge(peer)!!.state)
        em.onFrameFrom(peer, TransportId.LORA)
        val e = em.edge(peer)!!
        assertEquals(EdgeManager.State.UP, e.state)
        assertEquals(TransportId.LORA, e.transport)
        assertEquals(TransportId.LORA, e.lastWorking)
    }

    @Test
    fun `frame on another transport re-homes edge`() {
        em.setTransport(peer, TransportId.BLUETOOTH)
        em.onFrameFrom(peer, TransportId.BLUETOOTH)
        em.onFrameFrom(peer, TransportId.LORA)
        assertEquals(TransportId.LORA, em.edge(peer)!!.transport)
    }

    @Test
    fun `failover order - last working first, battery cost, excludes dead and unprovisioned`() {
        em.setTransport(peer, TransportId.LORA)
        em.onFrameFrom(peer, TransportId.LORA)
        em.onEdgeDown(peer)
        val all = TransportId.entries.toSet()
        assertEquals(
            listOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT, TransportId.INTERNET),
            em.failoverOrder(peer, all),
        )
        // without LoRa node provisioned and no INTERNET app path
        val limited = setOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT)
        assertEquals(
            listOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT),
            em.failoverOrder(peer, limited),
        )
    }

    @Test
    fun `stale peer goes down and is reported`() {
        em.setTransport(peer, TransportId.BLUETOOTH)
        em.onFrameFrom(peer, TransportId.BLUETOOTH)
        now += 3 * 60_000 + 1
        val wentDown = em.tick()
        assertEquals(listOf(peer), wentDown)
        assertEquals(EdgeManager.State.DOWN, em.edge(peer)!!.state)
    }

    @Test
    fun `fresh peer stays up`() {
        em.setTransport(peer, TransportId.BLUETOOTH)
        em.onFrameFrom(peer, TransportId.BLUETOOTH)
        now += 2 * 60_000
        assertTrue(em.tick().isEmpty())
    }
}
