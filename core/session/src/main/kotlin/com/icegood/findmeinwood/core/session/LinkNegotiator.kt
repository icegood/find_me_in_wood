package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkAck
import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkHost
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkSel
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.TransportId
import java.util.zip.CRC32

/**
 * LNP/3, the Link Negotiation Protocol (spec FR-3.5–3.10). Pure Kotlin.
 *
 * - Handshake, per transport: both list the transports they can use (an SMS offer carries
 *   the number). For each transport both offer, each *user* decides on their own phone
 *   whether it may connect them ([allow]); the phone acknowledges the transports its user
 *   allowed (list + hash, so both mean the same addresses). A transport is agreed when
 *   both users allowed it, and stops being agreed as soon as either one disallows it. A
 *   decision keeps holding while the radio is off; a new transport or address needs a new
 *   one.
 * - Selection, any time, no further handshake: either side picks a transport within the
 *   agreed set ([select]); it applies at once and is announced in the next beacons, where
 *   the peer applies it too. The higher selection epoch is current; equal: lower id wins.
 * - Default: once agreed, both start on the cheapest agreed transport — computed the same
 *   on both sides, so nothing needs to be sent.
 * - No fallback: nothing switches by itself. When the selected link goes quiet the
 *   negotiator only [suggest]s the next agreed transport; switching is the user's choice.
 * - Routing: frames go out only on radios this user allowed for some peer ([radios]), so
 *   a pair stays in touch while any of its agreed radios reaches.
 * - Wi-Fi Direct is per pair too ([wifiDirectHostOf]): of the two, the lower member id hosts
 *   unless the pair picked the other way round ([pickHost], announced like a selection).
 *   This phone serves one group it hosts and one per member it joins
 *   ([wifiDirectGroups]); the radio takes them in turn.
 */
