package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkAck
import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.TransportId
import java.util.zip.CRC32

/**
 * LNP/1, the Link Negotiation Protocol (spec FR-3.5–3.10). Pure Kotlin.
 *
 * - Offers: each member lists the transports it can use (and how to reach it there, e.g.
 *   an SMS number) inside its encrypted, signed beacons ([outgoing] / [onFrame]).
 * - Agreement: both sides derive the same *ladder* — standard rung order filtered to what
 *   both offer — and acknowledge it by hash. Costly rungs are used only once agreed.
 * - Switching: per peer the active rung is the cheapest one heard on directly and recently.
 *   When every radio rung is silent, both sides step down to the next agreed costly rung
 *   on their own (there is no channel left to coordinate on); any direct frame on a
 *   cheaper rung switches back up at once. A peer still heard through relays has no
 *   active rung of its own (null) and costs nothing.
 */
class LinkNegotiator(
    private val self: MemberId,
    private val nowMs: () -> Long,
    /** Beacon cadence: radio rungs are alive while heard within 3 of these. */
    private val beaconIntervalMs: Long,
    /** SMS report cadence: the SMS rung is alive while heard within 2 of these. */
    private val smsIntervalMs: Long = 30 * 60_000L,
) {
    companion object {
        /** Standard rung order, cheapest and fastest first. */
        val RUNGS = listOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT, TransportId.LORA, TransportId.SMS)

        /** Broadcast radios: free, used whether agreed or not. */
        val FREE = setOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT)

        /** Offers ride on every Nth beacon even when nothing changed (late joiners, lost frames). */
        const val OFFERS_EVERY = 5

        fun prefix(id: MemberId): ByteArray = id.bytes.copyOf(4)
    }

    /** One switch of a peer's active rung (for the log and the UI). */
    data class Switch(val peer: MemberId, val from: TransportId?, val to: TransportId?)

    /** Read-only view of one peer link. */
    data class LinkView(
        val peer: MemberId,
        val ladder: List<TransportId>,
        val agreed: Boolean,
        val active: TransportId?,
        val lastHeardMs: Long,
    )

    private class Peer {
        var offers: List<LinkOffer>? = null
        var theirAck: Int? = null
        val heard = HashMap<TransportId, Long>()
        var lastHeardMs = 0L
        /** Last frame of any kind, relayed ones included. */
        var lastAnyMs = 0L
        var active: TransportId? = null
        var activeSinceMs = 0L
    }

    private val peers = HashMap<MemberId, Peer>()
    private var myOffers: List<LinkOffer> = emptyList()
    private var dirty = true
    private var beaconCount = 0
    /** Hash we last acknowledged per peer; a change makes the next beacon carry acks. */
    private val ackSent = HashMap<MemberId, Int>()

    /** What this phone can offer right now; a change is announced on the next beacon. */
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

    private var myName: String? = null

    /** Display name announced with the offers; a change is announced on the next beacon. */
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
        val acks = peers.mapNotNull { (id, p) -> p.offers?.let { LinkAck(prefix(id), hash(id, it)) to id } }
        val pending = acks.any { (ack, id) -> ackSent[id] != ack.h || peers[id]?.theirAck != ack.h }
        if (!dirty && !pending && beaconCount % OFFERS_EVERY != 0) return null
        dirty = false
        acks.forEach { (ack, id) -> ackSent[id] = ack.h }
        return LinkCtl(offers = myOffers, acks = acks.map { it.first }, name = myName)
    }

    /**
     * An authenticated frame from [peer] arrived on [source]. [direct] = not relayed (TTL
     * intact): only that counts as hearing the peer on that rung. [ctl] may come relayed:
     * it is signed by the peer, so its offers are still the peer's own.
     */
    @Synchronized
    fun onFrame(peer: MemberId, source: TransportId, direct: Boolean, ctl: LinkCtl?): List<Switch> {
        if (peer == self) return emptyList()
        val p = peers.getOrPut(peer) { Peer() }
        if (ctl != null && ctl.v == 1) {
            p.offers = ctl.offers.filter { it.t in RUNGS }.distinctBy { it.t }
            p.theirAck = ctl.acks.firstOrNull { it.p.contentEquals(prefix(self)) }?.h
        }
        val now = nowMs()
        p.lastAnyMs = now
        if (direct && source in RUNGS) {
            p.heard[source] = now
            p.lastHeardMs = now
        }
        return listOfNotNull(reselect(peer, p))
    }

    /** Re-evaluates every peer (call every few seconds); silent rungs step down here. */
    @Synchronized
    fun tick(): List<Switch> = peers.mapNotNull { (id, p) -> reselect(id, p) }

    @Synchronized
    fun ladder(peer: MemberId): List<TransportId> = peers[peer]?.offers?.let { ladderOf(it) } ?: emptyList()

    @Synchronized
    fun agreed(peer: MemberId): Boolean = peers[peer]?.let { isAgreed(peer, it) } ?: false

    @Synchronized
    fun active(peer: MemberId): TransportId? = peers[peer]?.active

    /** Peers whose active rung is [t] on an agreed ladder, with their address for it. */
    @Synchronized
    fun peersOn(t: TransportId): List<Pair<MemberId, String?>> = peers.filter { (id, p) ->
        p.active == t && isAgreed(id, p)
    }.map { (id, p) -> id to p.offers?.firstOrNull { it.t == t }?.addr }

    /** Every agreed address for [t], whatever rung the peer is on. */
    @Synchronized
    fun agreedAddresses(t: TransportId): List<Pair<MemberId, String>> = peers.mapNotNull { (id, p) ->
        val addr = p.offers?.firstOrNull { it.t == t }?.addr
        if (addr != null && isAgreed(id, p) && t in ladderOf(p.offers!!)) id to addr else null
    }

    @Synchronized
    fun all(): List<LinkView> = peers.map { (id, p) ->
        LinkView(id, p.offers?.let { ladderOf(it) } ?: emptyList(), isAgreed(id, p), p.active, p.lastHeardMs)
    }

    /** Canonical ladder hash for the pair (self, peer); both sides compute the same value. */
    @Synchronized
    fun hash(peer: MemberId, theirOffers: List<LinkOffer>): Int {
        val meFirst = hex(self) < hex(peer)
        val sb = StringBuilder("LNP1")
        val (lo, hi) = if (meFirst) self to peer else peer to self
        sb.append('|').append(hex(lo)).append('|').append(hex(hi))
        for (t in ladderOf(theirOffers)) {
            val mine = myOffers.first { it.t == t }.addr.orEmpty()
            val theirs = theirOffers.first { it.t == t }.addr.orEmpty()
            val (a, b) = if (meFirst) mine to theirs else theirs to mine
            sb.append('|').append(t.name).append(':').append(a).append(':').append(b)
        }
        return CRC32().apply { update(sb.toString().toByteArray()) }.value.toInt()
    }

    private fun ladderOf(theirs: List<LinkOffer>): List<TransportId> {
        val mine = myOffers.map { it.t }.toSet()
        val their = theirs.map { it.t }.toSet()
        return RUNGS.filter { it in mine && it in their }
    }

    private fun isAgreed(id: MemberId, p: Peer): Boolean {
        val offers = p.offers ?: return false
        return ladderOf(offers).isNotEmpty() && p.theirAck == hash(id, offers)
    }

    private fun window(t: TransportId) = if (t == TransportId.SMS) 2 * smsIntervalMs else 3 * beaconIntervalMs

    private fun alive(p: Peer, t: TransportId, now: Long): Boolean =
        p.heard[t]?.let { now - it < window(t) } ?: false

    private fun reselect(id: MemberId, p: Peer): Switch? {
        val now = nowMs()
        val ladder = p.offers?.let { ladderOf(it) } ?: emptyList()
        // Radios always count when heard, even on rungs not (yet) in the ladder.
        val heardRung = RUNGS.firstOrNull { (it in FREE || it in ladder) && alive(p, it, now) }
        val next: TransportId? = when {
            heardRung != null && (heardRung in FREE || isAgreed(id, p)) -> heardRung
            !isAgreed(id, p) -> null
            // Still reached through other members' relays: no reason to pay for SMS.
            now - p.lastAnyMs < 3 * beaconIntervalMs -> null
            else -> {
                // Nothing heard: walk down the agreed costly rungs, giving each one its
                // liveness window before moving on (LoRa first, then SMS).
                val costly = ladder.filter { it !in FREE }
                val cur = p.active
                when {
                    costly.isEmpty() -> null
                    cur == null || cur in FREE -> costly.first()
                    now - p.activeSinceMs >= window(cur) && cur != costly.last() ->
                        costly[costly.indexOf(cur) + 1]
                    else -> cur
                }
            }
        }
        if (next == p.active) return null
        val sw = Switch(id, p.active, next)
        p.active = next
        p.activeSinceMs = now
        return sw
    }

    private fun hex(id: MemberId) = id.bytes.joinToString("") { "%02x".format(it) }
}
