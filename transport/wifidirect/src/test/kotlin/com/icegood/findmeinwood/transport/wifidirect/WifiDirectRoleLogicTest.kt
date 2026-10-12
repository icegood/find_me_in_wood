package com.icegood.findmeinwood.transport.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
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

    private fun transport(wiring: FakeWiring, owner: Boolean = false, pollMs: Long = 10_000L) =
        WifiDirectTransport(ctx, wiring, pollMs).apply { setPreferGroupOwner(owner); made += this }

    private suspend fun firstState(t: WifiDirectTransport): RadioState? =
        (withTimeout(5_000) { t.start(cfg).first() } as? TransportEvent.StateChanged)?.state

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
        assertEquals(listOf(creds), w.joins, "joins with the network's credentials")
        assertEquals(0, w.createGroupCalls, "joins first, hosts only later")
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
    fun `without a network secret there is no group at all`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, owner = true)
        t.start(SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.WIFI_DIRECT)))
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
    fun `no host found within the join window - this phone hosts`() = runBlocking<Unit> {
        val window = WifiDirectTransport.JOIN_WINDOW_MS
        WifiDirectTransport.JOIN_WINDOW_MS = 20
        try {
            val w = FakeWiring().apply { failJoin = true }
            val t = transport(w, pollMs = 30L)
            t.start(cfg)
            delay(200)
            assertTrue(w.joins.size >= 1)
            assertTrue(w.createGroupCalls >= 1, "hosts after the join window")
            t.stop()
        } finally {
            WifiDirectTransport.JOIN_WINDOW_MS = window
        }
    }

    @Test
    fun `a host nobody joins gives the group up and tries joining again`() = runBlocking<Unit> {
        val idle = WifiDirectTransport.HOST_IDLE_MS
        WifiDirectTransport.HOST_IDLE_MS = 20
        try {
            val w = FakeWiring()
            val t = transport(w, pollMs = 30L)
            t.start(cfg)
            w.answerInfo(formed = true, owner = true, host = "192.168.49.1")
            delay(250)
            assertTrue(w.removeGroupCalls >= 1, "idle host must yield")
            assertTrue(w.joins.size >= 2, "and try joining again")
            t.stop()
        } finally {
            WifiDirectTransport.HOST_IDLE_MS = idle
        }
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
        // Keep the phone in its join phase for the whole test (no switch to hosting).
        val window = WifiDirectTransport.JOIN_WINDOW_MS
        WifiDirectTransport.JOIN_WINDOW_MS = 3_600_000L
        try {
            val t = transport(w, pollMs = 30L)
            t.start(cfg)
            delay(100)
            assertEquals(1, w.joins.size, "a pending join must not be spammed")
            delay(WifiDirectTransport.PENDING_TIMEOUT_MS + 100)
            assertTrue(w.joins.size >= 2, "an unanswered join must expire and retry")
            t.stop()
        } finally {
            WifiDirectTransport.JOIN_WINDOW_MS = window
        }
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
