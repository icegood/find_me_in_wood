package app.findmeinwood.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayloadCodecTest {
    @Test
    fun `roundtrip with position`() {
        val p = BeaconPayload(BeaconPosition(510000000, 70000123, 450, 123456), fixAgeS = 3, batteryPct = 87)
        val decoded = PayloadCodec.decode(PayloadCodec.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `roundtrip keepalive without position`() {
        val p = BeaconPayload(position = null, batteryPct = 42)
        val decoded = PayloadCodec.decode(PayloadCodec.encode(p))
        assertNull(decoded.position)
        assertEquals(42, decoded.batteryPct)
    }

    @Test
    fun `fromFix converts e7`() {
        val p = PayloadCodec.fromFix(GnssFix(51.5, -0.12, 4.5f, 120.0, 0L))
        assertEquals(515000000, p.position!!.latE7)
        assertEquals(-1200000, p.position!!.lonE7)
        assertEquals(450, p.position!!.accuracyCm)
    }
}
