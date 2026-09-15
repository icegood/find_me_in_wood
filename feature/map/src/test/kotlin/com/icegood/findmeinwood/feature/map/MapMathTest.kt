package com.icegood.findmeinwood.feature.map

import android.location.Location
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MapMathTest {
    private fun loc(lat: Double, lon: Double) = Location("test").apply { latitude = lat; longitude = lon }

    @Test
    fun `distance and bearing to a point north of me`() {
        val me = loc(50.0, 30.0)
        val (meters, bearing) = distanceBearing(me, (50.01 * 1e7).toInt(), (30.0 * 1e7).toInt())
        assertTrue(meters > 1000 && meters < 1200, "expected ~1112 m, got $meters")
        assertTrue(bearing < 5 || bearing > 355, "expected ~0 deg, got $bearing")
    }

    @Test
    fun `bearing to the east is about 90 degrees`() {
        val me = loc(0.0, 0.0)
        val (_, bearing) = distanceBearing(me, 0, (0.01 * 1e7).toInt())
        assertTrue(bearing in 85.0..95.0, "expected ~90 deg, got $bearing")
    }
}
