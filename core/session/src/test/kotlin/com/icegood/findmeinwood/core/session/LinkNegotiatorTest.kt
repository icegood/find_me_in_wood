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
import com.icegood.findmeinwood.transport.api.GroupRole
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
        exchange() // offers known on both sides
        a.confirm(idB) // each user confirms on their own phone
        b.confirm(idA)
        repeat(2) { exchange() }
    }

    @Test
    fun `the handshake needs a confirmation on both phones`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        repeat(3) { exchange() }
        assertFalse(a.agreed(idB), "offers alone agree nothing")
        assertNull(a.link(idB))
        a.confirm(idB)
        repeat(3) { exchange() }
        assertFalse(a.agreed(idB), "only A's user confirmed")
        assertFalse(b.agreed(idA))
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), a.all().single().allowedByMe)
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), b.all().single().allowedByPeer, "B sees that A confirmed")
        assertTrue(b.all().single().allowedByMe.isEmpty())
        b.confirm(idA)
        repeat(2) { exchange() }
        assertTrue(a.agreed(idB) && b.agreed(idA))
        assertEquals(BLUETOOTH, a.link(idB))
    }

    @Test
    fun `a confirmation covers a smaller set, a new transport needs a new one`() {
        handshake()
        b.setOffers(listOf(LinkOffer(BLUETOOTH))) // B switched Wi-Fi and SMS off
        repeat(2) { exchange() }
        assertTrue(a.agreed(idB), "subset of what both confirmed: still agreed")
        assertEquals(listOf(BLUETOOTH), a.common(idB))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(SMS, "+380099"))) // new number
        repeat(2) { exchange() }
        assertEquals(listOf(BLUETOOTH), a.all().single().agreed, "the new SMS number was never confirmed; Bluetooth stays")
        assertEquals(listOf(SMS), a.all().single().let { v -> v.common - v.allowedByMe.toSet() - v.deniedByMe.toSet() })
        a.confirm(idB); b.confirm(idA)
        repeat(2) { exchange() }
        assertTrue(a.agreed(idB))
        assertEquals(listOf(idB to "+380099"), a.select(idB, SMS).let { a.peersOn(SMS) })
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
        assertTrue(b.select(idA, BLUETOOTH).isEmpty(), "B has A's offers but nobody confirmed yet")
        assertNull(b.link(idA))
        exchange()
        a.confirm(idB); b.confirm(idA)
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
    fun `a withdrawn offer keeps the agreement and the link moves within the smaller set`() {
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

    @Test
    fun `frames go on every radio until a set is agreed, then only on the agreed ones`() {
        assertNull(a.radios(), "nobody met yet: every radio")
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT), LinkOffer(LORA)))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        exchange()
        assertNull(a.radios(), "B is in its handshake: every radio")
        a.confirm(idB); b.confirm(idA)
        repeat(2) { exchange() }
        assertEquals(setOf(BLUETOOTH, WIFI_DIRECT), a.radios(), "LoRa was never agreed with anyone")
        now += beacon * 10
        assertEquals(setOf(BLUETOOTH, WIFI_DIRECT), a.radios(), "a quiet peer keeps its agreed radios")
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT), LinkOffer(LORA)))
        exchange()
        assertNull(a.radios(), "a new transport needs a new confirmation: every radio meanwhile")
    }

    @Test
    fun `undecided radios keep carrying frames when the peer falls silent`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        exchange()
        a.allow(idB, BLUETOOTH, false)
        exchange()
        now += beacon * 4
        assertEquals(setOf(WIFI_DIRECT), a.radios(), "quiet peer: the turned-down radio rests, the undecided one goes on")
        assertEquals(setOf(BLUETOOTH, WIFI_DIRECT), b.radios(), "nothing decided: both go on, so the pair can meet again")
    }

    @Test
    fun `the lower member id hosts the Wi-Fi Direct group while the pair hears each other`() {
        assertNull(a.wifiDirectRole())
        handshake()
        assertEquals(GroupRole.HOST, a.wifiDirectRole())
        assertEquals(GroupRole.JOIN, b.wifiDirectRole())
        now += beacon * 4
        assertNull(a.wifiDirectRole(), "nobody agreed in reach: the radio finds a group itself")
        assertNull(b.wifiDirectRole())
        exchange()
        assertEquals(GroupRole.HOST, a.wifiDirectRole())
        b.setOffers(listOf(LinkOffer(BLUETOOTH))) // B switched Wi-Fi off
        repeat(2) { exchange() }
        assertNull(a.wifiDirectRole(), "Wi-Fi Direct no longer agreed")
    }

    @Test
    fun `each transport is allowed on its own and connects only when both users allowed it`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        exchange()
        a.allow(idB, WIFI_DIRECT, true)
        b.allow(idA, BLUETOOTH, true)
        repeat(2) { exchange() }
        assertTrue(a.all().single().agreed.isEmpty(), "nothing both allowed")
        assertEquals(listOf(BLUETOOTH), a.all().single().allowedByPeer)
        b.allow(idA, WIFI_DIRECT, true)
        repeat(2) { exchange() }
        assertEquals(listOf(WIFI_DIRECT), a.all().single().agreed)
        assertEquals(WIFI_DIRECT, a.link(idB))
        assertEquals(WIFI_DIRECT, b.link(idA))
        a.allow(idB, BLUETOOTH, false) // A turns Bluetooth down explicitly
        repeat(2) { exchange() }
        assertEquals(listOf(BLUETOOTH), a.all().single().deniedByMe)
        assertEquals(setOf(WIFI_DIRECT), a.radios(), "A transmits only on what its user allowed")
        b.allow(idA, WIFI_DIRECT, false) // B disconnects Wi-Fi Direct
        exchange()
        assertTrue(a.all().single().agreed.isEmpty() && b.all().single().agreed.isEmpty())
        assertNull(a.link(idB))
        assertEquals(setOf(BLUETOOTH), b.radios())
    }

    @Test
    fun `an ack goes out until the peer echoes it, then link control rests`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH)))
        b.setOffers(listOf(LinkOffer(BLUETOOTH)))
        exchange()
        a.allow(idB, BLUETOOTH, true)
        assertTrue(a.replyNow(), "a decision is announced at once")
        b.allow(idA, BLUETOOTH, true)
        repeat(3) { exchange() }
        assertTrue(a.agreed(idB) && b.agreed(idA))
        assertFalse(a.replyNow() || b.replyNow(), "both acks echoed: nothing pending")
        assertEquals(1, (1..LinkNegotiator.OFFERS_EVERY).count { a.outgoing() != null })
    }

    @Test
    fun `an ack from an older version without a list allows every common transport`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
        val theirs = listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT))
        a.onFrame(idB, BLUETOOTH, true, LinkCtl(offers = theirs))
        a.confirm(idB)
        val legacy = com.icegood.findmeinwood.core.model.LinkAck(LinkNegotiator.prefix(idA), a.hash(idB, theirs))
        a.onFrame(idB, BLUETOOTH, true, LinkCtl(offers = theirs, acks = listOf(legacy)))
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), a.all().single().agreed)
    }
}
