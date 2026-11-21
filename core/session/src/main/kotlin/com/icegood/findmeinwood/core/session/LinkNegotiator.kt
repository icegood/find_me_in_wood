package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.WifiBand
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
 * - Wi-Fi Direct is per pair too ([wifiDirectHostOf]), only once the pair agreed on it: of
 *   the two, the network's owner hosts, else the lower member id, unless the pair picked the
 *   other way round ([pickHost], announced like a selection). This phone serves one group
 *   it hosts and one per member it joins ([wifiDirectGroups]); the radio takes them in turn.
 * - Subgroups: every pair that makes one phone host shares its one group, and the host
 *   relays between them. Two members both surely in one host's group (a picked host, or the
 *   owner by default; announced as [LinkCtl.hubs]) meet there and get no group of their own
 *   ([pairGroupNeeded]); picked hosts win over the owner's default.
 * - Bluetooth is the control channel for the handshake: offers and acknowledgements count
 *   only over Bluetooth until the pair agreed on something. Joining and the first handshake
 *   need it; afterwards Bluetooth may be off, the agreed links carry on (and their link
 *   control) over any radio. Wi-Fi Direct carries no rendezvous: there is no group for
 *   members that have not agreed yet.
 * - The host says the channel its group really runs on ([setHostedFrequency]); its joiners
 *   scan only that one ([frequencyOf]).
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

        /** The id an owner not learned yet goes by (as in NetworkProfile). */
        val OWNER_UNKNOWN = MemberId(ByteArray(32))

        /** Decision key of the pair's Wi-Fi Direct host pick (true = this phone hosts), kept across runs. */
        const val HOST_KEY = "host"

        /** At most this long without hearing a member before the pair forms its own group. */
        const val PAIR_QUIET_MAX_MS = 60_000L
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
        /** Radios that carried a frame straight from the peer lately (not relayed), in standard order. */
        val heard: List<TransportId> = emptyList(),
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
        /** Band of the Wi-Fi Direct group the peer hosts, as it last said. */
        var band: WifiBand? = null
        /** Channel (MHz) of the group the peer hosts, as it last said; null = not hosting. */
        var freq: Int? = null
        /** Last frame from the peer by any path, relayed ones too. */
        var lastAnyMs = 0L
        /** Hosts of the Wi-Fi Direct groups the peer said it is surely in (hex prefix -> picked). */
        var hubs: Map<String, Boolean> = emptyMap()
        /** The pair agreed on something this run: its link control is taken over any radio. */
        var agreedOnce = false
    }

    /** Band of the Wi-Fi Direct group this phone hosts (the user's choice). */
    private var myBand = WifiBand.GHZ_2_4

    /** This user picked the band of the group this phone hosts; its joiners hear it at once. */
    @Synchronized
    fun setBand(band: WifiBand) {
        if (band == myBand) return
        myBand = band
        dirty = true
        urgent = true
    }

    @Synchronized
    fun band(): WifiBand = myBand

    /** Band of the group [peer] hosts, as it said; null until heard. */
    @Synchronized
    fun bandOf(peer: MemberId): WifiBand? = peers[peer]?.band

    /** Channel (MHz) of the group this phone hosts now; null = none formed. */
    private var myFreq: Int? = null

    /** The group this phone hosts formed on [mhz] (null: none now); its joiners hear it at once. */
    @Synchronized
    fun setHostedFrequency(mhz: Int?) {
        if (mhz == myFreq) return
        myFreq = mhz
        dirty = true
        urgent = true
    }

    /** Channel (MHz) of the group [peer] hosts, as it said; null = not known. */
    @Synchronized
    fun frequencyOf(peer: MemberId): Int? = peers[peer]?.freq

    private val peers = HashMap<MemberId, Peer>()
    private var myOffers: List<LinkOffer> = emptyList()
    private var myName: String? = null
    private var dirty = true
    private var beaconCount = 0
    private val ackSent = HashMap<MemberId, Int>()
    private val echoSent = HashMap<MemberId, Int?>()
    /** The peer is waiting for our answer (handshake step or a selection to announce). */
    private var urgent = false
    /** [LinkCtl.hubs] as last announced. */
    private var hubsSent: Map<String, Boolean> = emptyMap()

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
        val hubs = anchors().mapKeys { (h, _) -> hexOf(prefix(h)) }
        if (hubs != hubsSent) { dirty = true; urgent = true }
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
        // Every pick rides on every link control, not only its first beacons: a pair out of
        // touch then (no common radio for a while) would never learn it.
        // An answer the peer waits for always goes out (and clears [urgent]), else every
        // frame heard meanwhile would ask for one more extra beacon.
        if (!dirty && !urgent && !handshaking && sels.isEmpty() && hostPicks.isEmpty() &&
            beaconCount % OFFERS_EVERY != 0
        ) return null
        dirty = false
        urgent = false
        hubsSent = hubs
        acks.forEach { (id, ack) -> ackSent[id] = ack.h; echoSent[id] = ack.e }
        sels.values.forEach { it.announce-- }
        hostPicks.values.forEach { it.hostAnnounce-- }
        return LinkCtl(
            offers = myOffers,
            acks = acks.map { it.second },
            sel = sels.map { (id, p) -> LinkSel(prefix(id), p.selected!!, p.epoch) },
            name = myName,
            hosts = peers.filter { (_, p) -> p.hostMe != null }.map { (id, p) -> LinkHost(prefix(id), p.hostMe!!, p.hostEpoch) },
            band = myBand,
            freq = myFreq,
            hubs = anchors().map { (h, picked) -> com.icegood.findmeinwood.core.model.LinkHub(prefix(h), picked) },
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
        p.lastAnyMs = nowMs()
        if (direct && source in RUNGS) {
            val now = nowMs()
            p.heard[source] = now
            p.lastHeardMs = now
        }
        if (ctl != null && ctl.v == 3) {
            ctl.band?.let { p.band = it }
            p.freq = ctl.freq
            p.hubs = ctl.hubs.associate { hexOf(it.p) to it.picked }
        }
        // The handshake runs over Bluetooth: elsewhere only for a pair that agreed before.
        if (agreedSet(peer, p).isNotEmpty()) p.agreedOnce = true
        val handshake = source == TransportId.BLUETOOTH || p.agreedOnce || restoredAllowsAny(peer)
        if (ctl != null && ctl.v == 3 && handshake) {
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
                    val changed = p.hostMe != meHosts
                    p.hostMe = meHosts
                    p.hostEpoch = h.e
                    if (changed) onDecided?.invoke() // kept across restarts, as this user's own
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
        restored + peers.filterValues { it.decisions.isNotEmpty() || it.hostMe != null }.mapValues { (_, p) ->
            HashMap(p.decisions).apply { p.hostMe?.let { put(HOST_KEY, it) } }
        }

    private fun seed(id: MemberId, p: Peer) {
        val saved = restored[id] ?: return
        saved.forEach { (k, v) -> if (k != HOST_KEY) p.decisions.putIfAbsent(k, v) }
        // The pick of the last run holds; a newer one from the peer wins (higher epoch).
        saved[HOST_KEY]?.let { if (p.hostMe == null) { p.hostMe = it; p.hostEpoch = maxOf(p.hostEpoch, 1) } }
        if (saved.keys.any { it != HOST_KEY }) { p.acking = true; dirty = true }
    }

    /** This user allowed some transport for [peer] in an earlier run. */
    private fun restoredAllowsAny(peer: MemberId): Boolean = restored[peer]?.any { (k, v) -> v && k != HOST_KEY } == true

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

    /**
     * [peer]'s link control counts only over Bluetooth for now (nothing agreed yet): a
     * frame's Bluetooth copy brings it even when another radio's copy came first. Taking
     * the same link control twice changes nothing.
     */
    @Synchronized
    fun handshakeOnBluetoothOnly(peer: MemberId): Boolean =
        peers[peer]?.agreedOnce != true && !restoredAllowsAny(peer)

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

    /** The network's members (its member list). */
    private var members: Set<MemberId> = emptySet()

    /** The network's owner (master); null while this phone has not learned it. */
    private var owner: MemberId? = null

    @Synchronized
    fun setMembers(ids: Collection<MemberId>) {
        members = ids.toSet() - self
    }

    /** The network's owner, or null / [OWNER_UNKNOWN] while this phone has not learned it. */
    @Synchronized
    fun setOwner(id: MemberId?) {
        owner = id?.takeIf { it != OWNER_UNKNOWN }
    }

    /**
     * Who hosts the pair's Wi-Fi Direct group once agreed (FR-3.5b): this phone or [peer].
     * Null while the pair has not agreed on Wi-Fi Direct (a decision kept from the last run
     * counts until the peer is heard again).
     */
    @Synchronized
    fun wifiDirectHostOf(peer: MemberId): MemberId? = if (wifiDirectAgreed(peer)) pairHost(peer) else null

    /** The pair agreed on Wi-Fi Direct; before its link control is heard, as decided last run. */
    private fun wifiDirectAgreed(peer: MemberId): Boolean {
        val p = peers[peer]
        return if (p?.offers != null) TransportId.WIFI_DIRECT in agreedSet(peer, p) else restoredAllows(peer)
    }

    /**
     * The pair's host by the pair alone: the pick one of the two made, else the owner when it
     * is one of the two, else the lower member id.
     */
    private fun pairHost(peer: MemberId): MemberId = when (peers[peer]?.hostMe) {
        true -> self
        false -> peer
        null -> when (owner) {
            self -> self
            peer -> peer
            else -> if (hex(self) < hex(peer)) self else peer
        }
    }

    /**
     * The groups this phone serves (FR-3.5b): one per pair agreed on Wi-Fi Direct that needs
     * its own ([pairGroupNeeded]). The group this phone hosts is shared by every pair that
     * makes it host (each still a pair of its own); then one per member it joins. Groups it
     * is surely in ([anchors]) come first, picked ones before the owner's.
     */
    @Synchronized
    fun wifiDirectGroups(): List<WifiDirectGroup> {
        if (myOffers.none { it.t == TransportId.WIFI_DIRECT }) return emptyList()
        val ids = (members + peers.keys + restored.keys).filter { it != self }.distinct().sortedBy { hex(it) }
        val byHost = ids.filter { wifiDirectAgreed(it) && pairGroupNeeded(it) }.groupBy { pairHost(it) }
        val mine = byHost[self]?.let { listOf(WifiDirectGroup(self, it)) }.orEmpty()
        val joins = byHost.filterKeys { it != self }.toSortedMap(compareBy { hex(it) })
            .map { (host, members) -> WifiDirectGroup(host, members) }
        val anchored = anchors()
        return (mine + joins).sortedByDescending { g -> anchored[g.host]?.let { if (it) 2 else 1 } ?: 0 }
    }

    /**
     * The groups this phone is surely in, by host (true = a user picked that host): every
     * pair agreed on Wi-Fi Direct whose host was picked, and every pair with the owner (its
     * default host) unless both are in a picked group anyway. Announced in [LinkCtl.hubs].
     */
    private fun anchors(): Map<MemberId, Boolean> {
        if (myOffers.none { it.t == TransportId.WIFI_DIRECT }) return emptyMap()
        val agreed = (peers.keys + restored.keys).filter { it != self && wifiDirectAgreed(it) }
        val picked = agreed.filter { peers[it]?.hostMe != null }.associate { pairHost(it) to true }
        val owners = agreed.filter { (it == owner || self == owner) && peers[it]?.hostMe == null }
            .filter { !meetIn(it, picked.keys, pickedOnly = true) }
            .associate { pairHost(it) to false }
        return owners + picked
    }

    /** [peer], heard lately, said it is surely in the group of a host in [hosts] (not one of the two). */
    private fun meetIn(peer: MemberId, hosts: Collection<MemberId>, pickedOnly: Boolean): Boolean {
        val p = peers[peer] ?: return false
        if (p.lastAnyMs == 0L || nowMs() - p.lastAnyMs > pairQuietMs()) return false
        return hosts.any { h -> h != self && h != peer && p.hubs[hexOf(prefix(h))]?.let { !pickedOnly || it } == true }
    }

    /**
     * Whether this phone and [peer] (agreed on Wi-Fi Direct) need a group of their own: not
     * when both are surely in one host's group, where that host relays between them. A
     * picked host always gets its group; the owner's pairs too, unless both members are in a
     * picked group; other pairs meet in a group both are in, and wait in this phone's own
     * group for a member not heard yet. One heard before and silent since is out of the
     * shared host's reach: the pair meets on its own.
     */
    private fun pairGroupNeeded(peer: MemberId): Boolean {
        val p = peers[peer]
        if (p?.hostMe != null) return true
        val anchored = anchors()
        val picked = anchored.filterValues { it }.keys
        if (peer == owner || self == owner) return !meetIn(peer, picked, pickedOnly = true)
        if (p == null || p.lastAnyMs == 0L) return anchored.isEmpty()
        return !meetIn(peer, anchored.keys, pickedOnly = false)
    }

    /** Silence after which two members stop counting on a shared host's group to meet. */
    private fun pairQuietMs() = minOf(3 * beaconIntervalMs, PAIR_QUIET_MAX_MS)

    /** This user picks who hosts the pair's Wi-Fi Direct group; the peer follows from the next beacon. */
    @Synchronized
    fun pickHost(peer: MemberId, meHosts: Boolean) {
        val p = peers[peer] ?: return
        p.hostMe = meHosts
        p.hostEpoch++
        p.hostAnnounce = SELECT_BEACONS
        urgent = true
        onDecided?.invoke()
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
            heardOn(p),
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

    /** Radios that heard [p] directly within the window its link would call quiet. */
    private fun heardOn(p: Peer): List<TransportId> {
        val now = nowMs()
        return RUNGS.filter { t -> p.heard[t]?.let { now - it <= window(t) * 3 / 2 } == true }
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

    private fun hex(id: MemberId) = hexOf(id.bytes)

    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
