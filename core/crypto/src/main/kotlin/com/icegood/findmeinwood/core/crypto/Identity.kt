package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.NetworkId
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Device identity (NFR-3). Ed25519 is used when the platform provides it (API 31+); older
 * devices fall back to EC P-256 so minSdk 26 keeps working. Member ids are always the
 * 32-byte SHA-256 of the X.509-encoded public key, so ids are algorithm independent.
 */
object Identity {
    private const val ED25519 = "Ed25519"
    private const val EC_FALLBACK = "EC"

    fun generate(): KeyPair = try {
        KeyPairGenerator.getInstance(ED25519).generateKeyPair()
    } catch (_: java.security.NoSuchAlgorithmException) {
        KeyPairGenerator.getInstance(EC_FALLBACK).apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    fun memberIdOf(pair: KeyPair): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)

    /**
     * Signs [data], or returns null when the platform cannot sign with this key type
     * (some conscrypt builds reject Ed25519 signatures). Callers then fall back to the
     * AEAD-only path instead of killing the session.
     */
    fun sign(pair: KeyPair, data: ByteArray): ByteArray? = try {
        withSignature(pair.private.algorithm) { s ->
            s.initSign(pair.private)
            s.update(data)
            s.sign()
        }
    } catch (_: Exception) {
        null
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        withSignature(publicKey.algorithm) { s ->
            s.initVerify(publicKey)
            s.update(data)
            s.verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    /** JCA names differ across platforms (EdDSA vs Ed25519); try them in order. */
    private fun <T> withSignature(keyAlgorithm: String, block: (Signature) -> T): T {
        val names = if (keyAlgorithm.contains("Ed", ignoreCase = true)) {
            listOf("EdDSA", ED25519)
        } else {
            listOf("SHA256withECDSA")
        }
        var last: Exception? = null
        for (name in names) {
            try {
                return block(Signature.getInstance(name))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no signature algorithm for $keyAlgorithm")
    }
}

data class NetworkKeys(
    val networkId: NetworkId,
    val trafficKey: ByteArray,
    val verifyTag: ByteArray,
)

object NetworkKeysFactory {
    private val NET_ID = "net-id".toByteArray()
    private val AEAD = "aead".toByteArray()
    private val VERIFY = "verify".toByteArray()

    fun derive(secret: ByteArray): NetworkKeys = NetworkKeys(
        networkId = NetworkId(Hkdf.derive(secret, ByteArray(32), NET_ID, 8)),
        trafficKey = Hkdf.derive(secret, ByteArray(32), AEAD, 32),
        verifyTag = Hkdf.derive(secret, ByteArray(32), VERIFY, 4),
    )
}
