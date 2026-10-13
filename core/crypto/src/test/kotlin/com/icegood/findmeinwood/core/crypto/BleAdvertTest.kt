package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.NetworkId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleAdvertTest {
    private val secret = ByteArray(32) { 7 }
    private val other = ByteArray(32) { 8 }
    private val t0 = 1_700_000_000_000L

    @Test
    fun `members code changes every window and only members recognise it`() {
        val w = BleAdvert.window(t0)
        val tag = BleAdvert.memberTag(secret, w)
        assertEquals(BleAdvert.TAG_LEN, tag.size)
        assertFalse(tag.contentEquals(BleAdvert.memberTag(secret, w + 1)), "rotates")
        assertFalse(tag.contentEquals(BleAdvert.memberTag(other, w)), "per network")
        assertTrue(BleAdvert.isMember(secret, tag, t0))
        assertTrue(BleAdvert.isMember(secret, tag, t0 + BleAdvert.WINDOW_MS), "a window of clock skew")
        assertFalse(BleAdvert.isMember(secret, tag, t0 + 3 * BleAdvert.WINDOW_MS), "an old code expires")
        assertFalse(BleAdvert.isMember(other, tag, t0))
        assertFalse(BleAdvert.isMember(secret, null, t0))
        assertFalse(BleAdvert.isMember(secret, ByteArray(3), t0))
    }

    @Test
    fun `network info fits a scan response and round-trips`() {
        val id = NetworkId(ByteArray(8) { it.toByte() })
        val info = BleAdvert.Info(id, JoinPolicy.PRIVATE, "test")
        val bytes = BleAdvert.encodeInfo(info)
        assertEquals(info, BleAdvert.decodeInfo(bytes))
        val long = BleAdvert.encodeInfo(info.copy(name = "Лісова прогулянка вихідного дня"))
        assertTrue(long.size <= BleAdvert.INFO_MAX)
        assertTrue(BleAdvert.decodeInfo(long)!!.name!!.startsWith("Лісова"), "cut on a character boundary")
        assertNull(BleAdvert.decodeInfo(BleAdvert.encodeInfo(info.copy(name = null)))!!.name)
        assertNull(BleAdvert.decodeInfo(null))
        assertNull(BleAdvert.decodeInfo(ByteArray(4)))
        assertNull(BleAdvert.decodeInfo(ByteArray(8) + byteArrayOf(9)), "unknown policy")
        assertContentEquals(id.bytes, BleAdvert.decodeInfo(bytes)!!.networkId.bytes)
        assertFailsWith<IllegalArgumentException> { BleAdvert.encodeInfo(info.copy(networkId = NetworkId(ByteArray(4)))) }
    }

    private fun ad(type: Int, vararg data: Byte) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data
    private fun manuf(company: Int, data: ByteArray) =
        ad(0xFF, *(byteArrayOf(company.toByte(), (company shr 8).toByte()) + data))

    @Test
    fun `raw record - members code and network info told apart by length`() {
        val tag = ByteArray(6) { 1 }
        val info = BleAdvert.encodeInfo(BleAdvert.Info(NetworkId(ByteArray(8)), JoinPolicy.OPEN, "x"))
        val raw = ad(0x01, 0x06) + manuf(0xFFFF, tag) + manuf(0x004C, ByteArray(6)) + manuf(0xFFFF, info) + byteArrayOf(0, 0)
        val p = BleAdvert.parseRecord(raw)
        assertContentEquals(tag, p.tag)
        assertContentEquals(info, p.info)
        val onlyTag = BleAdvert.parseRecord(ad(0x01, 0x06) + manuf(0xFFFF, tag))
        assertNull(onlyTag.info)
        assertNull(BleAdvert.parseRecord(null).tag)
        assertNull(BleAdvert.parseRecord(byteArrayOf(9, 0xFF.toByte(), 1)).tag, "truncated record")
        assertNull(BleAdvert.parseRecord(manuf(0xFFFF, ByteArray(3))).tag, "neither length")
    }
}

