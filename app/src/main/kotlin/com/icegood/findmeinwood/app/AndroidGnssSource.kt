package com.icegood.findmeinwood.app

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.session.GnssSource
import com.icegood.findmeinwood.core.session.SpoofGuard
import com.icegood.findmeinwood.feature.map.toRawFix
import com.icegood.findmeinwood.transport.api.DebugLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * FR-4.1/4.2: platform location, GPS first and the network provider as fallback so a fix
 * exists indoors too (accuracy is carried per fix). Interval configurable (5–300 s) and the
 * last known fix is emitted immediately. FR-4.5: faked satellite positions are never sent;
 * Wi-Fi/cell ones stand in meanwhile.
 */
class AndroidGnssSource(
    private val context: Context,
    private val intervalMs: Long = 30_000,
) : GnssSource {
    override fun fixes(): Flow<GnssFix> = callbackFlow {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val guard = SpoofGuard()
        var spoofed = false
        // Explicit object: below API 30 the listener interface has extra abstract methods,
        // so a Kotlin SAM lambda would crash with AbstractMethodError at runtime.
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val passed = loc.toRawFix()?.let(guard::offer)
                if (spoofed != guard.gpsSpoofed) {
                    spoofed = guard.gpsSpoofed
                    DebugLog.d { "gnss: satellite positions ${if (spoofed) "look faked, using network" else "trusted again"}" }
                }
                passed?.let { trySend(it.fix) }
            }

            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).filter { lm.isProviderEnabled(it) }
            // The last known ones in time order prime the check; the newest trusted is sent.
            providers.mapNotNull { lm.getLastKnownLocation(it) }.sortedBy { it.time }
                .mapNotNull { loc -> loc.toRawFix()?.let(guard::offer) }
                .lastOrNull()?.let { trySend(it.fix) }
            providers.forEach {
                lm.requestLocationUpdates(it, intervalMs, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            // permission not granted: no fixes (T3.4 gates UI before session start)
        } catch (_: IllegalArgumentException) {
            // provider unavailable on this device
        }
        awaitClose { lm.removeUpdates(listener) }
    }
}
