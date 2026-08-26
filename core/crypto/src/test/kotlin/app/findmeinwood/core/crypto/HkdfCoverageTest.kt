package app.findmeinwood.core.crypto

import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HkdfCoverageTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `expand across multiple blocks`() {
        // RFC 5869 case 1 PRK; request 82 bytes -> spans 3 SHA-256 blocks
        val prk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Hkdf.expand(prk, info, 82)
        val okm42 = Hkdf.expand(prk, info, 42)
        // first 42 bytes must equal the RFC case-1 OKM
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm.copyOf(42)),
        )
        assertEquals(hex(okm42), hex(okm.copyOf(42))) // stable across requested lengths
        assertEquals(82, okm.size)
    }

    @Test
    fun `rejects output longer than 255 blocks`() {
        assertFailsWith<IllegalArgumentException> {
            Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1)
        }
    }

    @Test
    fun `empty salt is padded to hash length`() {
        // RFC 5869 case 3: salt empty -> HMAC key = zeros
        val ikm = hex("0b".repeat(22))
        val okm = Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42)
        assertEqualsHex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm,
        )
        assertContentEquals(Hkdf.extract(ByteArray(0), ikm), Hkdf.extract(ByteArray(0), ikm))
    }

    private fun assertEqualsHex(expected: String, actual: ByteArray) {
        assertTrue(expected == hex(actual), "expected $expected got ${hex(actual)}")
    }
}
