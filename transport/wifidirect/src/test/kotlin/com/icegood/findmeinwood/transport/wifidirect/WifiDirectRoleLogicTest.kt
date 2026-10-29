package com.icegood.findmeinwood.transport.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.GroupPlan
import com.icegood.findmeinwood.transport.api.GroupRole
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.Mockito

/**
 * Group formation, roles and readiness (T4.1) as plain JVM: the platform is the
 * [FakeWiring] seam, so the decisions that OEM ROMs get wrong are asserted here.
 */
class WifiDirectRoleLogicTest {
    private val ctx: Context = Mockito.mock(Context::class.java)
    private val secret = ByteArray(32) { it.toByte() }
    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.WIFI_DIRECT), groupSecret = secret)
    private val creds = GroupCredentials.from(secret)
    private val made = mutableListOf<WifiDirectTransport>()

    /** [role]: the pair group it serves (the network's secret), null = none yet. */
    private fun transport(wiring: FakeWiring, owner: Boolean = false, pollMs: Long = 10_000L, role: GroupRole? = GroupRole.JOIN) =
        WifiDirectTransport(ctx, wiring, pollMs).apply {
            setPreferGroupOwner(owner)
            role?.let { setGroups(listOf(GroupPlan(secret, it))) }
            made += this
        }

    private fun lastState(events: kotlinx.coroutines.flow.Flow<TransportEvent>): RadioState? =
        (events as kotlinx.coroutines.flow.SharedFlow<TransportEvent>).replayCache
            .filterIsInstance<TransportEvent.StateChanged>().lastOrNull()?.state

    private suspend fun firstState(t: WifiDirectTransport): RadioState? =
        (withTimeout(5_000) { t.start(cfg).first() } as? TransportEvent.StateChanged)?.state

    @Test
    fun `a negotiation that forms no group is aborted and asked again`() = runBlocking<Unit> {
        val limit = WifiDirectTransport.CREATE_TIMEOUT_MS
        WifiDirectTransport.CREATE_TIMEOUT_MS = 50
        try {
            val w = FakeWiring()
            val t = transport(w, pollMs = 20)
            t.setGroups(listOf(GroupPlan(secret, GroupRole.HOST)))
            t.start(cfg)
            withTimeout(5_000) { while (w.cancelCalls == 0) delay(10) }
            val before = w.createGroupCalls
            withTimeout(5_000) { while (w.createGroupCalls == before) delay(10) }
            w.answerInfo(formed = true, owner = true, host = null)
            delay(100)
            val cancels = w.cancelCalls
            delay(200)
            assertEquals(cancels, w.cancelCalls, "a formed group is never aborted")
            t.stop()
        } finally {
            WifiDirectTransport.CREATE_TIMEOUT_MS = limit
        }
    }

    @Test
    fun `host role is a preference reported to the app`() = runBlocking<Unit> {
        val t = transport(FakeWiring())
        assertTrue(!t.isGroupOwner())
        t.setPreferGroupOwner(true)
        assertTrue(t.isGroupOwner())
        t.stop()
    }

    @Test
    fun `send without a formed group fails`() = runBlocking<Unit> {
        val t = transport(FakeWiring())
        assertIs<SendResult.Failed>(t.send(byteArrayOf(1)))
    }

    @Test
    fun `a device without p2p reports link down`() = runBlocking<Unit> {
        val w = object : FakeWiring() {
            override fun initialize(): WifiP2pManager.Channel? = throw RuntimeException("no p2p")
        }
        val t = transport(w)
        assertEquals(RadioState.LINK_DOWN, firstState(t))
        t.stop()
    }

    @Test
    fun `start scans, asks for group state and joins the network's group - never dials devices`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w)
        assertEquals(RadioState.SCANNING, firstState(t))
        assertTrue(w.discoverCalls >= 1, "must discover (and be discoverable)")
        assertTrue(w.infoCallbacks.isNotEmpty(), "must ask for group info")
        assertEquals(listOf(creds), w.joins, "joins its pair's group with its credentials")
        assertEquals(0, w.createGroupCalls, "a joining phone never hosts")
        t.stop()
        assertEquals(1, w.removeGroupCalls)
    }

    @Test
    fun `credentials come from the network secret and fit Android's rules`() {
        assertEquals(creds, GroupCredentials.from(secret.copyOf()))
        assertTrue(creds.networkName.startsWith("DIRECT-fm-"))
        assertTrue(creds.passphrase.length in 8..63)
        assertTrue(GroupCredentials.from(ByteArray(32) { 9 }) != creds, "another network, other credentials")
    }

    @Test
    fun `without a pair group to serve there is no group at all`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, owner = true, role = null)
        t.start(cfg)
        assertTrue(w.joins.isEmpty() && w.createGroupCalls == 0)
        t.stop()
    }

    @Test
    fun `host preference creates the group with the credentials and never joins`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, owner = true)
        t.start(cfg)
        assertEquals(listOf(creds), w.created)
        assertTrue(w.joins.isEmpty())
        t.stop()
    }

    @Test
    fun `several groups are served in turn, an urgent one at once`() = runBlocking<Unit> {
        val slot = WifiDirectTransport.SLOT_MS
        WifiDirectTransport.SLOT_MS = 100
        val other = ByteArray(32) { (it + 7).toByte() }
        try {
            val w = FakeWiring()
            val t = transport(w, pollMs = 20L)
            t.start(cfg)
            t.setGroups(listOf(GroupPlan(secret, GroupRole.HOST), GroupPlan(other, GroupRole.JOIN)))
            assertEquals(creds, w.created.last(), "hosts its own group first")
            // Nobody joined within the slot: on to the group it joins.
            withTimeout(5_000) { while (w.joins.none { it == GroupCredentials.from(other) }) delay(10) }
            // ...and back to its own one after that slot.
            val hosted = w.created.size
            withTimeout(5_000) { while (w.created.size == hosted) delay(10) }
            // Urgent traffic for the joined group's pair: there at once.
            val joins = w.joins.size
            t.serveNow(1)
            assertTrue(w.joins.size > joins)
            t.serveNow(1)
            t.serveNow(5) // out of range: ignored
            // A plan that still holds the group served now keeps it.
            val removes = w.removeGroupCalls
            t.setGroups(listOf(GroupPlan(other, GroupRole.JOIN)))
            assertEquals(removes, w.removeGroupCalls, "the group served now carries on")
            t.stop()
        } finally {
            WifiDirectTransport.SLOT_MS = slot
        }
    }

    @Test
    fun `a slot ends soon after a member linked in it`() = runBlocking<Unit> {
        val dwell = WifiDirectTransport.LINKED_DWELL_MS
        WifiDirectTransport.LINKED_DWELL_MS = 50
        val other = ByteArray(32) { (it + 7).toByte() }
        val server = ServerSocket(0)
        try {
            val w = object : FakeWiring() {
                override fun bind(port: Int): ServerSocket? = null
            }
            w.socketToReturn = Socket("127.0.0.1", server.localPort)
            val t = transport(w, pollMs = 20L)
            t.start(cfg)
            t.setGroups(listOf(GroupPlan(secret, GroupRole.JOIN), GroupPlan(other, GroupRole.JOIN)))
            w.answerInfo(formed = true, owner = false, host = "127.0.0.1")
            withTimeout(5_000) { while (t.peerLinkCount() == 0) delay(10) }
            withTimeout(5_000) { while (w.joins.none { it == GroupCredentials.from(other) }) delay(10) }
            assertEquals(0, t.peerLinkCount(), "leaving a group closes its links")
            t.stop()
        } finally {
            server.close()
            WifiDirectTransport.LINKED_DWELL_MS = dwell
        }
    }

    @Test
    fun `agreed host creates the group at once and never gives it up`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, pollMs = 30L, role = GroupRole.HOST)
        t.start(cfg)
        assertEquals(listOf(creds), w.created, "hosts at once")
        assertTrue(w.joins.isEmpty())
        w.answerInfo(formed = true, owner = true, host = "192.168.49.1")
        delay(250)
        assertEquals(0, w.removeGroupCalls, "the agreed host waits for its members")
        t.stop()
    }

    @Test
    fun `a member told to join gives up a group it hosts alone at once and never hosts`() = runBlocking<Unit> {
        val w = FakeWiring().apply { failJoin = true }
        val t = transport(w, pollMs = 30L, role = GroupRole.HOST)
        t.start(cfg)
        w.answerInfo(formed = true, owner = true, host = "192.168.49.1") // hosting by the old pick
        val created = w.createGroupCalls
        t.setGroups(listOf(GroupPlan(secret, GroupRole.JOIN))) // the pair swapped the host
        assertEquals(1, w.removeGroupCalls, "a lone host that should join gives up at once")
        delay(200)
        assertEquals(created, w.createGroupCalls, "never hosts again")
        assertTrue(w.joins.size >= 2)
        t.stop()
    }

    @Test
    fun `wifi switched off outside the app - radio off, no requests until it is back on`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, pollMs = 30L)
        val events = t.start(cfg)
        w.answerInfo(formed = true, owner = false, host = "192.168.49.1")
        withTimeout(5_000) { events.first { (it as? TransportEvent.StateChanged)?.state == RadioState.READY } }
        t.onP2pState(false)
        assertEquals(RadioState.LINK_DOWN, lastState(events))
        val joins = w.joins.size
        val discovers = w.discoverCalls
        delay(200)
        assertEquals(joins, w.joins.size, "no joins while P2P is off")
        assertEquals(discovers, w.discoverCalls)
        assertEquals(0, w.createGroupCalls)
        t.onP2pState(true)
        assertEquals(RadioState.SCANNING, lastState(events))
        assertTrue(w.joins.size > joins, "looks for the group again at once")
        assertTrue(w.discoverCalls > discovers)
        t.stop()
    }

    @Test
    fun `formed group turns ready and a lost group goes link down`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, owner = true)
        val events = t.start(cfg)
        w.answerInfo(formed = true, owner = true, host = "192.168.49.1")
        val ready = withTimeout(5_000) {
            events.first { (it as? TransportEvent.StateChanged)?.state == RadioState.READY }
        } as TransportEvent.StateChanged
        assertEquals(RadioState.READY, ready.state)
        // owner without a peer yet: nothing to write to
        assertIs<SendResult.Failed>(t.send(byteArrayOf(1)))

        w.answerInfo(formed = false, owner = false, host = null)
        val down = withTimeout(5_000) {
            events.first { (it as? TransportEvent.StateChanged)?.state == RadioState.LINK_DOWN }
        } as TransportEvent.StateChanged
        assertEquals(RadioState.LINK_DOWN, down.state)
        t.stop()
    }

    @Test
    fun `client dials the owner once the group is up`() = runBlocking<Unit> {
        val w = FakeWiring()
        val server = ServerSocket().apply { bind(InetSocketAddress(0)) }
        val port = server.localPort
        val t = transport(w)
        t.start(cfg)
        // answer with the owner address; the wiring returns a socket that is really
        // connected to our own accept loop
        w.socketToReturn = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 1000) }
        w.answerInfo(formed = true, owner = false, host = "127.0.0.1")
        val peer = withTimeout(5_000) {
            t.start(cfg).first { it is TransportEvent.PeerRadioVisible }
        } as TransportEvent.PeerRadioVisible
        assertTrue(peer.radioPeerId.isNotEmpty())
        assertIs<SendResult.Sent>(t.send(byteArrayOf(1, 2, 3)))
        server.close()
        t.stop()
    }

    @Test
    fun `a p2p stack that never answers is reported as link down`() = runBlocking<Unit> {
        val w = object : FakeWiring() {
            override fun discoverPeers(
                channel: WifiP2pManager.Channel?,
                done: (P2pResult) -> Unit,
            ) {
                discoverCalls++ // silently ignored, like a disabled P2P stack
            }
        }
        val t = transport(w, pollMs = 30L)
        t.start(cfg)
        val state = withTimeout(5_000) {
            t.start(cfg).first { (it as? TransportEvent.StateChanged)?.state == RadioState.LINK_DOWN }
        } as TransportEvent.StateChanged
        assertEquals(RadioState.LINK_DOWN, state.state)
        t.stop()
    }

    @Test
    fun `a join that is never answered is retried after it expires`() = runBlocking<Unit> {
        val w = object : FakeWiring() {
            override fun join(channel: WifiP2pManager.Channel?, creds: GroupCredentials, done: (P2pResult) -> Unit) {
                joins += creds // no callback: the framework never answers
            }
        }
        val t = transport(w, pollMs = 30L)
        t.start(cfg)
        delay(100)
        assertEquals(1, w.joins.size, "a pending join must not be spammed")
        delay(WifiDirectTransport.FORM_TIMEOUT_MS + 200)
        assertTrue(w.joins.size >= 2, "an unanswered join must be given up and asked again")
        t.stop()
    }

    @Test
    fun `host keeps trying to create the group until it forms`() = runBlocking<Unit> {
        val w = object : FakeWiring() {
            override fun createGroup(channel: WifiP2pManager.Channel?, creds: GroupCredentials, done: (P2pResult) -> Unit) {
                createGroupCalls++
                done(P2pResult.FAILED) // refused: the next poll must retry
            }
        }
        val t = transport(w, owner = true, pollMs = 30L)
        t.start(cfg)
        delay(200)
        assertTrue(w.createGroupCalls >= 2, "group creation must be retried")
        t.stop()
    }

    @Test
    fun `poll keeps asking for group state while no group exists`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, pollMs = 30L)
        t.start(cfg)
        delay(200)
        assertTrue(w.infoCallbacks.size >= 2, "group state must be polled")
        assertEquals(1, w.discoverCalls, "a discovery window is ~120s: do not re-arm it")
        t.stop()
    }

    @AfterTest
    fun tearDown() {
        made.forEach { runBlocking { it.stop() } }
        made.clear()
    }
}
