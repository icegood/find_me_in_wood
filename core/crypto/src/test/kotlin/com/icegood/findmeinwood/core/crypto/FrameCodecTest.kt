package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import java.io.EOFException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameCodecTest {
    private val frame = EncryptedFrame(
        networkId = NetworkId(ByteArray(8) { (it + 1).toByte() }),
        senderId = MemberId(ByteArray(32) { (it * 3).toByte() }),
        seq = 41u,
        ttl = 3u,
        sentAtMs = 1_724_000_000_123,
        ciphertext = ByteArray(57) { (it * 7).toByte() },
    )

    @Test
    fun `roundtrip`() {
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame.networkId.bytes.toList(), decoded.networkId.bytes.toList())
        assertEquals(frame.senderId.bytes.toList(), decoded.senderId.bytes.toList())
        assertEquals(frame.seq, decoded.seq)
        assertEquals(frame.ttl, decoded.ttl)
        assertEquals(frame.sentAtMs, decoded.sentAtMs)
        assertContentEquals(frame.ciphertext, decoded.ciphertext)
    }

    @Test
    fun `rejects bad magic and truncation`() {
        val bytes = FrameCodec.encode(frame)
        bytes[0] = 0
        assertFailsWith<IllegalArgumentException> { FrameCodec.decode(bytes) }
        assertFailsWith<EOFException> { FrameCodec.decode(bytes.copyOf(10)) }
    }

    @Test
    fun `aad covers header minus ttl and len`() {
        val aad = FrameCodec.aad(frame.networkId, frame.senderId, frame.seq, frame.sentAtMs)
        assertEquals(FrameCodec.HEADER_LEN - 1 - 2, aad.size) // ttl + len excluded
        val ttlFlipped = frame.copy(ttl = (frame.ttl - 1u).toUByte())
        val aadRelayed = FrameCodec.aad(ttlFlipped.networkId, ttlFlipped.senderId, ttlFlipped.seq, ttlFlipped.sentAtMs)
        assertContentEquals(aad, aadRelayed) // relay (ttl-1) keeps AAD stable
        val seqFlipped = FrameCodec.aad(frame.networkId, frame.senderId, frame.seq + 1u, frame.sentAtMs)
        assertFalse(aad.contentEquals(seqFlipped))
    }
}

class ReplayWindowTest {
    private var now = 1_000_000L
    private val window = ReplayWindow { now }
    private val sender = ByteArray(32) { 1 }

    @Test
    fun `accepts increasing seq rejects old and duplicates`() {
        assertTrue(window.accept(sender, 5u, now))
        assertTrue(window.accept(sender, 6u, now))
        assertFalse(window.accept(sender, 6u, now))
        assertFalse(window.accept(sender, 3u, now))
        assertTrue(window.accept(sender, 1000u, now))
    }

    @Test
    fun `clock sanity window enforced per sender`() {
        assertTrue(window.accept(sender, 1u, now))
        assertFalse(window.accept(ByteArray(32) { 2 }, 1u, now - 11 * 60 * 1000))
        assertTrue(window.accept(ByteArray(32) { 2 }, 1u, now - 5 * 60 * 1000))
    }
}
