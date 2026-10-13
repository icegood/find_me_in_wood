package com.icegood.findmeinwood.feature.map

import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.session.FixSource
import com.icegood.findmeinwood.core.session.RawFix
import com.icegood.findmeinwood.core.session.SpoofGuard

/**
 * A platform position for the spoofing check; null for blends such as "fused", which may
 * hide a faked satellite position behind a Wi-Fi one.
 */
fun Location.toRawFix(): RawFix? {
    val source = when (provider) {
        LocationManager.GPS_PROVIDER -> FixSource.GPS
        LocationManager.NETWORK_PROVIDER -> FixSource.NETWORK
        else -> return null
    }
    val mock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
    return RawFix(source, GnssFix(latitude, longitude, accuracy, altitude, time), mock)
}

/** The trusted one of the phone's last known positions. */
fun trustedLastKnown(locations: List<Location>): Location? {
    val raws = locations.mapNotNull { l -> l.toRawFix()?.let { it to l } }
    val best = SpoofGuard.bestOf(raws.map { it.first }) ?: return null
    return raws.first { it.first.fix == best.fix }.second
}
