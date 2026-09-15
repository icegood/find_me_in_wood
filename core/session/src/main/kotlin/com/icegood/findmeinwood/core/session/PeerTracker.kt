package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.DecryptedBeacon
import com.icegood.findmeinwood.core.model.MemberId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PeerState(
    val memberId: MemberId,
    val nickname: String? = null,
    val lastPosition: com.icegood.findmeinwood.core.model.BeaconPosition? = null,
    val lastSeenMs: Long,
    val lastSeq: UInt,
    val viaMemberId: MemberId? = null,
    val verified: Boolean = true,
)

/** Pure-Kotlin peer table (FR-6.1/6.3): staleness + purge, injectable clock. */
class PeerTracker(
    private val keepAliveMs: Long,
    private val nowMs: () -> Long,
    private val inactiveAfterWindows: Int = 3,
    private val purgeAfterMs: Long = 24 * 60 * 60 * 1000,
) {
    private val peers = MutableStateFlow<Map<MemberId, PeerState>>(emptyMap())
    val peersFlow: StateFlow<Map<MemberId, PeerState>> = peers

    fun onBeacon(b: DecryptedBeacon) {
        val now = nowMs()
        val cur = peers.value[b.senderId]
        if (cur != null && b.seq <= cur.lastSeq) return
        peers.value = peers.value + (b.senderId to PeerState(
            memberId = b.senderId,
            nickname = cur?.nickname,
            lastPosition = b.payload.position ?: cur?.lastPosition,
            lastSeenMs = now,
            lastSeq = b.seq,
            viaMemberId = cur?.viaMemberId,
            verified = cur?.verified ?: true,
        ))
    }

    /** Marks peers stale / purges old ones; call periodically or from [tick]. */
    fun tick() {
        val now = nowMs()
        val staleBefore = now - keepAliveMs * inactiveAfterWindows
        val purgeBefore = now - purgeAfterMs
        peers.value = peers.value.mapValues { (_, p) ->
            if (p.lastSeenMs < staleBefore) p.copy(lastPosition = null) else p
        }.filterValues { it.lastSeenMs >= purgeBefore }
    }
}
