package com.icegood.findmeinwood.transport.bluetooth

import com.icegood.findmeinwood.core.crypto.FrameCodec
import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Mocked platform border: unstubbed getSystemService returns null, like BT-less devices. */
private fun dummyContext(): android.content.Context =
    org.mockito.Mockito.mock(android.content.Context::class.java)

/**
 * Plain-JVM coverage of transport lifecycle + send decision paths. GATT server/
 * client callback internals are hardware-border surface (excluded from gate).
 */
class BluetoothTransportPlainTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cfg = SessionConfig(NetworkId(ByteArray(8)), setOf(TransportId.BLUETOOTH))

    @Test
    fun `start without radio emits link down and never throws`() = runBlocking<Unit> {
        val t = BluetoothTransport(dummyContext())
        val ev = withTimeout(5_000) {
            t.start(cfg).first { it is TransportEvent.StateChanged }
        } as TransportEvent.StateChanged
        assertEquals(RadioState.LINK_DOWN, ev.state)
        t.stop()
        scope.cancel()
    }

    @Test
    fun `send when not running fails`() = runBlocking<Unit> {
        assertIs<SendResult.Failed>(BluetoothTransport(dummyContext()).send(byteArrayOf(1)))
    }

    @Test
    fun `send with no peers fails after start`() = runBlocking<Unit> {
        val t = BluetoothTransport(dummyContext())
        t.start(cfg)
        delay(50)
        assertIs<SendResult.Failed>(t.send(byteArrayOf(1, 2)))
        t.stop()
        scope.cancel()
    }

    @Test
    fun `stop is idempotent`() = runBlocking<Unit> {
        val t = BluetoothTransport(dummyContext())
        t.start(cfg)
        delay(20)
        t.stop()
        t.stop()
    }
}

class FrameFramerEdgeTest {
    private fun wire(seq: Int) = FrameCodec.encode(
        EncryptedFrame(
            NetworkId(ByteArray(8)), MemberId(ByteArray(32) { seq.toByte() }), seq.toUInt(),
            3u, 0L, ByteArray(10) { seq.toByte() },
        ),
    )

    @Test
    fun `rejects chunk size too small for length prefix`() {
        assertFailsWith<IllegalArgumentException> { FrameFramer.frameToChunks(byteArrayOf(1), 2) }
    }

    @Test
    fun `single byte offers after header reassemble`() {
        val w = wire(1)
        val r = FrameFramer.Reassembler()
        var out: ByteArray? = null
        // length-prefixed stream: header first (2 bytes), then payload one byte at a time
        val stream = ByteArray(2 + w.size)
        stream[0] = ((w.size ushr 8) and 0xFF).toByte()
        stream[1] = (w.size and 0xFF).toByte()
        w.copyInto(stream, 2)
        out = r.offer(stream.copyOf(2))
        assertNull(out)
        for (i in 2 until stream.size) {
            out = r.offer(byteArrayOf(stream[i])) ?: out
        }
        assertEquals(w.toList(), out!!.toList())
    }

    @Test
    fun `empty offer before header yields null`() {
        assertNull(FrameFramer.Reassembler().offer(ByteArray(0)))
        assertNull(FrameFramer.Reassembler().offer(byteArrayOf(5)))
    }

    @Test
    fun `oversized declared length is not delivered early`() {
        val r = FrameFramer.Reassembler()
        // declare 0xFFFF but send far less
        assertNull(r.offer(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)))
    }

    @Test
    fun `roundtrip through chunker matches wire`() {
        val w = wire(2)
        val r = FrameFramer.Reassembler()
        var out: ByteArray? = null
        FrameFramer.frameToChunks(w, 7).forEach { out = r.offer(it) ?: out }
        assertTrue(out!!.contentEquals(w))
    }
}
