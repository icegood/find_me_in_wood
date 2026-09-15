package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId

class ReplayWindow(private val clockMs: () -> Long) {
    private val windowMs = 10 * 60 * 1000L
    private val seen = HashMap<ByteArray, UInt>() // senderId -> highest seq

    /** true if frame is fresh and should be processed; records it. */
    fun accept(senderId: ByteArray, seq: UInt, sentAtMs: Long): Boolean {
        val now = clockMs()
        if (sentAtMs > now + windowMs || sentAtMs < now - windowMs) return false
        val high = seen[senderId]
        if (high != null && seq <= high) return false
        seen[senderId] = seq
        return true
    }
}
