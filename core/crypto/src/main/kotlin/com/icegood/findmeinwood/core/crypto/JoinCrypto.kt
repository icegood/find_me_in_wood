package com.icegood.findmeinwood.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * FR-8.3/FR-8.4: ephemeral ECDH for the PRIVATE join handshake. X25519 is used where the
 * platform offers it (API 31+), otherwise EC P-256 (minSdk 29 safe). The shared secret is
 * expanded with HKDF into an AEAD key plus the 6-digit SAS both humans compare.
 */
object JoinCrypto {
    private const val EC = "EC"
    private const val X25519 = "X25519"

    /**
     * Handshake keys must be usable by *both* phones: a device without X25519 (API < 31)
     * cannot agree with an X25519 peer, and vice versa. EC P-256 is available from API 26
     * on every device, so the handshake always uses it.
     */
    fun ephemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(EC).apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    fun publicKeyOf(encoded: ByteArray): PublicKey = KeyParse.publicKey(encoded)

    fun sharedSecret(mine: KeyPair, peerPublic: ByteArray): ByteArray {
        val peer = publicKeyOf(peerPublic)
        // Pick the agreement that matches the keys we actually hold: some JVMs expose
        // X25519 keys but not an X25519 KeyAgreement, and mixing families fails.
        val wantX = mine.private.algorithm.equals(X25519, ignoreCase = true) ||
            peer.algorithm.equals(X25519, ignoreCase = true)
        // Android names it X25519, the JVM calls it XDH: try both, then ECDH.
        val names = if (wantX) listOf(X25519, "XDH", "ECDH") else listOf("ECDH")
        var last: Exception? = null
        for (name in names) {
            try {
                val ka = KeyAgreement.getInstance(name)
                ka.init(mine.private)
                ka.doPhase(peer, true)
                return ka.generateSecret()
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no key agreement available")
    }

    fun aeadKey(shared: ByteArray): ByteArray =
        Hkdf.derive(shared, ByteArray(32), "fmiw-join".toByteArray(), 32)

    /** FR-8.3: short authentication string both screens show and a human compares. */
    fun sas(shared: ByteArray): String {
        val bytes = Hkdf.derive(shared, ByteArray(32), "fmiw-sas".toByteArray(), 4)
        val n = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return ((n and 0x7FFFFFFF) % 1_000_000).toString().padStart(6, '0')
    }

    fun nonce(): Long = SecureRandom().nextLong()
}
