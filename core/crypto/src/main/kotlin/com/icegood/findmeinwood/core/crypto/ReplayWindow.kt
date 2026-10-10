package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId

/**
 * Per-sender replay filter. Only call [accept] for frames that already passed AEAD
 * authentication: it records the seq, so a forged frame with a huge seq would otherwise
 * lock the real sender out.
 */
class ReplayWindow(private val clockMs: () -> Long) {
    private val windowMs = 10 * 60 * 1000L
    // Keyed by MemberId (content equality): a raw ByteArray key hashes by identity, so
    // every decoded frame looked like a new sender and nothing was ever rejected.
    private val seen = HashMap<MemberId, UInt>() // senderId -> highest seq

    /** true if frame is fresh and should be processed; records it. */
    @Synchronized
    fun accept(senderId: ByteArray, seq: UInt, sentAtMs: Long): Boolean {
        val now = clockMs()
        if (sentAtMs > now + windowMs || sentAtMs < now - windowMs) return false
        val key = MemberId(senderId.copyOf())
        val high = seen[key]
        if (high != null && seq <= high) return false
        seen[key] = seq
        return true
    }
}
