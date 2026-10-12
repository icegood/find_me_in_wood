package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkSel
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH
import com.icegood.findmeinwood.core.model.TransportId.LORA
import com.icegood.findmeinwood.core.model.TransportId.SMS
import com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkNegotiatorTest {
    private var now = 1_000_000L
    private val beacon = 60_000L
    private val sms = 30 * 60_000L
    private val idA = MemberId(ByteArray(32) { 1 })
    private val idB = MemberId(ByteArray(32) { 2 })
    private val a = LinkNegotiator(idA, { now }, beacon, sms)
    private val b = LinkNegotiator(idB, { now }, beacon, sms)

    /** One beacon from A to B and one back, received on [t] (null = nothing gets through). */
    private fun exchange(t: TransportId? = BLUETOOTH, direct: Boolean = true) {
        val fromA = a.outgoing()
        if (t != null) b.onFrame(idA, t, direct, fromA)
        val fromB = b.outgoing()
        if (t != null) a.onFrame(idB, t, direct, fromB)
    }

    private fun handshake() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT), LinkOffer(SMS, "+380001")))
        b.setOffers(listOf(LinkOffer(SMS, "+380002"), LinkOffer(WIFI_DIRECT), LinkOffer(BLUETOOTH)))
        repeat(3) { exchange() }
    }

    @Test
    fun `handshake agrees the common set once and starts on the cheapest`() {
        handshake()
        assertTrue(a.agreed(idB) && b.agreed(idA))
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT, SMS), a.common(idB))
        assertEquals(a.hash(idB, b.offers()), b.hash(idA, a.offers()))
        assertEquals(BLUETOOTH, a.link(idB))
        assertEquals(BLUETOOTH, b.link(idA))
    }

    @Test
    fun `switching within the agreed set is instant and the peer follows without answering`() {
        handshake()
        val sw = a.select(idB, SMS).single()
        assertEquals(LinkNegotiator.Switch(idB, BLUETOOTH, SMS), sw)
        assertEquals(SMS, a.link(idB), "applies at once, no handshake")
        assertEquals(listOf(idB to "+380002"), a.peersOn(SMS))
        b.onFrame(idA, BLUETOOTH, direct = false, ctl = a.outgoing()) // even relayed
        assertEquals(SMS, b.link(idA))
        assertEquals(listOf(idA to "+380001"), b.peersOn(SMS))
        assertTrue(b.outgoing()?.sel.orEmpty().isEmpty(), "the peer does not answer a selection")
        // And back, just as instantly.
        a.select(idB, WIFI_DIRECT)
        b.onFrame(idA, WIFI_DIRECT, direct = true, ctl = a.outgoing())
        assertEquals(WIFI_DIRECT, b.link(idA))
    }

    @Test
    fun `nothing outside the agreed set can be selected, and nothing before the handshake`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(SMS, "+1")))
        b.setOffers(listOf(LinkOffer(BLUETOOTH)))
        b.onFrame(idA, BLUETOOTH, direct = true, ctl = a.outgoing())
        assertTrue(b.select(idA, BLUETOOTH).isEmpty(), "B has A's offers but A has not acknowledged yet")
        assertNull(b.link(idA))
        repeat(2) { exchange() }
        assertTrue(a.select(idB, SMS).isEmpty(), "B does not offer SMS")
        val ack = com.icegood.findmeinwood.core.model.LinkAck(LinkNegotiator.prefix(idB), a.hash(idB, b.offers()))
        val forged = LinkCtl(v = 3, offers = a.offers(), acks = listOf(ack), sel = listOf(LinkSel(LinkNegotiator.prefix(idB), LORA, 99)))
        b.onFrame(idA, BLUETOOTH, direct = true, ctl = forged)
        assertEquals(BLUETOOTH, b.link(idA))
    }

    @Test
    fun `no fallback - a quiet link only yields a suggestion`() {
        handshake()
        repeat(LinkNegotiator.SELECT_BEACONS + 2) { exchange(t = null) }
        now += 2 * beacon // nothing heard on Bluetooth for two beacons
        assertTrue(a.tick().isEmpty())
        assertEquals(BLUETOOTH, a.link(idB), "still Bluetooth: nothing switches by itself")
        assertEquals(WIFI_DIRECT, a.suggest(idB))
        assertTrue(a.peersOn(SMS).isEmpty())
        exchange(BLUETOOTH)
        assertNull(a.suggest(idB), "heard again: no suggestion")
    }

    @Test
    fun `concurrent selections - higher epoch wins, equal epochs the lower member id`() {
        handshake()
        a.select(idB, SMS)          // A epoch 1
        b.select(idA, WIFI_DIRECT)  // B epoch 1
        exchange()
        assertEquals(SMS, a.link(idB))
        assertEquals(SMS, b.link(idA), "same epoch: A (lower id) wins")
        b.select(idA, WIFI_DIRECT)  // epoch 2
        exchange()
        assertEquals(WIFI_DIRECT, a.link(idB))
    }

    @Test
    fun `a withdrawn offer re-opens the handshake and the link moves within the new set`() {
        handshake()
        a.select(idB, SMS)
        exchange()
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT))) // B stops offering SMS
        repeat(2) { exchange() }
        assertTrue(a.agreed(idB))
        assertFalse(SMS in a.common(idB))
        assertEquals(BLUETOOTH, a.link(idB))
        assertTrue(a.peersOn(SMS).isEmpty())
    }

    @Test
    fun `name and offers are announced on change and every fifth beacon`() {
        handshake()
        repeat(3) { a.outgoing() }
        a.setName("  Ira  ")
        assertEquals("Ira", a.outgoing()!!.name)
        a.setName("Ira")
        assertEquals(1, (1..LinkNegotiator.OFFERS_EVERY).count { a.outgoing() != null })
        assertTrue(a.onFrame(idA, BLUETOOTH, direct = true, ctl = null).isEmpty(), "own frames ignored")
        assertTrue(a.onFrame(idB, BLUETOOTH, direct = true, ctl = LinkCtl(v = 2)).isEmpty(), "other versions ignored")
        assertEquals(BLUETOOTH, a.all().single().link)
    }
}
