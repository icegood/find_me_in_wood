package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkAck
import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkSel
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.TransportId
import java.util.zip.CRC32

/**
 * LNP/3, the Link Negotiation Protocol (spec FR-3.5–3.10). Pure Kotlin.
 *
 * - Handshake, once per pair: both list the transports they can use (an SMS offer carries
 *   the number) and acknowledge the common set by hash. Only an agreed set is usable.
 * - Selection, any time, no further handshake: either side picks a transport within the
 *   agreed set ([select]); it applies at once and is announced in the next beacons, where
 *   the peer applies it too. The higher selection epoch is current; equal: lower id wins.
 * - Default: once agreed, both start on the cheapest common transport — computed the same
 *   on both sides, so nothing needs to be sent.
 * - No fallback: nothing switches by itself. When the selected link goes quiet the
 *   negotiator only [suggest]s the next agreed transport; switching is the user's choice.
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
        /** Transports both offer, in standard order. */
        val common: List<TransportId>,
        /** Handshake done: the peer acknowledged the same set. */
        val agreed: Boolean,
        /** Selected link; null until agreed. */
        val link: TransportId?,
        /** Next agreed transport when the selected link is quiet; never applied by itself. */
        val suggestion: TransportId?,
        val lastHeardMs: Long,
    )

    private class Peer {
        var offers: List<LinkOffer>? = null
        var theirAck: Int? = null
        val heard = HashMap<TransportId, Long>()
        var lastHeardMs = 0L
        /** Explicit selection (from either side); null = the default. */
        var selected: TransportId? = null
        var epoch = 0
        var selectedAtMs = 0L
        var announce = 0
        var shown: TransportId? = null
    }

    private val peers = HashMap<MemberId, Peer>()
    private var myOffers: List<LinkOffer> = emptyList()
    private var myName: String? = null
    private var dirty = true
    private var beaconCount = 0
    private val ackSent = HashMap<MemberId, Int>()
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
        val acks = peers.mapNotNull { (id, p) -> p.offers?.let { id to LinkAck(prefix(id), hash(id, it)) } }
        val handshaking = acks.any { (id, ack) -> ackSent[id] != ack.h || peers[id]?.theirAck != ack.h }
        val sels = peers.filter { (_, p) -> p.announce > 0 && p.selected != null }
        if (!dirty && !handshaking && sels.isEmpty() && beaconCount % OFFERS_EVERY != 0) return null
        dirty = false
        urgent = false
        acks.forEach { (id, ack) -> ackSent[id] = ack.h }
        sels.values.forEach { it.announce-- }
        return LinkCtl(
            offers = myOffers,
            acks = acks.map { it.second },
            sel = sels.map { (id, p) -> LinkSel(prefix(id), p.selected!!, p.epoch) },
            name = myName,
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
        val p = peers.getOrPut(peer) { Peer() }
        if (direct && source in RUNGS) {
            val now = nowMs()
            p.heard[source] = now
            p.lastHeardMs = now
        }
        if (ctl != null && ctl.v == 3) {
            val offers = ctl.offers.filter { it.t in RUNGS }.distinctBy { it.t }
            val theirAck = ctl.acks.firstOrNull { it.p.contentEquals(prefix(self)) }?.h
            // New offers need our ack; a missing/wrong ack means they lack our offers or ack.
            if (offers != p.offers || theirAck != hash(peer, offers) || ackSent[peer] != hash(peer, offers)) urgent = true
            p.offers = offers
            p.theirAck = theirAck
            ctl.sel.firstOrNull { it.p.contentEquals(prefix(self)) }?.let { s ->
                val newer = s.e > p.epoch || s.e == p.epoch && s.t != p.selected && hex(peer) < hex(self)
                if (newer && s.t in agreedSet(peer, p)) {
                    p.selected = s.t
                    p.epoch = s.e
                    p.selectedAtMs = nowMs()
                }
            }
        }
        return listOfNotNull(changed(peer, p))
    }

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
        LinkView(
            id, p.offers?.let { commonOf(it) } ?: emptyList(), agreedSet(id, p).isNotEmpty(),
            linkOf(id, p), suggestionOf(id, p), p.lastHeardMs,
        )
    }

    /** Canonical hash of the pair's common set incl. addresses; both sides compute the same. */
    @Synchronized
    fun hash(peer: MemberId, theirOffers: List<LinkOffer>): Int {
        val meFirst = hex(self) < hex(peer)
        val (lo, hi) = if (meFirst) self to peer else peer to self
        val sb = StringBuilder("LNP3|").append(hex(lo)).append('|').append(hex(hi))
        for (t in commonOf(theirOffers)) {
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

    private fun agreedSet(id: MemberId, p: Peer): List<TransportId> {
        val offers = p.offers ?: return emptyList()
        return if (p.theirAck == hash(id, offers)) commonOf(offers) else emptyList()
    }

    /** Explicit selection while it is still in the agreed set, else the cheapest agreed one. */
    private fun linkOf(id: MemberId, p: Peer): TransportId? {
        val set = agreedSet(id, p)
        return p.selected?.takeIf { it in set } ?: set.firstOrNull()
    }

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
