package com.icegood.findmeinwood.feature.map

import android.location.Location
import android.location.LocationManager
import com.icegood.findmeinwood.core.session.FixSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DeviceLocationTest {
    private val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
    private fun loc(provider: String, lat: Double, lon: Double, t: Long, acc: Float = 5f) =
        Location(provider).apply { latitude = lat; longitude = lon; time = t; accuracy = acc }

    @Test
    fun `platform positions keep their source and blends are left out`() {
        val g = loc(LocationManager.GPS_PROVIDER, 49.84, 24.03, 7).toRawFix()!!
        assertEquals(FixSource.GPS, g.source)
        assertEquals(49.84, g.fix.lat)
        assertEquals(7, g.fix.epochMs)
        assertFalse(g.mock)
        assertEquals(FixSource.NETWORK, loc(LocationManager.NETWORK_PROVIDER, 0.0, 0.0, 0).toRawFix()!!.source)
        assertNull(loc("fused", 0.0, 0.0, 0).toRawFix())
    }

    @Test
    fun `the start position skips a faked satellite one`() {
        val wifi = loc(LocationManager.NETWORK_PROVIDER, 49.84, 24.03, 1_000, acc = 16f)
        val faked = loc(LocationManager.GPS_PROVIDER, -12.05, -77.04, 2_000)
        assertSame(wifi, trustedLastKnown(listOf(faked, wifi, loc("fused", 1.0, 1.0, 3_000))))
        assertNull(trustedLastKnown(emptyList()))
    }

    @Test
    fun `status line says where my position comes from`() {
        val gps = loc(LocationManager.GPS_PROVIDER, 0.0, 0.0, 0, acc = 4f)
        val net = loc(LocationManager.NETWORK_PROVIDER, 0.0, 0.0, 0, acc = 30f)
        assertEquals("waiting for GPS", myFixText(ctx, null))
        assertEquals("GPS ±4 m", myFixText(ctx, DeviceFix(gps, null)))
        assertEquals("network ±30 m", myFixText(ctx, DeviceFix(net, null)))
        assertTrue(myFixText(ctx, DeviceFix(net, null, gpsSpoofed = true)).startsWith("GPS faked nearby"))
    }
}
