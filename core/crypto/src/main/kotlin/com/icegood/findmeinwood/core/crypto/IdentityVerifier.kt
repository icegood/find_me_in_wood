package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import java.security.PublicKey

/**
 * NFR-3: beacon origin authentication. Each beacon header is signed with the sender's
 * device identity (Ed25519 where available, EC P-256 otherwise). Keys are pinned on first
 * sight (trust-on-first-use) — the network AEAD still protects the transport, this adds
 * "this frame really came from the holder of that member id".
 */
class IdentityVerifier {
    private val pinned = HashMap<MemberId, PublicKey>()

    @Synchronized
    fun pin(member: MemberId, encodedKey: ByteArray): Boolean {
        val key = runCatching { publicKeyOf(encodedKey) }.getOrNull() ?: return false
        val previous = pinned[member]
        return if (previous == null || previous.encoded.contentEquals(key.encoded)) {
            pinned[member] = key
            true
        } else {
            false // key changed for the same member id: refuse
        }
    }

    @Synchronized
    fun keyFor(member: MemberId): PublicKey? = pinned[member]

    /** true when the signature verifies against the pinned key of [member]. */
    @Synchronized
    fun verify(member: MemberId, signedBytes: ByteArray, signature: ByteArray): Boolean {
        val key = pinned[member] ?: return false
        return Identity.verify(key, signedBytes, signature)
    }

    @Synchronized
    fun reset() = pinned.clear()

    companion object {
        fun publicKeyOf(encoded: ByteArray): PublicKey = KeyParse.publicKey(encoded)
    }
}
