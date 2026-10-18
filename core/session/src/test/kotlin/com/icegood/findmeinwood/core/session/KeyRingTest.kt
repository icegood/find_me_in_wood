package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.EpochKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyRingTest {
    private val base = ByteArray(32) { 3 }

    @Test
    fun `starts on the base key and moves only forward, skipping missed epochs`() {
        val ring = KeyRing(base)
        assertEquals(0, ring.epoch)
        assertContentEquals(base, ring.current())
        val s3 = EpochKeys.newSalt()
        assertTrue(ring.advance(3, s3), "a phone that missed epochs jumps to the newest")
        assertEquals(3, ring.epoch)
        assertContentEquals(EpochKeys.trafficKey(base, s3), ring.current())
        assertFalse(ring.advance(2, EpochKeys.newSalt()), "never back")
        assertFalse(ring.advance(4, ByteArray(5)), "a salt of the wrong size")
        assertEquals(listOf(3, 0), ring.newestFirst().map { it.first })
        assertContentEquals(s3, ring.salt(3))
        assertNull(ring.salt(0))
        assertNull(ring.key(2))
    }

    @Test
    fun `restored from stored salts`() {
        val s1 = EpochKeys.newSalt()
        val ring = KeyRing(base, mapOf(1 to s1, 0 to ByteArray(32)))
        assertEquals(1, ring.epoch)
        assertEquals(setOf(1), ring.salts().keys, "epoch 0 has no salt")
        assertContentEquals(EpochKeys.trafficKey(base, s1), ring.key(1))
    }
}