class LinkNegotiator(
    private val self: MemberId,
    private val nowMs: () -> Long,
    /** Beacon cadence; a missed beacon on the selected link makes it "quiet". */
    private val beaconIntervalMs: Long,
    /** SMS report cadence; a missed report on an SMS link makes it "quiet". */
    private val smsIntervalMs: Long = 30 * 60_000L,
) {
    companion object {
        /** Standard order, cheapest and fastest first. */
        val RUNGS = listOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT, TransportId.LORA, TransportId.SMS)

        /** Link control rides on every Nth beacon even when nothing changed. */
        const val OFFERS_EVERY = 5

        /** A selection is repeated on this many beacons (the first may be lost). */
        const val SELECT_BEACONS = 3

        fun prefix(id: MemberId): ByteArray = id.bytes.copyOf(4)
    }

    /** The selected link to a peer changed (for the log and the UI). */
    data class Switch(val peer: MemberId, val from: TransportId?, val to: TransportId?)

    /** Read-only view of one peer link. */
    data class LinkView(
        val peer: MemberId,
        /** Transports both offer, in standard order: what each user decides on. */
        val common: List<TransportId>,
        /** Transports both users allowed: the ones the link can use. */
        val agreed: List<TransportId>,
        /** Common transports this phone's user allowed. */
        val allowedByMe: List<TransportId>,
        /** Common transports this phone's user turned down. */
        val deniedByMe: List<TransportId>,
        /** Common transports the peer's user allowed (their acknowledgement matches). */
        val allowedByPeer: List<TransportId>,
        /** Selected link; null until something is agreed. */
        val link: TransportId?,
        /** Next agreed transport when the selected link is quiet; never applied by itself. */
        val suggestion: TransportId?,
        val lastHeardMs: Long,
    )

    private class Peer {
        var offers: List<LinkOffer>? = null
        /** Their last acknowledgement for us: allowed transports (null = all common) + hash. */
        var theirAckT: List<TransportId>? = null
        var theirAckH: Int? = null
        /** Their echo of our last acknowledgement they received. */
        var theirEcho: Int? = null
        val heard = HashMap<TransportId, Long>()
        var lastHeardMs = 0L
        /** Explicit selection (from either side); null = the default. */
        var selected: TransportId? = null
        var epoch = 0
        var selectedAtMs = 0L
        var announce = 0
        var shown: TransportId? = null
        /** What this user decided for the peer: transport + the two addresses then -> allowed. */
        val decisions = HashMap<String, Boolean>()
        /** We decided something for this peer (an empty allowed list is an answer too). */
        var acking = false
        /** Picked Wi-Fi Direct host: true = this phone, false = the peer, null = the lower id. */
        var hostMe: Boolean? = null
        var hostEpoch = 0
        var hostAnnounce = 0
    }

    private val peers = HashMap<MemberId, Peer>()
    private var myOffers: List<LinkOffer> = emptyList()
    private var myName: String? = null
    private var dirty = true
    private var beaconCount = 0
    private val ackSent = HashMap<MemberId, Int>()
    private val echoSent = HashMap<MemberId, Int?>()
    /** The peer is waiting for our answer (handshake step or a selection to announce). */
    private var urgent = false

    /**
     * True when link control should go out now rather than with the next scheduled beacon:
     * every handshake step is answered at once, so a handshake completes within one beacon
     * interval (to first hear the peer) plus a few round trips.
     */
    @Synchronized
    fun replyNow(): Boolean = urgent

    /** Put our offers and acks on the very next beacon (e.g. a new radio link came up). */
    @Synchronized
    fun announceNow() {
        dirty = true
        urgent = true
    }

    @Synchronized
    fun setOffers(offers: List<LinkOffer>) {
        val sorted = offers.filter { it.t in RUNGS }.distinctBy { it.t }.sortedBy { RUNGS.indexOf(it.t) }
        if (sorted != myOffers) {
            myOffers = sorted
            dirty = true
        }
    }

    @Synchronized
    fun offers(): List<LinkOffer> = myOffers

    /** Display name announced with the link control. */
    @Synchronized
    fun setName(name: String?) {
        val n = name?.trim()?.take(32)?.ifBlank { null }
        if (n != myName) {
            myName = n
            dirty = true
        }
    }

    /** Link control for the next beacon, or null when there is nothing worth the bytes. */
    @Synchronized
    fun outgoing(): LinkCtl? {
        beaconCount++
        // Acknowledge exactly what this phone's user allowed, echoing the peer's own ack.
        val acks = peers.mapNotNull { (id, p) ->
            val offers = p.offers ?: return@mapNotNull null
            if (!p.acking) return@mapNotNull null
            val allowed = allowedByMe(id, p)
            id to LinkAck(prefix(id), ackHash(id, offers, allowed), allowed, p.theirAckH)
        }
        // Until the peer echoes our ack, and until we echoed theirs.
        val handshaking = acks.any { (id, ack) -> ackSent[id] != ack.h || peers[id]?.theirEcho != ack.h || echoSent[id] != ack.e }
        val sels = peers.filter { (_, p) -> p.announce > 0 && p.selected != null }
        val hostPicks = peers.filter { (_, p) -> p.hostAnnounce > 0 && p.hostMe != null }
        // An answer the peer waits for always goes out (and clears [urgent]), else every
        // frame heard meanwhile would ask for one more extra beacon.
        if (!dirty && !urgent && !handshaking && sels.isEmpty() && hostPicks.isEmpty() &&
            beaconCount % OFFERS_EVERY != 0
        ) return null
        dirty = false
        urgent = false
        acks.forEach { (id, ack) -> ackSent[id] = ack.h; echoSent[id] = ack.e }
        sels.values.forEach { it.announce-- }
        hostPicks.values.forEach { it.hostAnnounce-- }
        return LinkCtl(
            offers = myOffers,
            acks = acks.map { it.second },
            sel = sels.map { (id, p) -> LinkSel(prefix(id), p.selected!!, p.epoch) },
            name = myName,
            hosts = hostPicks.map { (id, p) -> LinkHost(prefix(id), p.hostMe!!, p.hostEpoch) },
        )
    }

    /**
     * An authenticated frame from [peer] arrived on [source]. [direct] = not relayed (TTL
     * intact): only that counts as hearing the peer on that transport. [ctl] may come
     * relayed: it is signed by the peer, so its offers and selections are its own.
     */
    @Synchronized
    fun onFrame(peer: MemberId, source: TransportId, direct: Boolean, ctl: LinkCtl?): List<Switch> {
        if (peer == self) return emptyList()
        val p = peers.getOrPut(peer) { Peer().also { seed(peer, it) } }
        if (direct && source in RUNGS) {
            val now = nowMs()
            p.heard[source] = now
            p.lastHeardMs = now
        }
        if (ctl != null && ctl.v == 3) {
            val offers = ctl.offers.filter { it.t in RUNGS }.distinctBy { it.t }
            val ack = ctl.acks.firstOrNull { it.p.contentEquals(prefix(self)) }
            // Answer at once: new offers, a changed ack (to echo), or ours not yet echoed.
            val mine = if (p.acking) ackHash(peer, offers, allowedByMe(peer, p, offers)) else null
            if (offers != p.offers || ack?.h != p.theirAckH || ack?.t != p.theirAckT ||
                mine != null && (ackSent[peer] != mine || ack?.e != mine)
            ) urgent = true
            p.offers = offers
            p.theirAckT = ack?.t
            p.theirAckH = ack?.h
            p.theirEcho = ack?.e
            ctl.sel.firstOrNull { it.p.contentEquals(prefix(self)) }?.let { s ->
                val newer = s.e > p.epoch || s.e == p.epoch && s.t != p.selected && hex(peer) < hex(self)
                if (newer && s.t in agreedSet(peer, p)) {
                    p.selected = s.t
                    p.epoch = s.e
                    p.selectedAtMs = nowMs()
                }
            }
            // Their pick says whether *they* host: for this phone it is the other way round.
            ctl.hosts.firstOrNull { it.p.contentEquals(prefix(self)) }?.let { h ->
                val meHosts = !h.h
                val newer = h.e > p.hostEpoch || h.e == p.hostEpoch && p.hostMe != meHosts && hex(peer) < hex(self)
                if (newer) {
                    p.hostMe = meHosts
                    p.hostEpoch = h.e
                }
            }
        }
        return listOfNotNull(changed(peer, p))
    }

    /**
     * This phone's user allows ([allow] true) or turns down [t] for the link with [peer].
     * The acknowledgement goes out at once; [t] is agreed once the peer's user allowed it
     * too, and stops being agreed at once when either turns it down.
     */
    @Synchronized
    fun allow(peer: MemberId, t: TransportId, allow: Boolean): List<Switch> {
        val p = peers[peer] ?: return emptyList()
        val offers = p.offers ?: return emptyList()
        val e = entry(t, offers) ?: return emptyList()
        p.decisions[e] = allow
        p.acking = true
        dirty = true
        urgent = true
        val switched = listOfNotNull(changed(peer, p))
        onDecided?.invoke()
        return switched
    }

    /** Decisions kept from an earlier run, applied once each peer is heard again. */
    private val restored = HashMap<MemberId, Map<String, Boolean>>()

    /** This user decided something; [decisions] then has it (to keep across restarts). */
    var onDecided: (() -> Unit)? = null

    /**
     * Brings back what this user decided in an earlier run (FR-3.7: a decision holds until
     * the user changes it, also across app restarts). A peer is acknowledged as soon as it
     * is heard, so a known pair reconnects without asking anyone again.
     */
    @Synchronized
    fun restore(saved: Map<MemberId, Map<String, Boolean>>) {
        restored.putAll(saved)
        saved.keys.forEach { id -> peers[id]?.let { seed(id, it) } }
    }

    /** Every decision this user made, per peer: decision key (transport and both addresses) -> allowed. */
    @Synchronized
    fun decisions(): Map<MemberId, Map<String, Boolean>> =
        restored + peers.filterValues { it.decisions.isNotEmpty() }.mapValues { (_, p) -> HashMap(p.decisions) }

    private fun seed(id: MemberId, p: Peer) {
        val saved = restored[id] ?: return
        saved.forEach { (k, v) -> p.decisions.putIfAbsent(k, v) }
        if (saved.isNotEmpty()) { p.acking = true; dirty = true }
    }

    /** This phone's user allows every transport currently common with [peer]. */
    @Synchronized
    fun confirm(peer: MemberId): List<Switch> =
        common(peer).flatMap { allow(peer, it, true) }.lastOrNull()?.let { listOf(it) } ?: emptyList()

    /** Selects [t] for the link with [peer], at once; false when [t] is not in the agreed set. */
    @Synchronized
    fun select(peer: MemberId, t: TransportId): List<Switch> {
        val p = peers[peer] ?: return emptyList()
        if (t !in agreedSet(peer, p)) return emptyList()
        p.selected = t
        p.epoch++
        p.selectedAtMs = nowMs()
        p.announce = SELECT_BEACONS
        urgent = true
        return listOfNotNull(changed(peer, p))
    }

    /** Re-evaluates links (e.g. a handshake completing or an offer withdrawn). */
    @Synchronized
    fun tick(): List<Switch> = peers.mapNotNull { (id, p) -> changed(id, p) }

    /**
     * Radios frames go out on: those this user allowed or has not decided on yet for any
     * peer, or every radio (null) while nobody was met yet or a peer heard lately waits for
     * this user's decision (its offers and the handshake must get through). Only a radio
     * turned down for every peer goes quiet: an undecided one keeps carrying frames even
     * when the peer fell silent, else two undecided phones would never hear each other
     * again. Radios outside [RUNGS] are not negotiated and never limited.
     */
    @Synchronized
    fun radios(): Set<TransportId>? {
        if (peers.isEmpty()) return null
        val now = nowMs()
        val pending = peers.any { (id, p) -> heardLately(p, now) && (p.offers == null || undecided(id, p).isNotEmpty()) }
        if (pending) return null
        return peers.flatMap { (id, p) -> allowedByMe(id, p) + undecided(id, p) }.toSet()
    }

    /** One Wi-Fi Direct group this phone serves: hosted by [host] (this phone or a member), with [members]. */
    data class WifiDirectGroup(val host: MemberId, val members: List<MemberId>)

    /** The network's members (its member list): being one means being able to pair with every other. */
    private var members: Set<MemberId> = emptySet()

    @Synchronized
    fun setMembers(ids: Collection<MemberId>) {
        members = ids.toSet() - self
    }

    /**
     * Who hosts the pair's Wi-Fi Direct group once agreed (FR-3.5b): this phone or [peer].
     * Null while the pair has not agreed on Wi-Fi Direct (a decision kept from the last run
     * counts until the peer is heard again).
     */
    @Synchronized
    fun wifiDirectHostOf(peer: MemberId): MemberId? {
        val p = peers[peer]
        val agreed = if (p != null) TransportId.WIFI_DIRECT in agreedSet(peer, p) else restoredAllows(peer)
        return if (agreed) pairHost(peer) else null
    }

    /** The pair's host by the pair alone: the pick one of the two made, else the lower member id. */
    private fun pairHost(peer: MemberId): MemberId = when (peers[peer]?.hostMe) {
        true -> self
        false -> peer
        null -> if (hex(self) < hex(peer)) self else peer
    }

    /**
     * The groups this phone serves (FR-3.5b): every member it can pair with over Wi-Fi
     * Direct meets it in their pair's group — agreed or not yet decided (the handshake runs
     * there), never one that turned it down or does not offer it. The group this phone
     * hosts comes first (shared by every pair that makes it host, each still a pair of its
     * own), then one per member it joins.
     */
    @Synchronized
    fun wifiDirectGroups(): List<WifiDirectGroup> {
        if (myOffers.none { it.t == TransportId.WIFI_DIRECT }) return emptyList()
        val ids = (members + peers.keys + restored.keys).filter { it != self }.sortedBy { hex(it) }
        val pairable = ids.filter { id ->
            val offers = peers[id]?.offers
            !wifiDirectRefused(id) && !restoredDenies(id) &&
                (offers == null || TransportId.WIFI_DIRECT in commonOf(offers))
        }
        val byHost = pairable.groupBy { pairHost(it) }
        val mine = byHost[self]?.let { listOf(WifiDirectGroup(self, it)) }.orEmpty()
        val joins = byHost.filterKeys { it != self }.toSortedMap(compareBy { hex(it) })
            .map { (host, members) -> WifiDirectGroup(host, members) }
        return mine + joins
    }

    /** This user picks who hosts the pair's Wi-Fi Direct group; the peer follows from the next beacon. */
    @Synchronized
    fun pickHost(peer: MemberId, meHosts: Boolean) {
        val p = peers[peer] ?: return
        p.hostMe = meHosts
        p.hostEpoch++
        p.hostAnnounce = SELECT_BEACONS
        urgent = true
    }

    /**
     * The pair settled Wi-Fi Direct and it is not agreed: this user or theirs turned it
     * down. Such a member may not keep a Wi-Fi Direct link with this phone (FR-8.7).
     */
    @Synchronized
    fun wifiDirectRefused(peer: MemberId): Boolean {
        val p = peers[peer] ?: return false
        val offers = p.offers ?: return false
        if (TransportId.WIFI_DIRECT !in commonOf(offers)) return false
        if (TransportId.WIFI_DIRECT in agreedSet(peer, p)) return false
        val e = entry(TransportId.WIFI_DIRECT, offers)
        val mineNo = p.decisions[e] == false
        val theirsNo = p.theirAckH != null && p.theirAckT?.let { TransportId.WIFI_DIRECT !in it } == true
        return mineNo || theirsNo
    }

    private fun restoredAllows(peer: MemberId): Boolean =
        restored[peer]?.any { (k, v) -> v && k.startsWith("${TransportId.WIFI_DIRECT.name}:") } == true

    /** Kept from the last run: this user turned Wi-Fi Direct down for [peer] (until it is heard again). */
    private fun restoredDenies(peer: MemberId): Boolean =
        peer !in peers && restored[peer]?.any { (k, v) -> !v && k.startsWith("${TransportId.WIFI_DIRECT.name}:") } == true

    @Synchronized
    fun common(peer: MemberId): List<TransportId> = peers[peer]?.offers?.let { commonOf(it) } ?: emptyList()

    @Synchronized
    fun agreed(peer: MemberId): Boolean = peers[peer]?.let { agreedSet(peer, it).isNotEmpty() } ?: false

    @Synchronized
    fun link(peer: MemberId): TransportId? = peers[peer]?.let { linkOf(peer, it) }

    @Synchronized
    fun suggest(peer: MemberId): TransportId? = peers[peer]?.let { suggestionOf(peer, it) }

    /** Peers whose selected link is [t], with their address for it. */
    @Synchronized
    fun peersOn(t: TransportId): List<Pair<MemberId, String?>> =
        peers.filter { (id, p) -> linkOf(id, p) == t }.map { (id, p) -> id to p.offers?.firstOrNull { it.t == t }?.addr }

    @Synchronized
    fun all(): List<LinkView> = peers.map { (id, p) ->
        val common = p.offers?.let { commonOf(it) } ?: emptyList()
        val mine = allowedByMe(id, p)
        LinkView(
            id, common, agreedSet(id, p), mine,
            common.filter { t -> p.offers?.let { entry(t, it) }?.let { p.decisions[it] } == false },
            allowedByPeer(id, p), linkOf(id, p), suggestionOf(id, p), p.lastHeardMs,
        )
    }

    /** Canonical hash of the pair's whole common set incl. addresses (an ack allowing all). */
    @Synchronized
    fun hash(peer: MemberId, theirOffers: List<LinkOffer>): Int = ackHash(peer, theirOffers, commonOf(theirOffers))

    /** Canonical hash of [ts] with both addresses each; both sides compute the same. */
    private fun ackHash(peer: MemberId, theirOffers: List<LinkOffer>, ts: List<TransportId>): Int {
        val meFirst = hex(self) < hex(peer)
        val (lo, hi) = if (meFirst) self to peer else peer to self
        val sb = StringBuilder("LNP3|").append(hex(lo)).append('|').append(hex(hi))
        for (t in RUNGS.filter { it in ts }) {
            val mine = myOffers.first { it.t == t }.addr.orEmpty()
            val theirs = theirOffers.first { it.t == t }.addr.orEmpty()
            val (a, b) = if (meFirst) mine to theirs else theirs to mine
            sb.append('|').append(t.name).append(':').append(a).append(':').append(b)
        }
        return CRC32().apply { update(sb.toString().toByteArray()) }.value.toInt()
    }

    private fun commonOf(theirs: List<LinkOffer>): List<TransportId> {
        val mine = myOffers.map { it.t }.toSet()
        val their = theirs.map { it.t }.toSet()
        return RUNGS.filter { it in mine && it in their }
    }

    /** Transports both users allowed, in standard order. */
    private fun agreedSet(id: MemberId, p: Peer): List<TransportId> {
        val theirs = allowedByPeer(id, p)
        return allowedByMe(id, p).filter { it in theirs }
    }

    /** The decision key of a common transport: it and both addresses (null = not common). */
    private fun entry(t: TransportId, theirs: List<LinkOffer>): String? {
        val mine = myOffers.firstOrNull { it.t == t } ?: return null
        val their = theirs.firstOrNull { it.t == t } ?: return null
        return "${t.name}:${mine.addr.orEmpty()}:${their.addr.orEmpty()}"
    }

    private fun allowedByMe(id: MemberId, p: Peer, offers: List<LinkOffer>? = p.offers): List<TransportId> {
        val o = offers ?: return emptyList()
        return commonOf(o).filter { t -> p.decisions[entry(t, o)] == true }
    }

    /** Common transports this user has not decided on yet. */
    private fun undecided(id: MemberId, p: Peer): List<TransportId> {
        val o = p.offers ?: return emptyList()
        return commonOf(o).filter { t -> entry(t, o) !in p.decisions }
    }

    /** What the peer's user allowed, when their ack matches what this phone sees. */
    private fun allowedByPeer(id: MemberId, p: Peer): List<TransportId> {
        val o = p.offers ?: return emptyList()
        val h = p.theirAckH ?: return emptyList()
        val common = commonOf(o)
        val ts = p.theirAckT ?: common
        if (!common.containsAll(ts) || ackHash(id, o, ts) != h) return emptyList()
        return common.filter { it in ts }
    }

    /** Explicit selection while it is still in the agreed set, else the cheapest agreed one. */
    private fun linkOf(id: MemberId, p: Peer): TransportId? {
        val set = agreedSet(id, p)
        return p.selected?.takeIf { it in set } ?: set.firstOrNull()
    }

    /** Heard directly within three beacon intervals (the staleness of a peer). */
    private fun heardLately(p: Peer, now: Long) = p.lastHeardMs > 0 && now - p.lastHeardMs <= beaconIntervalMs * 3

    private fun window(t: TransportId) = if (t == TransportId.SMS) smsIntervalMs else beaconIntervalMs

    private fun suggestionOf(id: MemberId, p: Peer): TransportId? {
        val link = linkOf(id, p) ?: return null
        val now = nowMs()
        val quiet = (p.heard[link]?.let { now - it } ?: Long.MAX_VALUE) > window(link) * 3 / 2 &&
            now - p.selectedAtMs > window(link) * 3 / 2
        if (!quiet) return null
        return agreedSet(id, p).firstOrNull { RUNGS.indexOf(it) > RUNGS.indexOf(link) }
    }

    private fun changed(id: MemberId, p: Peer): Switch? {
        val now = linkOf(id, p)
        if (now == p.shown) return null
        val sw = Switch(id, p.shown, now)
        p.shown = now
        return sw
    }

    private fun hex(id: MemberId) = id.bytes.joinToString("") { "%02x".format(it) }
}
