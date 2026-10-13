package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.GnssFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a position came from: the satellite receiver, or Wi-Fi/cell towers (and the rest). */
enum class FixSource { GPS, NETWORK }

/** One position as the platform reported it, before the spoofing check. */
data class RawFix(
    val source: FixSource,
    val fix: GnssFix,
    /** Supplied by a mock-location app. */
    val mock: Boolean = false,
)

/** What the check lets through, and whether satellite positions are being faked right now. */
data class GuardedFix(val fix: GnssFix, val source: FixSource, val gpsSpoofed: Boolean)

/**
 * FR-4.5: drops faked satellite positions. Electronic warfare broadcasts fake satellite
 * signals, and the receiver then reports a confident (±5 m) position far away. Such a fix
 * is told apart by:
 *  - disagreeing with a recent Wi-Fi/cell position by more than both errors allow, or,
 *    with no recent one, lying further from the last trusted position than anyone
 *    could have travelled.
 *
 * While satellites are faked, Wi-Fi/cell positions are used (less exact, but true); a
 * dropped fix yields nothing, so the last trusted position stays. The state clears with
 * the first satellite position that passes again.
 */
class SpoofGuard {
    private var lastNetwork: GnssFix? = null
    private var lastTrusted: GnssFix? = null
    private var lastTrustedGps: GnssFix? = null

    /** The last satellite position failed the check. */
    var gpsSpoofed = false
        private set

    /** Checks [raw]: the position to use, or null when it is dropped. */
    fun offer(raw: RawFix): GuardedFix? {
        val f = raw.fix
        return when (raw.source) {
            FixSource.NETWORK -> {
                lastNetwork = f
                // A fresher, more exact satellite position wins while it is trusted.
                val gps = lastTrustedGps
                if (!gpsSpoofed && gps != null && f.epochMs - gps.epochMs < GPS_PREFERRED_MS &&
                    gps.accuracyM <= f.accuracyM
                ) {
                    null
                } else {
                    accept(f, FixSource.NETWORK)
                }
            }
            FixSource.GPS -> {
                // A fresh Wi-Fi/cell position is the anchor; without one, travel speed decides.
                val net = lastNetwork?.takeIf { kotlin.math.abs(f.epochMs - it.epochMs) <= NETWORK_FRESH_MS }
                gpsSpoofed = raw.mock || if (net != null) disagrees(f, net) else impossibleJump(f)
                if (gpsSpoofed) null else accept(f, FixSource.GPS).also { lastTrustedGps = f }
            }
        }
    }

    private fun accept(f: GnssFix, source: FixSource): GuardedFix {
        lastTrusted = f
        return GuardedFix(f, source, gpsSpoofed)
    }

    private fun disagrees(f: GnssFix, net: GnssFix): Boolean {
        val allowed = maxOf(MIN_DISAGREEMENT_M, 3.0 * (net.accuracyM + f.accuracyM))
        return distanceM(f, net) > allowed
    }

    private fun impossibleJump(f: GnssFix): Boolean {
        val prev = lastTrusted ?: return false
        val gap = distanceM(f, prev) - prev.accuracyM - f.accuracyM - MIN_DISAGREEMENT_M
        if (gap <= 0) return false
        val dtS = (f.epochMs - prev.epochMs) / 1000.0
        return dtS <= 0 || gap / dtS > MAX_SPEED_MPS
    }

    companion object {
        /** A Wi-Fi/cell position this old still says where the phone is. */
        const val NETWORK_FRESH_MS = 10 * 60_000L

        /** Never call a disagreement smaller than this a fake: cell positions can be that far off. */
        const val MIN_DISAGREEMENT_M = 2_000.0

        /** Faster than any car or train: 360 km/h. */
        const val MAX_SPEED_MPS = 100.0

        /** A trusted satellite position this fresh beats a vaguer Wi-Fi/cell one. */
        const val GPS_PREFERRED_MS = 30_000L

        /** The position to start from among the platform's last known ones, or null. */
        fun bestOf(fixes: List<RawFix>): GuardedFix? {
            val guard = SpoofGuard()
            var best: GuardedFix? = null
            fixes.sortedBy { it.fix.epochMs }.forEach { raw -> guard.offer(raw)?.let { best = it } }
            return best?.copy(gpsSpoofed = guard.gpsSpoofed)
        }

        /** Great-circle distance in metres (WGS-84 mean radius). */
        fun distanceM(a: GnssFix, b: GnssFix): Double {
            val r = 6_371_008.8
            val p1 = Math.toRadians(a.lat)
            val p2 = Math.toRadians(b.lat)
            val dp = p2 - p1
            val dl = Math.toRadians(b.lon - a.lon)
            val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
