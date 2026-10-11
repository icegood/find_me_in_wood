package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkProp
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
    private val switchesA = mutableListOf<LinkNegotiator.Switch>()
    private val switchesB = mutableListOf<LinkNegotiator.Switch>()

    /** One beacon from A to B and one back, received on [t] (null = nothing gets through). */
    private fun exchange(t: TransportId? = BLUETOOTH, direct: Boolean = true) {
        val fromA = a.outgoing()
        if (t != null) switchesB += b.onFrame(idA, t, direct, fromA)
        val fromB = b.outgoing()
        if (t != null) switchesA += a.onFrame(idB, t, direct, fromB)
    }

    private fun meet() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT), LinkOffer(SMS, "+380001")))
        b.setOffers(listOf(LinkOffer(SMS, "+380002"), LinkOffer(BLUETOOTH)))
        repeat(3) { exchange() }
    }

    /** Lets repeated acceptances run out, so new proposals may start. */
    private fun settle() = repeat(LinkNegotiator.ACCEPT_BEACONS) { exchange(t = null) }

    @Test
    fun `first contact establishes the link explicitly by propose and accept`() {
        meet()
        assertEquals(listOf(BLUETOOTH, SMS), a.common(idB))
        assertEquals(BLUETOOTH, a.link(idB))
        assertEquals(BLUETOOTH, b.link(idA))
        assertTrue(switchesA.isNotEmpty() && switchesB.isNotEmpty())
    }

    @Test
    fun `a weak link is switched only after the peer accepts over a channel that still works`() {
        meet()
        settle()
        now += 2 * beacon // A missed B's beacons on Bluetooth
        a.tick()
        assertEquals(SMS, a.proposing(idB))
        assertEquals(BLUETOOTH, a.link(idB), "proposing is not switching")
        exchange(BLUETOOTH, direct = false) // proposal relayed, B accepts, acceptance relayed back
        assertEquals(SMS, b.link(idA))
        assertEquals(SMS, a.link(idB))
        assertEquals(listOf(idB to "+380002"), a.peersOn(SMS))
        assertEquals(listOf(idA to "+380001"), b.peersOn(SMS))
    }

    @Test
    fun `no fallback when nothing gets through - the proposal expires and the link stays`() {
        meet()
        settle()
        now += 2 * beacon
        a.tick()
        assertEquals(SMS, a.proposing(idB))
        repeat(LinkNegotiator.PROPOSE_BEACONS) { a.outgoing() } // sent into the void
        assertNull(a.proposing(idB), "unanswered proposal expired")
        assertEquals(BLUETOOTH, a.link(idB))
        assertEquals(BLUETOOTH, b.link(idA))
        assertTrue(a.peersOn(SMS).isEmpty(), "nobody is texted without an accepted switch")
    }

    @Test
    fun `back on a cheaper radio is proposed and accepted too`() {
        meet()
        settle()
        now += 2 * beacon
        a.tick()
        exchange(BLUETOOTH, direct = false)
        assertEquals(SMS, a.link(idB))
        settle()
        exchange(BLUETOOTH) // heard directly again -> proposal
        exchange(BLUETOOTH) // -> accept
        assertEquals(BLUETOOTH, a.link(idB))
        assertEquals(BLUETOOTH, b.link(idA))
        assertTrue(a.peersOn(SMS).isEmpty())
    }

    @Test
    fun `a proposal for a transport the peer cannot use gets no answer`() {
        meet()
        val ctl = LinkCtl(offers = a.offers(), prop = listOf(LinkProp(LinkNegotiator.prefix(idB), WIFI_DIRECT, 9)))
        assertTrue(b.onFrame(idA, BLUETOOTH, direct = true, ctl = ctl).isEmpty())
        assertEquals(BLUETOOTH, b.link(idA))
        assertTrue(b.outgoing()?.acc.orEmpty().none { it.t == WIFI_DIRECT })
    }

    @Test
    fun `simultaneous proposals - equal epochs resolve to the lower member id`() {
        meet()
        settle()
        now += 2 * beacon
        a.tick(); b.tick()
        assertEquals(SMS, a.proposing(idB))
        assertEquals(SMS, b.proposing(idA))
        exchange(BLUETOOTH, direct = false)
        exchange(BLUETOOTH, direct = false)
        assertEquals(SMS, a.link(idB))
        assertEquals(SMS, b.link(idA))
    }

    @Test
    fun `stale proposals and other protocol versions are ignored`() {
        meet()
        val old = LinkCtl(offers = a.offers(), prop = listOf(LinkProp(LinkNegotiator.prefix(idB), SMS, 1)))
        assertTrue(b.onFrame(idA, BLUETOOTH, direct = true, ctl = old).isEmpty())
        assertEquals(BLUETOOTH, b.link(idA))
        assertTrue(b.onFrame(idA, BLUETOOTH, direct = true, ctl = LinkCtl(v = 1)).isEmpty())
    }

    @Test
    fun `display name and offers are announced on change and every fifth beacon`() {
        meet()
        settle()
        a.setName("  Ira  ")
        assertEquals("Ira", a.outgoing()!!.name)
        a.setName("Ira")
        val sent = (1..LinkNegotiator.OFFERS_EVERY).map { a.outgoing() }
        assertEquals(1, sent.count { it != null })
        assertTrue(a.onFrame(idA, BLUETOOTH, direct = true, ctl = null).isEmpty(), "own frames ignored")
        assertEquals(BLUETOOTH, a.all().single().link)
        assertFalse(LORA in a.common(idB))
    }
}
