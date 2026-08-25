package app.findmeinwood.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HkdfTest {
    @Test
    fun `rfc5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    fun `network keys are deterministic and sized`() {
        val k1 = NetworkKeysFactory.derive("secret".toByteArray())
        val k2 = NetworkKeysFactory.derive("secret".toByteArray())
        val k3 = NetworkKeysFactory.derive("other".toByteArray())
        assertContentEquals(k1.networkId.bytes, k2.networkId.bytes)
        assertContentEquals(k1.trafficKey, k2.trafficKey)
        assertTrue(!k1.networkId.bytes.contentEquals(k3.networkId.bytes))
        assertEquals(8, k1.networkId.bytes.size)
        assertEquals(32, k1.trafficKey.size)
        assertEquals(4, k1.verifyTag.size)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

class AeadTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `seal open roundtrip with aad`() {
        val pt = "position payload".toByteArray()
        val aad = "header".toByteArray()
        val ct = AesGcmAead.seal(key, pt, aad)
        assertContentEquals(pt, AesGcmAead.open(key, ct, aad))
    }

    @Test
    fun `tampered ciphertext or aad fails`() {
        val ct = AesGcmAead.seal(key, "x".toByteArray(), "aad".toByteArray())
        ct[ct.size - 1] = (ct.last() + 1).toByte()
        assertFailsWith<Exception> { AesGcmAead.open(key, ct, "aad".toByteArray()) }
        assertFailsWith<Exception> {
            AesGcmAead.open(key, AesGcmAead.seal(key, "x".toByteArray(), "aad".toByteArray()), "bad".toByteArray())
        }
    }
}

class IdentityTest {
    @Test
    fun `sign verify roundtrip and reject`() {
        val pair = Identity.generate()
        val memberId = Identity.memberIdOf(pair)
        assertEquals(32, memberId.size)
        val data = "beacon header".toByteArray()
        val sig = Identity.sign(pair, data)
        assertTrue(Identity.verify(memberId, data, sig))
        assertFalse(Identity.verify(memberId, data + 1, sig))
        val other = Identity.generate()
        assertFalse(Identity.verify(Identity.memberIdOf(other), data, sig))
    }
}
