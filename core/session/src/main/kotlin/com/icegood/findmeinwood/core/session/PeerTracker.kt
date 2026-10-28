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
    /** Transports both users allowed: the ones the link can use and be switched to. */
    val linkOptions: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** Next agreed transport while the selected link is quiet (a hint, never applied). */
    val linkSuggestion: com.icegood.findmeinwood.core.model.TransportId? = null,
    /** Transports both offer: what each user decides on, one by one. */
    val linkCommon: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** Of [linkCommon], what this phone's user allowed. */
    val linkAllowedByMe: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** Of [linkCommon], what this phone's user turned down (the rest is undecided). */
    val linkDeniedByMe: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** Of [linkCommon], what the peer's user allowed. */
    val linkAllowedByPeer: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
    /** Who hosts the Wi-Fi Direct group; null while Wi-Fi Direct is agreed with nobody. */
    val wifiDirectHost: WifiDirectHost? = null,
)

/** The Wi-Fi Direct group's host: [member], or this phone when that is null. */
data class WifiDirectHost(val member: MemberId?)

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
            linkAllowedByMe = cur?.linkAllowedByMe ?: emptyList(),
            linkDeniedByMe = cur?.linkDeniedByMe ?: emptyList(),
            linkAllowedByPeer = cur?.linkAllowedByPeer ?: emptyList(),
            wifiDirectHost = cur?.wifiDirectHost,
        ))
    }

    /** Link state from LNP/3: selection, agreed options, suggestion. */
    fun setLinkInfo(
        member: MemberId,
        link: com.icegood.findmeinwood.core.model.TransportId?,
        options: List<com.icegood.findmeinwood.core.model.TransportId>,
        suggestion: com.icegood.findmeinwood.core.model.TransportId?,
        common: List<com.icegood.findmeinwood.core.model.TransportId> = options,
        allowedByMe: List<com.icegood.findmeinwood.core.model.TransportId> = options,
        deniedByMe: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList(),
        allowedByPeer: List<com.icegood.findmeinwood.core.model.TransportId> = options,
        wifiDirectHost: WifiDirectHost? = null,
    ) {
        val cur = peers.value[member] ?: return
        val next = cur.copy(
            link = link, linkOptions = options, linkSuggestion = suggestion, linkCommon = common,
            linkAllowedByMe = allowedByMe, linkDeniedByMe = deniedByMe, linkAllowedByPeer = allowedByPeer,
            wifiDirectHost = wifiDirectHost,
        )
        if (next != cur) peers.value = peers.value + (member to next)
    }

    /** Display name announced by the peer (LNP/3 link control). */
    fun setNickname(member: MemberId, name: String) {
        val cur = peers.value[member] ?: return
        if (cur.nickname != name) peers.value = peers.value + (member to cur.copy(nickname = name))
    }

    /** Drops every peer [gone] says is gone (a member the master removed). */
    fun forget(gone: (MemberId) -> Boolean) {
        if (peers.value.keys.any(gone)) peers.value = peers.value.filterKeys { !gone(it) }
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
