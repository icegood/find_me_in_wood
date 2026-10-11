package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsCodecTest {
    private val keys = NetworkKeysFactory.derive("sms-net".toByteArray())
    private val other = NetworkKeysFactory.derive("other-net".toByteArray())
    private val me = MemberId(ByteArray(32) { (it * 7).toByte() })

    private fun encode(seq: UInt = 1000u, battery: Int? = 42) =
        SmsCodec.encode(
            keys.trafficKey, keys.networkId, me, seq,
            timeS = 1_791_700_000L, latE7 = 498_493_210, lonE7 = 240_612_100, accuracyM = 12, batteryPct = battery,
        )

    @Test
    fun `fits one GSM-7 SMS`() {
        val sms = encode()
        assertTrue(sms.startsWith(SmsCodec.PREFIX))
        assertTrue(sms.length <= 160, "length ${sms.length}")
        // GSM-7 basic set covers base64url and the prefix; no UCS-2 fallback (70 chars).
        assertTrue(sms.all { it.isLetterOrDigit() && it.code < 128 || it in "-_:" }, sms)
    }

    @Test
    fun `round trip`() {
        val r = SmsCodec.decode(SmsCodec.frameOf(encode())!!, keys.trafficKey)
        assertNotNull(r)
        assertEquals(1000u, r.seq)
        assertEquals(1_791_700_000L, r.timeS)
        assertEquals(498_493_210, r.latE7)
        assertEquals(240_612_100, r.lonE7)
        assertEquals(12, r.accuracyM)
        assertEquals(42, r.batteryPct)
        assertTrue(r.isFrom(me))
    }

    @Test
    fun `negative coordinates and unknown battery survive`() {
        val sms = SmsCodec.encode(
            keys.trafficKey, keys.networkId, me, 7u,
            timeS = 1L, latE7 = -334_000_000, lonE7 = -700_000_000, accuracyM = 100_000, batteryPct = null,
        )
        val r = SmsCodec.decode(SmsCodec.frameOf(sms)!!, keys.trafficKey)!!
        assertEquals(-334_000_000, r.latE7)
        assertEquals(-700_000_000, r.lonE7)
        assertEquals(0xFFFF, r.accuracyM, "accuracy is capped, not wrapped")
        assertEquals(SmsCodec.BATTERY_UNKNOWN, r.batteryPct)
    }

    @Test
    fun `other networks cannot read it and the tag points to the right one`() {
        val frame = SmsCodec.frameOf(encode())!!
        assertNull(SmsCodec.decode(frame, other.trafficKey))
        assertTrue(SmsCodec.networkTagOf(frame).contentEquals(SmsCodec.networkTag(keys.networkId)))
    }

    @Test
    fun `tampered header or body is rejected`() {
        val frame = SmsCodec.frameOf(encode())!!
        // Any header bit (here a reserved flag) is authenticated.
        val header = frame.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(SmsCodec.decode(header, keys.trafficKey))
        val body = frame.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertNull(SmsCodec.decode(body, keys.trafficKey))
    }

    @Test
    fun `found inside a longer SMS, rejected when malformed`() {
        val sms = "find me in wood: ${encode()} (position)"
        assertTrue(SmsCodec.looksLikeReport(sms))
        assertNotNull(SmsCodec.decode(SmsCodec.frameOf(sms)!!, keys.trafficKey))
        assertNull(SmsCodec.frameOf("hello"))
        assertNull(SmsCodec.frameOf(SmsCodec.PREFIX + "short"))
        val wrongVersion = SmsCodec.frameOf(encode())!!.also { it[0] = 0x21 }
        assertNull(SmsCodec.frameOf(SmsCodec.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(wrongVersion)))
    }
}
