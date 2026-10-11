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

    @Test
    fun `heading smoothing takes the short way round`() {
        val wrap = blendHeading(350f, 10f)
        assertTrue(wrap > 350f || wrap < 10f, "expected near north, got $wrap")
        val same = blendHeading(90f, 90f)
        assertTrue(same in 89.9f..90.1f, "expected 90, got $same")
        assertTrue(blendHeading(null, 370f) in 9.9f..10.1f)
    }

    @Test
    fun `arrow ends on a visible target`() {
        val e = arrowEnd(100f, 100f, 300f, 200f, 1000f, 800f, 10f)!!
        assertTrue(!e.offScreen && e.x == 300f && e.y == 200f, "$e")
    }

    @Test
    fun `arrow to an off-screen target stops on the edge in the right direction`() {
        // Target far to the right: the tip lands on the right edge at the same height.
        val right = arrowEnd(500f, 400f, 5000f, 400f, 1000f, 800f, 10f)!!
        assertTrue(right.offScreen && right.x == 990f && right.y == 400f, "$right")
        // Target up and to the left: tip on the top edge, left of me.
        val up = arrowEnd(500f, 400f, 300f, -2000f, 1000f, 800f, 10f)!!
        assertTrue(up.offScreen && up.y == 10f && up.x < 500f && up.x >= 10f, "$up")
    }

    @Test
    fun `no arrow when the target is exactly here`() {
        assertTrue(arrowEnd(50f, 50f, 50f, 50f, 100f, 100f, 5f) == null)
    }

    @Test
    fun `distance label switches to km`() {
        assertTrue(formatDistance(850.4) == "850 m")
        // decimal separator follows the device locale
        assertTrue(formatDistance(1530.0).matches(Regex("1[.,]5 km")), formatDistance(1530.0))
    }

    @Test
    fun `distance uncertainty is shown only when the fixes are rough`() {
        kotlin.test.assertEquals("", uncertainty(10, 15))
        kotlin.test.assertEquals(" (±518 m)", uncertainty(500, 18))
        kotlin.test.assertEquals("", uncertainty(-1, 0))
    }
}
