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

    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.WIFI_DIRECT))

    @BeforeTest
    fun up() {
        t = WifiDirectTransport(dummyContext())
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
    fun `tcp peer exchange over real localhost sockets`() = runBlocking<Unit> {
        val events = t.start(cfg)

        // Transport hosts the server; a member connects like a GO-side peer.
        val client = Socket()
        withTimeout(5_000) {
            while (true) {
                try {
                    client.connect(InetSocketAddress("127.0.0.1", WifiDirectTransport.PORT), 500)
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

class WifiDirectConstantsTest {
    @Test
    fun `frame cap matches protocol`() {
        assertEquals(65_507, WifiDirectTransport.MAX_FRAME)
        assertTrue(WifiDirectTransport.PORT in 1024..65535)
    }

    @Test
    fun `server socket port is bindable in test env`() {
        ServerSocket().use { s ->
            s.bind(InetSocketAddress(WifiDirectTransport.PORT))
        }
    }
}
