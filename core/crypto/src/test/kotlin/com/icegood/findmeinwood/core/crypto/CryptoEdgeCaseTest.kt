package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import java.io.EOFException
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CryptoEdgeCaseTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val net = NetworkId(ByteArray(8) { 9 })
    private val sender = MemberId(ByteArray(32) { 4 })

    @Test
    fun `aead rejects too short ciphertext`() {
        assertFailsWith<IllegalArgumentException> {
            AesGcmAead.open(key, ByteArray(8), ByteArray(0))
        }
    }

    @Test
    fun `codec rejects unsupported version`() {
        val frame = EncryptedFrame(net, sender, 0u, 3u, 0L, ByteArray(4))
        val wire = FrameCodec.encode(frame)
        wire[2] = (FrameCodec.VERSION + 1).toByte()
        assertFailsWith<IllegalArgumentException> { FrameCodec.decode(wire) }
    }

    @Test
    fun `codec rejects truncated payload`() {
        val frame = EncryptedFrame(net, sender, 0u, 3u, 0L, ByteArray(40))
        val wire = FrameCodec.encode(frame)
        assertFailsWith<EOFException> { FrameCodec.decode(wire.copyOf(wire.size - 10)) }
    }

    @Test
    fun `codec validates id sizes on encode`() {
        val badNet = EncryptedFrame(NetworkId(ByteArray(7)), sender, 0u, 3u, 0L, ByteArray(1))
        assertFailsWith<IllegalArgumentException> { FrameCodec.encode(badNet) }
        val badSender = EncryptedFrame(net, MemberId(ByteArray(31)), 0u, 3u, 0L, ByteArray(1))
        assertFailsWith<IllegalArgumentException> { FrameCodec.encode(badSender) }
    }

    @Test
    fun `verify returns false for malformed signatures and foreign keys`() {
        val pair = Identity.generate()
        val data = "data".toByteArray()
        val sig = Identity.sign(pair, data)!!
        assertFalse(Identity.verify(pair.public, data, ByteArray(0)))
        assertFalse(Identity.verify(pair.public, data, ByteArray(sig.size) { 0x55 }))
        assertFalse(Identity.verify(Identity.generate().public, data, sig))
    }
}
