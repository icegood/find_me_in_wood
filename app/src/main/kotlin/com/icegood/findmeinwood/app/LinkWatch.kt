package com.icegood.findmeinwood.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure peer-link bookkeeping for the debug link log: turns periodic samples into
 * "signal lost" / "signal back" events and remembers the last values seen before a loss
 * (distance, RSSI, transport), which is what the log is for.
 */
internal class LinkWatch(private val lostAfterMs: Long) {

    /** One peer as seen in one sample. */
    data class PeerSample(
        val id: String,
        /** Time since the last authenticated frame from this peer. */
        val silentMs: Long,
        /** Distance from my fix to the peer's last position, if both are known. */
        val distanceM: Double?,
        /** Transport that last delivered a frame from this peer, if known. */
        val transport: String?,
    )

    sealed interface Event {
        val id: String

        /** Peer went silent for [silentMs]; last values seen while it was still heard. */
        data class Lost(
            override val id: String,
            val silentMs: Long,
            val lastDistanceM: Double?,
            val lastTransport: String?,
            /** Distance per sample over the last minute before the loss, oldest first. */
            val distanceTrend: List<Double>,
        ) : Event

        /** Peer heard again after being lost for [gapMs]. */
        data class Back(
            override val id: String,
            val gapMs: Long,
            val distanceM: Double?,
            val transport: String?,
        ) : Event
    }

    private class State(
        var lost: Boolean = false,
        var lostAtMs: Long = 0,
        var silentAtLossMs: Long = 0,
        var lastDistanceM: Double? = null,
        var lastTransport: String? = null,
        val trend: ArrayDeque<Double> = ArrayDeque(),
    )

    private val peers = HashMap<String, State>()

    fun update(nowMs: Long, samples: List<PeerSample>): List<Event> {
        val events = mutableListOf<Event>()
        for (s in samples) {
            val st = peers.getOrPut(s.id) { State() }
            val heard = s.silentMs < lostAfterMs
            if (heard) {
                if (st.lost) {
                    // Whole silence: what had passed when the loss was noticed, plus since then.
                    events += Event.Back(s.id, st.silentAtLossMs + (nowMs - st.lostAtMs), s.distanceM, s.transport)
                    st.lost = false
                }
                s.distanceM?.let {
                    st.lastDistanceM = it
                    st.trend.addLast(it)
                    while (st.trend.size > TREND_SAMPLES) st.trend.removeFirst()
                }
                s.transport?.let { st.lastTransport = it }
            } else if (!st.lost) {
                st.lost = true
                st.lostAtMs = nowMs
                st.silentAtLossMs = s.silentMs
                events += Event.Lost(s.id, s.silentMs, st.lastDistanceM, st.lastTransport, st.trend.toList())
            }
        }
        return events
    }

    companion object {
        /** 6 samples at the 10 s cadence = the minute before the loss. */
        const val TREND_SAMPLES = 6

        /** Great-circle distance in metres (WGS-84 mean radius); pure, for JVM tests. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_008.8
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
