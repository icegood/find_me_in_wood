package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH
import com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT
import com.icegood.findmeinwood.transport.api.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End to end, two complete session stacks ("phones" A and B) with a Bluetooth and a Wi-Fi
 * Direct radio each, exchanging real signed + encrypted beacons:
 *
 * 1. Handshake: both users confirm Bluetooth + Wi-Fi Direct; the link starts on Bluetooth.
 * 2. The phones walk apart: Bluetooth (~100 m) stops delivering, Wi-Fi Direct (~200 m)
 *    still does. Nothing switches by itself; both phones propose Wi-Fi Direct.
 * 3. The user on A takes the proposal: A switches at once, B follows from A's next beacon
 *    — no new handshake.
 * 4. They come back into Bluetooth range; B's user switches back to Bluetooth the same way.
 */
class LinkE2ETest {
    private var now = 1_000_000L
    private val beaconMs = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = NetworkKeysFactory.derive("e2e-link-test-secret".toByteArray())

    /** Bluetooth only reaches while the phones are close; Wi-Fi Direct always does here. */
    @Volatile private var btInRange = true

    private inner class Phone(name: String) {
        val pair = Identity.generate()
        val id = MemberId(Identity.memberIdOf(pair))
        val bt = FakeTransport(BLUETOOTH)
        val wd = FakeTransport(WIFI_DIRECT)
        val links = LinkNegotiator(id, { now }, beaconIntervalMs = beaconMs).apply {
            setOffers(listOf(LinkOffer(BLUETOOTH), LinkOffer(WIFI_DIRECT)))
            setName(name)
        }
        val switches = CopyOnWriteArrayList<LinkNegotiator.Switch>()
        val session = SessionManager(
            NetworkProfile(name, keys.networkId, keys.trafficKey, id, id, JoinPolicy.PRIVATE),
            listOf(bt, wd), flowOf(), { now },
            // Beacons are sent by the test, one round at a time: no background cadence.
            scope = scope, keepAliveMs = 3_600_000L, signer = pair, links = links,
        ).also { s -> s.onLinkSwitch = { switches += it } }

        fun peer(other: Phone) = session.peerFlow.value[other.id]
    }

    private val a = Phone("A")
    private val b = Phone("B")

    private fun wire(from: FakeTransport, to: FakeTransport, gate: () -> Boolean) {
        scope.launch { from.sentWire.collect { w -> if (gate()) to.deliver(FrameCodec.decode(w)) } }
    }

    init {
        wire(a.bt, b.bt) { btInRange }
        wire(b.bt, a.bt) { btInRange }
        wire(a.wd, b.wd) { true }
        wire(b.wd, a.wd) { true }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        a.session.stop(); b.session.stop(); scope.cancel()
    }

    /** One beacon each way, one beacon interval later; waits until both were received. */
    private suspend fun round() {
        now += beaconMs
        val rxA = a.session.diagnostics.value.received
        val rxB = b.session.diagnostics.value.received
        a.session.sendBeaconSigned(BeaconPayload())
        b.session.sendBeaconSigned(BeaconPayload())
        eventually { a.session.diagnostics.value.received > rxA && b.session.diagnostics.value.received > rxB }
        delay(50) // let the duplicate copy over the second radio land too
    }

    private suspend fun eventually(cond: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < deadline) { "timed out" }
            delay(10)
        }
    }

    @Test
    fun `handshake both radios, propose the other one when apart, switch without a new handshake`() = runBlocking {
        a.session.start(); b.session.start()

        // 1. Handshake: offers exchanged, then each user confirms on their own phone.
        round()
        assertNull(a.peer(b)?.link, "nothing agreed before both users confirm")
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), a.peer(b)?.linkCommon)
        a.session.confirmLinks(b.id)
        round()
        assertNull(b.peer(a)?.link, "A's confirmation alone is not enough")
        assertEquals(true, b.peer(a)?.linkConfirmedByPeer)
        b.session.confirmLinks(a.id)
        round()
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), a.peer(b)?.linkOptions)
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), b.peer(a)?.linkOptions)
        assertEquals(BLUETOOTH, a.peer(b)?.link)
        assertEquals(BLUETOOTH, b.peer(a)?.link)
        assertEquals("B", a.peer(b)?.nickname)
        assertNull(a.peer(b)?.linkSuggestion)

        // 2. Walk apart: Bluetooth stops reaching, Wi-Fi Direct still does.
        btInRange = false
        repeat(2) { round() }
        assertEquals(BLUETOOTH, a.peer(b)?.link, "nothing switches by itself")
        assertEquals(BLUETOOTH, b.peer(a)?.link)
        assertEquals(WIFI_DIRECT, a.peer(b)?.linkSuggestion, "A proposes the other agreed radio")
        assertEquals(WIFI_DIRECT, b.peer(a)?.linkSuggestion, "B proposes it too")

        // 3. A's user takes the proposal: instant on A, B follows from A's next beacon.
        a.session.selectLink(b.id, WIFI_DIRECT)
        assertEquals(WIFI_DIRECT, a.peer(b)?.link)
        round()
        assertEquals(WIFI_DIRECT, b.peer(a)?.link)
        assertEquals(WIFI_DIRECT, b.switches.last().to)
        assertNull(a.peer(b)?.linkSuggestion, "Wi-Fi Direct is heard: nothing to propose")
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), b.peer(a)?.linkOptions, "same agreed set: no new handshake")

        // 4. Back in Bluetooth range; B's user switches back, A follows.
        btInRange = true
        round()
        assertEquals(WIFI_DIRECT, a.peer(b)?.link, "coming back does not switch by itself either")
        b.session.selectLink(a.id, BLUETOOTH)
        round()
        assertEquals(BLUETOOTH, a.peer(b)?.link)
        assertEquals(BLUETOOTH, b.peer(a)?.link)
        assertEquals(BLUETOOTH, a.switches.last().to)
    }

    @Test
    fun `handshake starts when the radio link comes up and every step is answered at once`() = runBlocking {
        a.session.start(); b.session.start()
        // No scheduled beacon at all: the Bluetooth link coming up on A starts it, and
        // everything after that is an immediate reply.
        val t0 = System.nanoTime()
        a.bt.linkUp()
        eventually { a.peer(b)?.linkCommon?.isNotEmpty() == true && b.peer(a)?.linkCommon?.isNotEmpty() == true }
        // Both users confirm; each confirmation goes out at once.
        a.session.confirmLinks(b.id)
        b.session.confirmLinks(a.id)
        eventually { a.peer(b)?.link == BLUETOOTH && b.peer(a)?.link == BLUETOOTH }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        // The test clock never moved: no scheduled beacon was involved, so the handshake
        // takes a few round trips after the link comes up, whatever the beacon interval.
        assertEquals(listOf(BLUETOOTH, WIFI_DIRECT), b.peer(a)?.linkOptions)
        kotlin.test.assertTrue(tookMs < 3_000, "took $tookMs ms")
        kotlin.test.assertTrue(a.session.diagnostics.value.sent <= 3, "A sent ${a.session.diagnostics.value.sent}")
    }
}
