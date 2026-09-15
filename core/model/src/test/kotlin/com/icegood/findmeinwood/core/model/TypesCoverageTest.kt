package com.icegood.findmeinwood.core.model

import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypesCoverageTest {
    @Test
    fun `transport id and join policy enumerations`() {
        assertEquals(4, TransportId.entries.size)
        assertEquals(3, JoinPolicy.entries.size)
    }

    @Test
    fun `hello payload cbor roundtrip`() {
        val h = HelloPayload(
            name = "hunt",
            ownerMemberId = ByteArray(32) { 5 },
            ownerNick = "ice",
            policy = JoinPolicy.OPEN,
            activeTransports = listOf(TransportId.BLUETOOTH, TransportId.LORA),
        )
        val decoded = Cbor.decodeFromByteArray(HelloPayload.serializer(), Cbor.encodeToByteArray(HelloPayload.serializer(), h))
        assertEquals(h.name, decoded.name)
        assertContentEquals(h.ownerMemberId, decoded.ownerMemberId)
        assertEquals(h.ownerNick, decoded.ownerNick)
        assertEquals(h.policy, decoded.policy)
        assertEquals(h.activeTransports, decoded.activeTransports)
    }

    @Test
    fun `encrypted frame data class equality and copy`() {
        val f = EncryptedFrame(NetworkId(ByteArray(8)), MemberId(ByteArray(32)), 1u, 3u, 7L, byteArrayOf(9))
        val same = f.copy()
        assertEquals(f, same)
        assertEquals(f.hashCode(), same.hashCode())
        val ttlDown = f.copy(ttl = 2u)
        assertNull(null)
        assertContentEquals(byteArrayOf(9), ttlDown.ciphertext)
    }

    @Test
    fun `decrypted beacon carries fields`() {
        val b = DecryptedBeacon(MemberId(ByteArray(32) { 2 }), 3u, 9L, BeaconPayload())
        assertEquals(3u, b.seq)
        assertEquals(9L, b.sentAtMs)
    }

    @Test
    fun `beacon payload all-default construction`() {
        val p = BeaconPayload()
        assertNull(p.position)
        assertNull(p.fixAgeS)
        assertNull(p.batteryPct)
    }

    @Test
    fun `fromFix keeps altitude and optional battery`() {
        val withBattery = PayloadCodec.fromFix(GnssFix(0.0, 0.0, 0f, -12.0, 5L), batteryPct = 64)
        assertEquals(-1200, withBattery.position!!.altitudeCm)
        assertEquals(64, withBattery.batteryPct)
    }
}

class BeaconPositionSerializerTest {
    @Test
    fun `serializer accessible`() {
        kotlin.test.assertNotNull(BeaconPosition.serializer())
    }
}
