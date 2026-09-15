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
    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.WIFI_DIRECT))
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
    fun `start scans, discovers and asks for group state`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w)
        assertEquals(RadioState.SCANNING, firstState(t))
        assertTrue(w.discoverCalls >= 1, "must discover peers")
        assertTrue(w.peerCallbacks.isNotEmpty(), "must ask for peers")
        assertTrue(w.infoCallbacks.isNotEmpty(), "must ask for group info")
        t.stop()
        assertEquals(1, w.removeGroupCalls)
    }

    @Test
    fun `host creates the group and never dials out`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, owner = true)
        t.start(cfg)
        assertTrue(w.createGroupCalls >= 1, "host must create the group")
        w.answerPeers(listOf("AA:BB:CC:DD:EE:01"))
        assertTrue(w.connectAddresses.isEmpty(), "the host waits for peers to join")
        t.stop()
    }

    @Test
    fun `joining peer dials with an owner intent`() = runBlocking<Unit> {
        val w = FakeWiring()
        val t = transport(w, owner = false)
        t.start(cfg)
        w.answerPeers(listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"))
        assertEquals(listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"), w.connectAddresses)
        w.connectIntents.forEach { assertTrue(it in 0..15, "owner intent out of range") }
        t.stop()
    }

    @Test
    fun `failed connect frees the address for a retry`() = runBlocking<Unit> {
        val w = FakeWiring().apply { failConnect = true }
        val t = transport(w)
        t.start(cfg)
        w.answerPeers(listOf("AA:BB:CC:DD:EE:04"))
        w.answerPeers(listOf("AA:BB:CC:DD:EE:04"))
        assertEquals(2, w.connectAddresses.size, "a failed connect must not stick")
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
    fun `a connect that is never answered is retried`() = runBlocking<Unit> {
        val w = object : FakeWiring() {
            override fun connect(
                channel: WifiP2pManager.Channel?,
                address: String,
                ownerIntent: Int,
                done: (P2pResult) -> Unit,
            ) {
                connectAddresses += address // no callback: peer must accept first
            }
        }
        val t = transport(w, pollMs = 30L)
        t.start(cfg)
        w.answerPeers(listOf("AA:BB:CC:DD:EE:09"))
        delay(80)
        w.answerPeers(listOf("AA:BB:CC:DD:EE:09"))
        assertEquals(1, w.connectAddresses.size, "pending connect must not be spammed")
        WifiDirectTransport.PENDING_TIMEOUT_MS.let { timeout ->
            // the pending attempt expires, then the peer is dialed again
            runBlocking { delay(timeout + 60) }
        }
        w.answerPeers(listOf("AA:BB:CC:DD:EE:09"))
        assertEquals(2, w.connectAddresses.size, "unanswered connect must expire and retry")
        t.stop()
    }

    @Test
    fun `host keeps trying to create the group until it forms`() = runBlocking<Unit> {
        val w = object : FakeWiring() {
            override fun createGroup(channel: WifiP2pManager.Channel?, done: (P2pResult) -> Unit) {
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
