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
    /** Transport of the last frame heard directly from the peer; null when only relayed. */
    val transport: com.icegood.findmeinwood.core.model.TransportId? = null,
    /** Link transport selected with the peer (LNP/3); null until the handshake is done. */
    val link: com.icegood.findmeinwood.core.model.TransportId? = null,
    /** Transports agreed in the handshake: the ones the link can be switched to. */
    val linkOptions: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** Next agreed transport while the selected link is quiet (a hint, never applied). */
    val linkSuggestion: com.icegood.findmeinwood.core.model.TransportId? = null,
    /** Transports both offer: what the handshake asks the two users to confirm. */
    val linkCommon: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** This phone's user confirmed [linkCommon]. */
    val linkConfirmedByMe: Boolean = false,
    /** The peer's user confirmed it. */
    val linkConfirmedByPeer: Boolean = false,
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

    fun onBeacon(b: DecryptedBeacon, transport: com.icegood.findmeinwood.core.model.TransportId? = null) {
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
            transport = transport,
            link = cur?.link,
            linkOptions = cur?.linkOptions ?: emptyList(),
            linkSuggestion = cur?.linkSuggestion,
            linkCommon = cur?.linkCommon ?: emptyList(),
            linkConfirmedByMe = cur?.linkConfirmedByMe ?: false,
            linkConfirmedByPeer = cur?.linkConfirmedByPeer ?: false,
        ))
    }

    /** Link state from LNP/3: selection, agreed options, suggestion. */
    fun setLinkInfo(
        member: MemberId,
        link: com.icegood.findmeinwood.core.model.TransportId?,
        options: List<com.icegood.findmeinwood.core.model.TransportId>,
        suggestion: com.icegood.findmeinwood.core.model.TransportId?,
        common: List<com.icegood.findmeinwood.core.model.TransportId> = options,
        confirmedByMe: Boolean = options.isNotEmpty(),
        confirmedByPeer: Boolean = options.isNotEmpty(),
    ) {
        val cur = peers.value[member] ?: return
        val next = cur.copy(
            link = link, linkOptions = options, linkSuggestion = suggestion, linkCommon = common,
            linkConfirmedByMe = confirmedByMe, linkConfirmedByPeer = confirmedByPeer,
        )
        if (next != cur) peers.value = peers.value + (member to next)
    }

    /** Display name announced by the peer (LNP/3 link control). */
    fun setNickname(member: MemberId, name: String) {
        val cur = peers.value[member] ?: return
        if (cur.nickname != name) peers.value = peers.value + (member to cur.copy(nickname = name))
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
