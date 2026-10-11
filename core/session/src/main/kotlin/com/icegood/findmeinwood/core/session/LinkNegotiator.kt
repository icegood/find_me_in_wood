package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.LinkCtl
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkProp
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.TransportId

/**
 * LNP/2, the Link Negotiation Protocol (spec FR-3.5–3.10). Pure Kotlin.
 *
 * The transport of each pair's link is explicit, agreed state: it changes only through a
 * PROPOSE / ACCEPT exchange carried in the peers' encrypted, signed beacons. There is no
 * fallback — nothing ever switches on its own because a link went quiet.
 *
 * - Offers: each member lists the transports it can use (an SMS offer carries its number).
 * - Proposals: a phone proposes a transport both offer — on first contact (the rung the
 *   peer is heard on), when the current link weakens (a missed beacon: the next rung),
 *   or when a cheaper radio hears the peer directly again (that rung).
 * - Acceptance: the peer accepts if it offers that transport too; both sides then use it.
 *   A proposal travels over whatever still reaches the peer (the weak link, a relay);
 *   if nothing does, it expires and the link stays as it was.
 * - Conflicts: higher epoch wins; equal epochs: the lower member id's proposal wins.
 */
class LinkNegotiator(
    private val self: MemberId,
    private val nowMs: () -> Long,
    /** Beacon cadence; one missed beacon on the current link = weak. */
    private val beaconIntervalMs: Long,
    /** SMS report cadence; one missed report on an SMS link = weak. */
    private val smsIntervalMs: Long = 30 * 60_000L,
) {
    companion object {
        /** Standard rung order, cheapest and fastest first. */
        val RUNGS = listOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT, TransportId.LORA, TransportId.SMS)

        /** Offers ride on every Nth beacon even when nothing changed (late joiners, lost frames). */
        const val OFFERS_EVERY = 5

        /** A proposal is repeated on this many beacons, then dropped if not accepted. */
        const val PROPOSE_BEACONS = 3

        /** An acceptance is repeated on this many beacons (the first may be lost). */
        const val ACCEPT_BEACONS = 3

        fun prefix(id: MemberId): ByteArray = id.bytes.copyOf(4)
    }

    /** The agreed link to a peer changed (for the log and the UI). */
    data class Switch(val peer: MemberId, val from: TransportId?, val to: TransportId?)

    /** Read-only view of one peer link. */
    data class LinkView(
        val peer: MemberId,
        /** Transports both sides offer, in rung order. */
        val common: List<TransportId>,
        /** Agreed link; null until a first proposal was accepted. */
        val link: TransportId?,
        /** Our outstanding proposal, if any. */
        val proposing: TransportId?,
        val lastHeardMs: Long,
    )

    private class Pending(val t: TransportId, val e: Int, var beaconsLeft: Int)

    private class Peer {
        var offers: List<LinkOffer>? = null
        val heard = HashMap<TransportId, Long>()
        var lastHeardMs = 0L
        var link: TransportId? = null
        var epoch = 0
        var linkSinceMs = 0L
        var proposal: Pending? = null
        var acceptance: Pending? = null
    }

    private val peers = HashMap<MemberId, Peer>()
    private var myOffers: List<LinkOffer> = emptyList()
    private var myName: String? = null
    private var dirty = true
    private var beaconCount = 0

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
        val props = peers.mapNotNull { (id, p) -> p.proposal?.let { id to it } }
        val accs = peers.mapNotNull { (id, p) -> p.acceptance?.let { id to it } }
        if (!dirty && props.isEmpty() && accs.isEmpty() && beaconCount % OFFERS_EVERY != 0) return null
        dirty = false
        val ctl = LinkCtl(
            offers = myOffers,
            prop = props.map { (id, pr) -> LinkProp(prefix(id), pr.t, pr.e) },
            acc = accs.map { (id, a) -> LinkProp(prefix(id), a.t, a.e) },
            name = myName,
        )
        // Count down repeats; an unanswered proposal expires and the link stays as it was.
        props.forEach { (id, pr) -> if (--pr.beaconsLeft <= 0) peers[id]?.proposal = null }
        accs.forEach { (id, a) -> if (--a.beaconsLeft <= 0) peers[id]?.acceptance = null }
        return ctl
    }

    /**
     * An authenticated frame from [peer] arrived on [source]. [direct] = not relayed (TTL
     * intact): only that counts as hearing the peer on that rung. [ctl] may come relayed:
     * it is signed by the peer, so its proposals and acceptances are still the peer's own.
     */
    @Synchronized
    fun onFrame(peer: MemberId, source: TransportId, direct: Boolean, ctl: LinkCtl?): List<Switch> {
        if (peer == self) return emptyList()
        val p = peers.getOrPut(peer) { Peer() }
        val out = mutableListOf<Switch>()
        if (direct && source in RUNGS) {
            val now = nowMs()
            p.heard[source] = now
            p.lastHeardMs = now
        }
        if (ctl != null && ctl.v == 2) {
            p.offers = ctl.offers.filter { it.t in RUNGS }.distinctBy { it.t }
            ctl.acc.firstOrNull { it.p.contentEquals(prefix(self)) }?.let { a ->
                val mine = p.proposal
                if (mine != null && mine.t == a.t && mine.e == a.e) {
                    p.proposal = null
                    out += setLink(peer, p, a.t, a.e)
                }
            }
            ctl.prop.firstOrNull { it.p.contentEquals(prefix(self)) }?.let { pr ->
                onProposal(peer, p, pr)?.let { out += it }
            }
        }
        maybePropose(peer, p)
        return out
    }

    /** Raises proposals that are due (weak link, cheaper radio back). Never switches by itself. */
    @Synchronized
    fun tick() {
        peers.forEach { (id, p) -> maybePropose(id, p) }
    }

    @Synchronized
    fun common(peer: MemberId): List<TransportId> = peers[peer]?.offers?.let { commonOf(it) } ?: emptyList()

    @Synchronized
    fun link(peer: MemberId): TransportId? = peers[peer]?.link

    @Synchronized
    fun proposing(peer: MemberId): TransportId? = peers[peer]?.proposal?.t

    /** Peers whose agreed link is [t], with their address for it. */
    @Synchronized
    fun peersOn(t: TransportId): List<Pair<MemberId, String?>> =
        peers.filter { (_, p) -> p.link == t }.map { (id, p) -> id to p.offers?.firstOrNull { it.t == t }?.addr }

    @Synchronized
    fun all(): List<LinkView> = peers.map { (id, p) ->
        LinkView(id, p.offers?.let { commonOf(it) } ?: emptyList(), p.link, p.proposal?.t, p.lastHeardMs)
    }

    private fun commonOf(theirs: List<LinkOffer>): List<TransportId> {
        val mine = myOffers.map { it.t }.toSet()
        val their = theirs.map { it.t }.toSet()
        return RUNGS.filter { it in mine && it in their }
    }

    private fun onProposal(id: MemberId, p: Peer, pr: LinkProp): Switch? {
        if (pr.e <= p.epoch) return null // stale or already applied
        val offers = p.offers ?: return null
        if (pr.t !in commonOf(offers)) return null // we cannot use that transport: no answer
        val mine = p.proposal
        // Both proposed at once: the higher epoch, then the lower member id, wins.
        if (mine != null && (mine.e > pr.e || mine.e == pr.e && hex(self) < hex(id))) return null
        p.proposal = null
        p.acceptance = Pending(pr.t, pr.e, ACCEPT_BEACONS)
        return setLink(id, p, pr.t, pr.e)
    }

    private fun setLink(id: MemberId, p: Peer, t: TransportId, e: Int): Switch {
        val sw = Switch(id, p.link, t)
        p.link = t
        p.epoch = e
        p.linkSinceMs = nowMs()
        return sw
    }

    private fun window(t: TransportId) = if (t == TransportId.SMS) smsIntervalMs else beaconIntervalMs

    /** Heard directly on [t] recently: within one and a half of its cadence. */
    private fun fresh(p: Peer, t: TransportId, now: Long) =
        p.heard[t]?.let { now - it <= window(t) * 3 / 2 } ?: false

    private fun maybePropose(id: MemberId, p: Peer) {
        if (p.proposal != null || p.acceptance != null) return
        val common = p.offers?.let { commonOf(it) } ?: return
        if (common.isEmpty()) return
        val now = nowMs()
        val cur = p.link
        val cheaperBack = cur?.let { c -> common.firstOrNull { RUNGS.indexOf(it) < RUNGS.indexOf(c) && fresh(p, it, now) } }
        val target: TransportId? = when {
            // First contact: agree on the rung the peer is heard on directly.
            cur == null -> common.firstOrNull { fresh(p, it, now) }
            // A cheaper radio hears the peer directly again: propose going back to it.
            cheaperBack != null -> cheaperBack
            // Current link weak (a missed beacon or report on it): propose the next rung.
            !fresh(p, cur, now) && now - p.linkSinceMs > window(cur) * 3 / 2 ->
                common.firstOrNull { RUNGS.indexOf(it) > RUNGS.indexOf(cur) }
            else -> null
        }
        if (target != null && target != cur) p.proposal = Pending(target, p.epoch + 1, PROPOSE_BEACONS)
    }

    private fun hex(id: MemberId) = id.bytes.joinToString("") { "%02x".format(it) }
}
