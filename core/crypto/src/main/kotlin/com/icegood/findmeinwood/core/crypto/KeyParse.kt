package com.icegood.findmeinwood.core.crypto

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Picks the right KeyFactory for an X.509-encoded public key by inspecting the algorithm
 * OID. Guessing ("try X25519 first") silently mis-parses EC keys on devices that do have
 * X25519 (API 31+), which then fails at KeyAgreement time with
 * "Only OpenSSLX25519PublicKey accepted".
 */
internal object KeyParse {
    // OIDs are short-form: 06 03 <3 bytes>
    private val X25519_OID = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x6E) // 1.3.101.110
    private val ED25519_OID = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x70) // 1.3.101.112

    fun isX25519(spki: ByteArray) = contains(spki, X25519_OID)
    fun isEd25519(spki: ByteArray) = contains(spki, ED25519_OID)

    private fun contains(data: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || data.size < pattern.size) return false
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return true
        }
        return false
    }

    fun publicKey(spki: ByteArray): PublicKey = when {
        isX25519(spki) -> factory("X25519", spki)
        isEd25519(spki) -> factory("Ed25519", spki)
        else -> factory("EC", spki)
    }

    private fun factory(algorithm: String, spki: ByteArray): PublicKey =
        KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(spki))
}
