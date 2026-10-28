package com.icegood.findmeinwood.transport.wifidirect

import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** getSystemService returns null on the mock -> manager=null path, as on P2P-less devices. */
private fun dummyContext(): android.content.Context =
    org.mockito.Mockito.mock(android.content.Context::class.java)

class WifiDirectTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var t: WifiDirectTransport
    /** A port nobody else uses right now (debug and release test runs may overlap). */
    private val freePort = java.net.ServerSocket(0).use { it.localPort }

    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.WIFI_DIRECT))

    @BeforeTest
    fun up() {
        t = WifiDirectTransport(dummyContext(), port = freePort)
    }

    @AfterTest
    fun down() {
        runBlocking { t.stop() }
        scope.cancel()
    }

    @Test
    fun `start without a p2p channel reports link down`() = runBlocking<Unit> {
        // getSystemService returns null on the mock: no channel, so no P2P at all
        val events = t.start(cfg)
        val first = withTimeout(5_000) { events.first() } as TransportEvent.StateChanged
        assertEquals(RadioState.LINK_DOWN, first.state)
        t.stop()
    }

    @Test
    fun `send before group fails with no p2p group`() = runBlocking<Unit> {
        assertIs<SendResult.Failed>(t.send(byteArrayOf(1)))
    }

    @Test
    fun `group owner relays a member's frame to the other members, not back to it`() = runBlocking<Unit> {
        val events = t.start(cfg)
        suspend fun connect(): Socket = Socket().also { c ->
            withTimeout(5_000) {
                while (true) {
                    try { c.connect(InetSocketAddress("127.0.0.1", freePort), 500); break } catch (_: Exception) { delay(50) }
                }
            }
        }
        val a = connect()
        val b = connect()
        withTimeout(5_000) { while (t.peerLinkCount() < 2) delay(20) }
        val wire = FrameCodec.encode(
            EncryptedFrame(NetworkId(ByteArray(8)), MemberId(ByteArray(32) { 2 }), 9u, 3u, 0L, ByteArray(16) { 3 }),
        )
        DataOutputStream(a.getOutputStream()).apply { writeInt(wire.size); write(wire); flush() }
        val got = withTimeout(5_000) {
            events.first { it is TransportEvent.FrameReceived } as TransportEvent.FrameReceived
        }
        val link = requireNotNull(got.link) { "the frame names the socket it came in on" }

        assertIs<SendResult.Sent>(t.relay(wire, except = link))
        val din = DataInputStream(b.getInputStream())
        val back = ByteArray(din.readInt()).also { din.readFully(it) }
        assertTrue(back.contentEquals(wire), "B gets A's frame")
        a.soTimeout = 300
        assertTrue(runCatching { DataInputStream(a.getInputStream()).readInt() }.isFailure, "nothing back to A")
        // A member with only the owner's socket has nobody to relay to.
        assertIs<SendResult.Failed>(
            WifiDirectTransport(dummyContext(), port = java.net.ServerSocket(0).use { it.localPort }).relay(wire, except = link),
        )
        a.close(); b.close()
    }

    @Test
    fun `tcp peer exchange over real localhost sockets`() = runBlocking<Unit> {
        val events = t.start(cfg)

        // Transport hosts the server; a member connects like a GO-side peer.
        val client = Socket()
        withTimeout(5_000) {
            while (true) {
                try {
                    client.connect(InetSocketAddress("127.0.0.1", freePort), 500)
                    break
                } catch (_: Exception) {
                    delay(50)
                }
            }
        }

        withTimeout(5_000) { events.first { it is TransportEvent.PeerRadioVisible } }
        val ready = events.first {
            (it as? TransportEvent.StateChanged)?.state == RadioState.READY
        } as TransportEvent.StateChanged
        assertEquals(RadioState.READY, ready.state)

        // Peer sends us a frame -> FrameReceived
        val wire = FrameCodec.encode(
            EncryptedFrame(
                NetworkId(ByteArray(8)), MemberId(ByteArray(32) { 2 }), 7u, 3u, 0L,
                ByteArray(16) { 3 },
            ),
        )
        val out = DataOutputStream(client.getOutputStream())
        out.writeInt(wire.size)
        out.write(wire)
        out.flush()
        val got = withTimeout(5_000) {
            events.first { it is TransportEvent.FrameReceived } as TransportEvent.FrameReceived
        }
        assertEquals(7u, got.frame.seq)

        // Our send fans out to the registered socket; peer reads len+frame.
        val sent = t.send(wire)
        assertTrue(sent is SendResult.Sent, "expected Sent, was $sent")
        val din = DataInputStream(client.getInputStream())
        val len = din.readInt()
        val back = ByteArray(len).also { din.readFully(it) }
        assertTrue(back.contentEquals(wire))

        // Abrupt peer close: send prunes dead sockets without throwing.
        out.close()
        client.close()
        repeat(3) { t.send(wire) }
        delay(200)
        val after = t.send(wire)
        assertTrue(after is SendResult.Failed || after is SendResult.Sent)
    }
}

class WifiDirectHeartbeatTest {
    private val freePort = java.net.ServerSocket(0).use { it.localPort }
    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.WIFI_DIRECT))

    @Test
    fun `heartbeats keep a socket alive, a silent one is closed`() = runBlocking<Unit> {
        val beat = WifiDirectTransport.HEARTBEAT_MS
        val silent = WifiDirectTransport.SILENT_SOCKET_MS
        WifiDirectTransport.HEARTBEAT_MS = 100
        WifiDirectTransport.SILENT_SOCKET_MS = 600
        val t = WifiDirectTransport(dummyContext(), port = freePort)
        try {
            t.start(cfg)
            val alive = Socket()
            withTimeout(5_000) {
                while (true) {
                    try { alive.connect(InetSocketAddress("127.0.0.1", freePort), 500); break } catch (_: Exception) { delay(50) }
                }
            }
            withTimeout(5_000) { while (t.peerLinkCount() < 1) delay(20) }
            // The transport beats on its own; the peer answers with empty frames too.
            val din = DataInputStream(alive.getInputStream())
            assertEquals(0, withTimeout(5_000) { din.readInt() }, "an empty frame is a heartbeat")
            val out = DataOutputStream(alive.getOutputStream())
            repeat(10) { out.writeInt(0); out.flush(); delay(100) }
            assertEquals(1, t.peerLinkCount(), "beating peer stays")
            // Gone without a FIN: nothing comes any more, the socket is dropped.
            withTimeout(5_000) { while (t.peerLinkCount() > 0) delay(50) }
            alive.close()
        } finally {
            t.stop()
            WifiDirectTransport.HEARTBEAT_MS = beat
            WifiDirectTransport.SILENT_SOCKET_MS = silent
        }
    }
}

class WifiDirectConstantsTest {
    @Test
    fun `frame cap matches protocol`() {
        assertEquals(65_507, WifiDirectTransport.MAX_FRAME)
        assertTrue(WifiDirectTransport.PORT in 1024..65535)
        // FR-3.5c: whatever dropped the link, the pair is back within 20 s.
        assertTrue(WifiDirectTransport.PENDING_TIMEOUT_MS + 5_000 <= 20_000)
        assertTrue(WifiDirectTransport.SILENT_SOCKET_MS + 5_000 <= 20_000)
    }
}
