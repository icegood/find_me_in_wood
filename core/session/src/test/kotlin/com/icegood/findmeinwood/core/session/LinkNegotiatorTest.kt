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
    fun `decisions survive a restart and reconnect the pair without asking again`() {
        var saved = 0
        a.onDecided = { saved++ }
        b.onDecided = { saved++ }
        handshake()
        assertTrue(saved > 0, "every decision is handed out to be kept")
        val keptA = a.decisions()
        val keptB = b.decisions()
        assertTrue(keptA.getValue(idB).values.all { it })
        // Both apps restart: fresh negotiators with what was kept.
        val a2 = LinkNegotiator(idA, { now }, beacon, sms).apply { restore(keptA) }
        val b2 = LinkNegotiator(idB, { now }, beacon, sms).apply { restore(keptB) }
        assertEquals(keptA, a2.decisions(), "kept before the peer is heard again")
        assertEquals(idA, a2.wifiDirectHostOf(idB), "the kept Wi-Fi Direct decision sets the host before anyone is heard")
        assertEquals(idA, b2.wifiDirectHostOf(idA))
        // A kept No: no pair group with that member, even before it is heard again.
        val kept = LinkNegotiator(idA, { now }, beacon, sms).apply {
            setOffers(listOf(LinkOffer(WIFI_DIRECT)))
            restore(mapOf(idB to mapOf("WIFI_DIRECT::" to false)))
            setMembers(listOf(idA, idB))
        }
        assertEquals(emptyList(), kept.wifiDirectGroups())
        a2.setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT), LinkOffer(SMS, "+380001")))
        b2.setOffers(listOf(LinkOffer(SMS, "+380002"), LinkOffer(WIFI_DIRECT), LinkOffer(BLUETOOTH)))
        repeat(3) {
            b2.onFrame(idA, BLUETOOTH, true, a2.outgoing())
            a2.onFrame(idB, BLUETOOTH, true, b2.outgoing())
        }
        assertEquals(BLUETOOTH, a2.link(idB), "agreed again with nobody tapping")
        assertTrue(a2.agreed(idB))
        assertEquals(idA, a2.wifiDirectHostOf(idB))
        assertEquals(idA, b2.wifiDirectHostOf(idA))
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
    fun `each pair decides its own Wi-Fi Direct host, also while out of touch`() {
        assertNull(a.wifiDirectHostOf(idB))
        assertEquals(emptyList(), a.wifiDirectGroups(), "Wi-Fi off (not offered): no group")
        handshake()
        assertEquals(idA, a.wifiDirectHostOf(idB), "lower id hosts")
        assertEquals(idA, b.wifiDirectHostOf(idA))
        assertEquals(listOf(LinkNegotiator.WifiDirectGroup(idA, listOf(idB))), a.wifiDirectGroups())
        assertEquals(listOf(LinkNegotiator.WifiDirectGroup(idA, listOf(idA))), b.wifiDirectGroups())
        now += beacon * 4
        assertEquals(idA, a.wifiDirectHostOf(idB), "out of touch: the pair keeps its host")
        exchange()
        b.setOffers(listOf(LinkOffer(BLUETOOTH))) // B switched Wi-Fi off
        repeat(2) { exchange() }
        assertNull(a.wifiDirectHostOf(idB), "Wi-Fi Direct no longer agreed")
    }

    @Test
    fun `either of the pair may swap the host, the newer pick wins`() {
        handshake()
        b.pickHost(idA, meHosts = true)
        repeat(2) { exchange() }
        assertEquals(idB, a.wifiDirectHostOf(idB), "A follows B's pick")
        assertEquals(idB, b.wifiDirectHostOf(idA))
        a.pickHost(idA, meHosts = true) // not a peer: ignored
        a.pickHost(idB, meHosts = true)
        repeat(2) { exchange() }
        assertEquals(idA, b.wifiDirectHostOf(idA), "the newer pick wins")
        repeat(4) { exchange() } // picks are announced a few times only
        assertEquals(idA, a.wifiDirectHostOf(idB))
    }

    @Test
    fun `a phone serves its hosted group and one per host it joins, a pair group for every member it can pair with`() {
        val idC = MemberId(ByteArray(32) { 0 }) // lowest of all
        val idD = MemberId(ByteArray(32) { 9 })
        val c = LinkNegotiator(idC, { now }, beacon, sms)
        val d = LinkNegotiator(idD, { now }, beacon, sms)
        val wd = listOf(LinkOffer(WIFI_DIRECT))
        listOf(a, b, c, d).forEach { it.setOffers(wd) }
        fun ctl(n: LinkNegotiator) = n.run { announceNow(); outgoing() }
        fun meet(x: LinkNegotiator, xi: MemberId, y: LinkNegotiator, yi: MemberId) {
            y.onFrame(xi, WIFI_DIRECT, true, ctl(x))
            x.onFrame(yi, WIFI_DIRECT, true, ctl(y))
            x.allow(yi, WIFI_DIRECT, true); y.allow(xi, WIFI_DIRECT, true)
            repeat(3) {
                y.onFrame(xi, WIFI_DIRECT, true, ctl(x))
                x.onFrame(yi, WIFI_DIRECT, true, ctl(y))
            }
        }
        meet(a, idA, b, idB) // A hosts B
        meet(c, idC, a, idA) // C hosts A
        meet(a, idA, d, idD) // A hosts D
        assertEquals(
            listOf(LinkNegotiator.WifiDirectGroup(idA, listOf(idB, idD)), LinkNegotiator.WifiDirectGroup(idC, listOf(idC))),
            a.wifiDirectGroups(),
            "A hosts one group for B and D, and joins C's in turn",
        )
        // A member heard but undecided meets A in their pair's group, where the handshake runs.
        val idE = MemberId(ByteArray(32) { 5 })
        a.onFrame(idE, WIFI_DIRECT, true, LinkCtl(offers = wd))
        assertEquals(LinkNegotiator.WifiDirectGroup(idA, listOf(idB, idE, idD)), a.wifiDirectGroups().first())
        // Every member of the network can pair: one never heard gets its pair group too.
        val idF = MemberId(ByteArray(32) { 0x0f })
        a.setMembers(listOf(idA, idB, idF))
        assertTrue(idF in a.wifiDirectGroups().first().members)
        // A member that does not offer Wi-Fi Direct has no group with A.
        a.onFrame(idF, BLUETOOTH, true, LinkCtl(offers = listOf(LinkOffer(BLUETOOTH))))
        assertFalse(idF in a.wifiDirectGroups().first().members)
        // E turns Wi-Fi Direct down: refused, no common group needed for it any more.
        val e = LinkNegotiator(idE, { now }, beacon, sms).apply { setOffers(wd) }
        e.onFrame(idA, WIFI_DIRECT, true, LinkCtl(offers = wd))
        e.allow(idA, WIFI_DIRECT, false)
        a.onFrame(idE, WIFI_DIRECT, true, e.outgoing())
        assertTrue(a.wifiDirectRefused(idE))
        assertFalse(a.wifiDirectRefused(idB))
        assertFalse(a.wifiDirectRefused(MemberId(ByteArray(32) { 7 })), "unknown member")
        assertEquals(
            listOf(LinkNegotiator.WifiDirectGroup(idA, listOf(idB, idD)), LinkNegotiator.WifiDirectGroup(idC, listOf(idC))),
            a.wifiDirectGroups(),
            "the member that turned Wi-Fi Direct down has no group with A",
        )
        a.allow(idB, WIFI_DIRECT, false)
        assertTrue(a.wifiDirectRefused(idB), "this user's No counts too")
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

class WifiDirectSecretTest {
    @Test
    fun `group credentials differ per host and per hour, and every member derives the same`() {
        val key = ByteArray(32) { 1 }
        val a = MemberId(ByteArray(32) { 1 })
        val b = MemberId(ByteArray(32) { 2 })
        val s = SessionManager.wifiDirectSecret(key, a, 10)
        assertTrue(s.contentEquals(SessionManager.wifiDirectSecret(key, a, 10)), "the same on every phone")
        assertFalse(s.contentEquals(SessionManager.wifiDirectSecret(key, b, 10)), "a different group per host")
        assertFalse(s.contentEquals(SessionManager.wifiDirectSecret(key, a, 11)), "a new name every hour")
        assertFalse(s.contentEquals(SessionManager.wifiDirectSecret(ByteArray(32) { 2 }, a, 10)), "only members can derive it")
        assertEquals(32, s.size)
    }
}
