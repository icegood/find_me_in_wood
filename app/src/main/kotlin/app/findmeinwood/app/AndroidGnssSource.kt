package app.findmeinwood.app

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.findmeinwood.core.model.GnssFix
import app.findmeinwood.core.session.GnssSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** FR-4.1/4.2: platform GNSS via LocationManager GPS provider, default 30 s interval. */
class AndroidGnssSource(
    private val context: Context,
    private val intervalMs: Long = 30_000,
) : GnssSource {
    override fun fixes(): Flow<GnssFix> = callbackFlow {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc: Location ->
            trySend(
                GnssFix(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    accuracyM = loc.accuracy,
                    altitudeM = loc.altitude,
                    epochMs = loc.time,
                ),
            )
        }
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) {
                trySend(GnssFix(last.latitude, last.longitude, last.accuracy, last.altitude, last.time))
            }
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            // permission not granted: no fixes (T3.4 gates UI before session start)
        }
        awaitClose { lm.removeUpdates(listener) }
    }
}
