package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberId
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

    /** One beacon each way on [t], carrying the control block only when the sender adds one. */
    private fun exchange(t: com.icegood.findmeinwood.core.model.TransportId = BLUETOOTH) {
        b.onFrame(idA, t, direct = true, ctl = a.outgoing())
        a.onFrame(idB, t, direct = true, ctl = b.outgoing())
    }

    private fun agree() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT), LinkOffer(SMS, "+380001")))
        b.setOffers(listOf(LinkOffer(SMS, "+380002"), LinkOffer(BLUETOOTH)))
        repeat(3) { exchange() }
    }

    @Test
    fun `both sides derive the same ladder and agree on it by hash`() {
        agree()
        assertEquals(listOf(BLUETOOTH, SMS), a.ladder(idB))
        assertEquals(listOf(BLUETOOTH, SMS), b.ladder(idA))
        assertEquals(a.hash(idB, b.offers()), b.hash(idA, a.offers()))
        assertTrue(a.agreed(idB))
        assertTrue(b.agreed(idA))
        assertEquals(BLUETOOTH, a.active(idB))
    }

    @Test
    fun `no agreement without the peer's ack, so nobody is texted unasked`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(SMS, "+380001")))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(SMS, "+380002")))
        // A hears B's offers, but B never hears A: B cannot have acknowledged anything.
        a.onFrame(idB, BLUETOOTH, direct = true, ctl = LinkCtl(offers = b.offers()))
        assertFalse(a.agreed(idB))
        now += 10 * beacon
        a.tick()
        assertNull(a.active(idB))
        assertTrue(a.peersOn(SMS).isEmpty())
        assertTrue(a.agreedAddresses(SMS).isEmpty())
    }

    @Test
    fun `losing the radio steps both sides down to SMS, and back up on a direct frame`() {
        agree()
        now += 3 * beacon
        val downA = a.tick().single()
        val downB = b.tick().single()
        assertEquals(LinkNegotiator.Switch(idB, BLUETOOTH, SMS), downA)
        assertEquals(SMS, downB.to)
        assertEquals(listOf(idB to "+380002"), a.peersOn(SMS))
        assertEquals(listOf(idA to "+380001"), b.peersOn(SMS))

        // An SMS report keeps the SMS rung alive; it does not bring radios back.
        a.onFrame(idB, SMS, direct = true, ctl = null)
        assertEquals(SMS, a.active(idB))

        val up = a.onFrame(idB, BLUETOOTH, direct = true, ctl = null).single()
        assertEquals(LinkNegotiator.Switch(idB, SMS, BLUETOOTH), up)
        assertTrue(a.peersOn(SMS).isEmpty())
    }

    @Test
    fun `a peer still reached through relays costs nothing`() {
        agree()
        now += 3 * beacon
        a.onFrame(idB, BLUETOOTH, direct = false, ctl = null) // relayed by another member
        a.tick()
        assertNull(a.active(idB))
        assertTrue(a.peersOn(SMS).isEmpty())
    }

    @Test
    fun `costly rungs are tried in order, each for its liveness window`() {
        a.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(LORA), LinkOffer(SMS, "+1")))
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(LORA), LinkOffer(SMS, "+2")))
        repeat(3) { exchange() }
        now += 3 * beacon
        assertEquals(LORA, a.tick().single().to)
        now += 3 * beacon
        assertEquals(SMS, a.tick().single().to)
        now += 3 * sms
        assertTrue(a.tick().isEmpty(), "SMS is the last rung: stay there")
        assertEquals(LORA, a.onFrame(idB, LORA, direct = true, ctl = null).single().to)
    }

    @Test
    fun `display name is announced on change only`() {
        agree()
        assertNull(a.outgoing())
        a.setName("  Ira  ")
        assertEquals("Ira", a.outgoing()!!.name)
        a.setName("Ira")
        assertNull(a.outgoing(), "same name again is not news")
    }

    @Test
    fun `changed offers re-open the agreement and are announced at once`() {
        agree()
        assertNull(a.outgoing(), "nothing changed, not every 5th beacon")
        b.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(SMS, "+380099")))
        val ctl = b.outgoing()!!
        assertFalse(b.agreed(idA), "B's new number is not acknowledged by A yet")
        a.onFrame(idB, BLUETOOTH, direct = true, ctl = ctl)
        assertTrue(a.agreed(idB), "B's announcement already acknowledges the new ladder")
        b.onFrame(idA, BLUETOOTH, direct = true, ctl = a.outgoing())
        assertTrue(b.agreed(idA))
        assertEquals(listOf(idB to "+380099"), a.agreedAddresses(SMS))
    }

    @Test
    fun `offers are repeated every fifth beacon and own frames are ignored`() {
        agree()
        val sent = (1..LinkNegotiator.OFFERS_EVERY).map { a.outgoing() }
        assertEquals(1, sent.count { it != null })
        assertTrue(a.onFrame(idA, BLUETOOTH, direct = true, ctl = null).isEmpty())
        assertEquals(1, a.all().size)
        assertTrue(a.all().single().agreed)
    }

    @Test
    fun `radios count before any agreement and unknown versions are ignored`() {
        val sw = a.onFrame(idB, WIFI_DIRECT, direct = true, ctl = LinkCtl(v = 2)).single()
        assertEquals(WIFI_DIRECT, sw.to)
        assertTrue(a.ladder(idB).isEmpty())
        now += 3 * beacon
        assertNull(a.tick().single().to, "silent, no agreed fallback: no active rung")
    }
}
