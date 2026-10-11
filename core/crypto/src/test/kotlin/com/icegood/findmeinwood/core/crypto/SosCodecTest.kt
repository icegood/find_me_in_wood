package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SosCodecTest {
    private val keys = NetworkKeysFactory.derive("sos-net".toByteArray())
    private val other = NetworkKeysFactory.derive("other-net".toByteArray())
    private val me = MemberId(ByteArray(32) { (it * 7).toByte() })

    private fun encode(seq: UInt = 1000u, active: Boolean = true, cancelled: Boolean = false, battery: Int? = 42) =
        SosCodec.encode(
            keys.trafficKey, keys.networkId, me, seq, active, cancelled,
            timeS = 1_791_700_000L, latE7 = 498_493_210, lonE7 = 240_612_100, accuracyM = 12, batteryPct = battery,
        )

    @Test
    fun `fits one GSM-7 SMS`() {
        val sms = encode()
        assertTrue(sms.startsWith(SosCodec.PREFIX))
        assertTrue(sms.length <= 160, "length ${sms.length}")
        // GSM-7 basic set covers base64url and the prefix; no UCS-2 fallback (70 chars).
        assertTrue(sms.all { it.isLetterOrDigit() && it.code < 128 || it in "-_:" }, sms)
    }

    @Test
    fun `round trip`() {
        val r = SosCodec.decode(SosCodec.frameOf(encode())!!, keys.trafficKey)
        assertNotNull(r)
        assertTrue(r.active)
        assertFalse(r.cancelled)
        assertEquals(1000u, r.seq)
        assertEquals(1_791_700_000L, r.timeS)
        assertEquals(498_493_210, r.latE7)
        assertEquals(240_612_100, r.lonE7)
        assertEquals(12, r.accuracyM)
        assertEquals(42, r.batteryPct)
        assertTrue(r.isFrom(me))
    }

    @Test
    fun `negative coordinates, unknown battery and cancel survive`() {
        val sms = SosCodec.encode(
            keys.trafficKey, keys.networkId, me, 7u, active = false, cancelled = true,
            timeS = 1L, latE7 = -334_000_000, lonE7 = -700_000_000, accuracyM = 100_000, batteryPct = null,
        )
        val r = SosCodec.decode(SosCodec.frameOf(sms)!!, keys.trafficKey)!!
        assertEquals(-334_000_000, r.latE7)
        assertEquals(-700_000_000, r.lonE7)
        assertEquals(0xFFFF, r.accuracyM, "accuracy is capped, not wrapped")
        assertEquals(SosCodec.BATTERY_UNKNOWN, r.batteryPct)
        assertTrue(r.cancelled)
        assertFalse(r.active)
    }

    @Test
    fun `other networks cannot read it and the tag points to the right one`() {
        val frame = SosCodec.frameOf(encode())!!
        assertNull(SosCodec.decode(frame, other.trafficKey))
        assertTrue(SosCodec.networkTagOf(frame).contentEquals(SosCodec.networkTag(keys.networkId)))
    }

    @Test
    fun `tampered header or body is rejected`() {
        val frame = SosCodec.frameOf(encode())!!
        // Flip "active" off: the header is authenticated.
        val header = frame.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(SosCodec.decode(header, keys.trafficKey))
        val body = frame.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertNull(SosCodec.decode(body, keys.trafficKey))
    }

    @Test
    fun `found inside a longer SMS, rejected when malformed`() {
        val sms = "SOS! Serhii needs help. ${encode()} (sent by find me in wood)"
        assertTrue(SosCodec.looksLikeSos(sms))
        assertNotNull(SosCodec.decode(SosCodec.frameOf(sms)!!, keys.trafficKey))
        assertNull(SosCodec.frameOf("hello"))
        assertNull(SosCodec.frameOf(SosCodec.PREFIX + "short"))
        val wrongVersion = SosCodec.frameOf(encode())!!.also { it[0] = 0x21 }
        assertNull(SosCodec.frameOf(SosCodec.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(wrongVersion)))
    }
}
