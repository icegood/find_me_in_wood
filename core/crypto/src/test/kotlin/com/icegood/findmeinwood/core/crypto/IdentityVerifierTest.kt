package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityVerifierTest {
    private val v = IdentityVerifier()
    private val pair = Identity.generate()
    private val member = MemberId(Identity.memberIdOf(pair))
    private val data = "beacon".toByteArray()

    @Test
    fun `pins a key then verifies signatures`() {
        assertNull(v.keyFor(member))
        assertTrue(v.pin(member, pair.public.encoded))
        assertNotNull(v.keyFor(member))
        assertTrue(v.verify(member, data, Identity.sign(pair, data)!!))
        assertFalse(v.verify(member, "other".toByteArray(), Identity.sign(pair, data)!!))
    }

    @Test
    fun `refuses a different key for the same member`() {
        assertTrue(v.pin(member, pair.public.encoded))
        val impostor = Identity.generate()
        assertFalse(v.pin(member, impostor.public.encoded))
        // the original key stays pinned
        assertTrue(v.verify(member, data, Identity.sign(pair, data)!!))
    }

    @Test
    fun `unknown member and garbage keys are rejected`() {
        assertFalse(v.verify(member, data, Identity.sign(pair, data)!!))
        assertFalse(v.pin(member, ByteArray(4)))
        v.reset()
        assertNull(v.keyFor(member))
    }

    @Test
    fun `join crypto derives the same secret and sas on both sides`() {
        val a = JoinCrypto.ephemeralKeyPair()
        val b = JoinCrypto.ephemeralKeyPair()
        val s1 = JoinCrypto.sharedSecret(a, b.public.encoded)
        val s2 = JoinCrypto.sharedSecret(b, a.public.encoded)
        assertTrue(s1.contentEquals(s2))
        assertEquals(JoinCrypto.sas(s1), JoinCrypto.sas(s2))
        assertEquals(6, JoinCrypto.sas(s1).length)
        assertTrue(JoinCrypto.sas(s1).all { it.isDigit() })
        assertEquals(32, JoinCrypto.aeadKey(s1).size)
        assertTrue(JoinCrypto.nonce() != 0L)
        // key parsing must work for both key families (EC fallback on older devices)
        val ec = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        assertNotNull(JoinCrypto.publicKeyOf(ec.public.encoded))
        assertNotNull(JoinCrypto.publicKeyOf(a.public.encoded))
        assertEquals("EC", ec.public.algorithm)
        // EC peers still agree on a shared secret
        val ecB = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        assertTrue(JoinCrypto.sharedSecret(ec, ecB.public.encoded)
            .contentEquals(JoinCrypto.sharedSecret(ecB, ec.public.encoded)))
    }
}
