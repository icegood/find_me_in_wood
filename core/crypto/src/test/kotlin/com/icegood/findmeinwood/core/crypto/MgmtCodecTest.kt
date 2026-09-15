package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.MgmtType
import com.icegood.findmeinwood.core.model.NetworkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MgmtCodecTest {
    private val pair = Identity.generate()
    private val netId = NetworkId(ByteArray(8) { 3 })
    private val sender = MemberId(Identity.memberIdOf(pair))

    private fun frame(type: MgmtType, payload: ByteArray = byteArrayOf(1, 2, 3), nonce: Long = 42L) =
        MgmtCodec.encode(MgmtFrame(type, netId, sender, nonce, payload, ByteArray(0)), pair)

    @Test
    fun `encode decode roundtrip`() {
        val f = MgmtCodec.decode(frame(MgmtType.HELLO, byteArrayOf(9, 8, 7)))
        assertEquals(MgmtType.HELLO, f.type)
        assertEquals(42L, f.nonce)
        assertEquals(netId, f.networkId)
        assertEquals(sender, f.senderId)
        assertTrue(f.payload.contentEquals(byteArrayOf(9, 8, 7)))
        assertTrue(f.signature.isNotEmpty())
    }

    @Test
    fun `isMgmt distinguishes management frames from beacons`() {
        val mgmt = frame(MgmtType.JOIN_REQ)
        assertTrue(MgmtCodec.isMgmt(mgmt))
        val beacon = FrameCodec.encode(
            com.icegood.findmeinwood.core.model.EncryptedFrame(netId, sender, 1u, 3u, 0L, ByteArray(4)),
        )
        assertFalse(MgmtCodec.isMgmt(beacon))
        assertFalse(MgmtCodec.isMgmt(ByteArray(3)))
    }

    @Test
    fun `signature verifies for the sender and fails for others`() {
        val f = MgmtCodec.decode(frame(MgmtType.JOIN_ACCEPT))
        assertTrue(MgmtCodec.verify(f, pair.public))
        assertFalse(MgmtCodec.verify(f, Identity.generate().public))
    }

    @Test
    fun `tampered payload breaks verification`() {
        val f = MgmtCodec.decode(frame(MgmtType.HELLO, byteArrayOf(1)))
        val tampered = f.copy(payload = byteArrayOf(2))
        assertFalse(MgmtCodec.verify(tampered, pair.public))
    }

    @Test
    fun `truncated frames are rejected`() {
        val bytes = frame(MgmtType.HELLO)
        assertFalse(runCatching { MgmtCodec.decode(bytes.copyOfRange(0, 20)) }.isSuccess)
    }
}
