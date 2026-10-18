package com.icegood.findmeinwood.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EpochKeysTest {
    private val base = ByteArray(32) { it.toByte() }

    @Test
    fun `epoch 0 is the base key, every salt gives another key`() {
        assertContentEquals(base, EpochKeys.trafficKey(base, null))
        val a = EpochKeys.trafficKey(base, EpochKeys.newSalt())
        val b = EpochKeys.trafficKey(base, EpochKeys.newSalt())
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(base) || a.contentEquals(b))
    }

    @Test
    fun `a salt sealed to a member opens only with its box key and the same binding`() {
        val salt = EpochKeys.newSalt()
        val member = EpochKeys.boxKeyPair()
        val other = EpochKeys.boxKeyPair()
        val eph = EpochKeys.boxKeyPair()
        val aad = byteArrayOf(1, 2, 3)
        val ct = EpochKeys.seal(salt, eph, member.public.encoded, aad)
        assertContentEquals(salt, EpochKeys.open(ct, member, eph.public.encoded, aad))
        assertNull(EpochKeys.open(ct, other, eph.public.encoded, aad), "not sealed to this member")
        assertNull(EpochKeys.open(ct, member, eph.public.encoded, byteArrayOf(9)), "another member or epoch")
    }
}
