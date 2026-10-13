package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.GnssFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpoofGuardTest {
    // Western Ukraine, where the phone is, and Lima, where the faked satellites put it.
    private fun lviv(t: Long, acc: Float = 5f, dLat: Double = 0.0) = GnssFix(49.84 + dLat, 24.03, acc, 300.0, t)
    private fun lima(t: Long) = GnssFix(-12.05, -77.04, 5f, 990.0, t)
    private fun gps(f: GnssFix, mock: Boolean = false) = RawFix(FixSource.GPS, f, mock)
    private fun net(f: GnssFix) = RawFix(FixSource.NETWORK, f)

    @Test
    fun `satellites far from a fresh wifi position are dropped and wifi is used`() {
        val g = SpoofGuard()
        assertEquals(FixSource.NETWORK, g.offer(net(lviv(0, acc = 16f)))!!.source)
        assertNull(g.offer(gps(lima(1_000))))
        assertTrue(g.gpsSpoofed)
        // While faked, the next wifi position is used and says so.
        val used = assertNotNull(g.offer(net(lviv(60_000, acc = 20f))))
        assertEquals(FixSource.NETWORK, used.source)
        assertTrue(used.gpsSpoofed)
    }

    @Test
    fun `satellites agreeing with wifi pass and then beat the vaguer wifi`() {
        val g = SpoofGuard()
        g.offer(net(lviv(0, acc = 2000f)))
        val ok = assertNotNull(g.offer(gps(lviv(1_000, dLat = 0.01))))
        assertEquals(FixSource.GPS, ok.source)
        assertFalse(ok.gpsSpoofed)
        assertNull(g.offer(net(lviv(5_000, acc = 2000f))))
        // Once the satellites go quiet, wifi is used again.
        assertNotNull(g.offer(net(lviv(5_000 + SpoofGuard.GPS_PREFERRED_MS, acc = 2000f))))
    }

    @Test
    fun `the state clears with the first satellite position that passes`() {
        val g = SpoofGuard()
        g.offer(net(lviv(0)))
        g.offer(gps(lima(1_000)))
        assertTrue(g.gpsSpoofed)
        assertNotNull(g.offer(gps(lviv(2_000))))
        assertFalse(g.gpsSpoofed)
    }

    @Test
    fun `without wifi an impossible jump is a fake and a plausible trip is not`() {
        val g = SpoofGuard()
        assertNotNull(g.offer(gps(lviv(0))))
        assertNull(g.offer(gps(lima(60_000))))
        assertTrue(g.gpsSpoofed)
        // 0.1° (11 km) in 10 min is a car, not a fake.
        assertNotNull(g.offer(gps(lviv(600_000, dLat = 0.1))))
        assertFalse(g.gpsSpoofed)
        // The same instant somewhere else is a fake too.
        assertNull(g.offer(gps(lviv(600_000, dLat = 1.0))))
    }

    @Test
    fun `old wifi is no anchor and wifi alone recovers from a fake first fix`() {
        val g = SpoofGuard()
        g.offer(net(lviv(0)))
        // Wifi is stale by now; 12 000 km in a day is still too fast.
        assertNull(g.offer(gps(lima(SpoofGuard.NETWORK_FRESH_MS + 1))))
        // A fake as the very first fix is believed, but fresh wifi corrects the next one.
        val g2 = SpoofGuard()
        assertNotNull(g2.offer(gps(lima(0))))
        g2.offer(net(lviv(1_000)))
        assertNotNull(g2.offer(gps(lviv(2_000))))
        assertNull(g2.offer(gps(lima(3_000))))
    }

    @Test
    fun `mock-location apps count as fakes`() {
        val g = SpoofGuard()
        assertNull(g.offer(gps(lviv(0), mock = true)))
        assertTrue(g.gpsSpoofed)
    }

    @Test
    fun `the start position comes from the trusted last known ones`() {
        val best = assertNotNull(SpoofGuard.bestOf(listOf(gps(lima(2_000)), net(lviv(1_000)))))
        assertEquals(FixSource.NETWORK, best.source)
        assertTrue(best.gpsSpoofed)
        assertNull(SpoofGuard.bestOf(emptyList()))
        assertEquals(FixSource.GPS, SpoofGuard.bestOf(listOf(gps(lviv(0))))!!.source)
    }

    @Test
    fun `distance is great-circle metres`() {
        assertEquals(111_195.0, SpoofGuard.distanceM(lviv(0), lviv(0, dLat = 1.0)), 1.0)
    }
}
