package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.TransportId

/**
 * Per-edge transport state and failover (FR-3.6/3.6a). Pure Kotlin.
 *
 * Model: humans agree out-of-band -> both call [setTransport]; first frame received
 * from the peer on any transport confirms/re-homes the edge ([onFrameFrom]).
 * If the peer goes silent (>= [staleWindows] windows) the edge goes DOWN and
 * [failoverOrder] yields the transports to auto-try, battery-cheap first.
 */
class EdgeManager(
    private val selfId: MemberId,
    private val nowMs: () -> Long,
    private val staleWindows: Int = 3,
) {
    enum class State { UP, WAITING_PEER, DOWN }

    data class Edge(
        val peerId: MemberId,
        val transport: TransportId,
        val state: State,
        val lastSeenMs: Long,
        val lastWorking: TransportId?,
    )

    private data class Internal(
        var transport: TransportId,
        var state: State,
        var lastSeenMs: Long,
        var lastWorking: TransportId?,
    )

    private val edges = HashMap<MemberId, Internal>()

    /** Human-agreed change (FR-3.6): local selection; peer confirmation pending. */
    fun setTransport(peerId: MemberId, transport: TransportId) {
        val cur = edges[peerId]
        edges[peerId] = Internal(
            transport = transport,
            state = State.WAITING_PEER,
            lastSeenMs = cur?.lastSeenMs ?: nowMs(),
            lastWorking = cur?.lastWorking,
        )
    }

    /** Any valid frame from the peer confirms or re-homes the edge (FR-3.6/3.6a). */
    fun onFrameFrom(peerId: MemberId, transport: TransportId) {
        val cur = edges[peerId]
        edges[peerId] = Internal(
            transport = transport,
            state = State.UP,
            lastSeenMs = nowMs(),
            lastWorking = transport,
        )
    }

    fun onEdgeDown(peerId: MemberId) {
        edges[peerId]?.let { it.state = State.DOWN }
    }

    fun edge(peerId: MemberId): Edge? = edges[peerId]?.let {
        Edge(peerId, it.transport, it.state, it.lastSeenMs, it.lastWorking)
    }

    fun all(): List<Edge> = edges.map { (id, e) ->
        Edge(id, e.transport, e.state, e.lastSeenMs, e.lastWorking)
    }

    /**
     * Transports to auto-try when [peerId]'s edge is DOWN (FR-3.6a):
     * last working first, then battery cost (BT/WiFi-Direct before LoRa),
     * excluding the dead selection and anything not in [provisioned].
     */
    fun failoverOrder(peerId: MemberId, provisioned: Set<TransportId>): List<TransportId> {
        val e = edges[peerId] ?: return emptyList()
        val dead = e.transport
        val lastWorking = e.lastWorking?.takeIf { it != dead && it in provisioned }
        val cheap = listOf(TransportId.BLUETOOTH, TransportId.WIFI_DIRECT, TransportId.INTERNET)
        val rest = (cheap + TransportId.LORA)
            .filter { it != dead && it != lastWorking && it in provisioned }
        return listOfNotNull(lastWorking) + rest
    }

    /** Call periodically: peers silent for >= staleWindows windows go DOWN. */
    fun tick(): List<MemberId> {
        val now = nowMs()
        val wentDown = mutableListOf<MemberId>()
        edges.forEach { (id, e) ->
            if (e.state == State.UP && now - e.lastSeenMs >= staleWindows * KEEP_ALIVE_MS) {
                e.state = State.DOWN
                wentDown += id
            }
        }
        return wentDown
    }

    companion object {
        const val KEEP_ALIVE_MS = 60_000L
    }
}
