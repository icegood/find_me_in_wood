package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.BeaconPayload
import com.icegood.findmeinwood.core.model.DecryptedBeacon
import com.icegood.findmeinwood.core.model.MemberId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerTrackerTest {
    private var now = 1_000_000L
    private val tracker = PeerTracker(keepAliveMs = 60_000, nowMs = { now })
    private val peer = MemberId(ByteArray(32) { 7 })

    private fun beacon(seq: UInt, withPos: Boolean = true) = DecryptedBeacon(
        senderId = peer, seq = seq, sentAtMs = now,
        payload = BeaconPayload(
            position = if (withPos) com.icegood.findmeinwood.core.model.BeaconPosition(1, 2, 3, 4) else null,
        ),
    )

    @Test
    fun `beacon creates and updates peer`() {
        tracker.onBeacon(beacon(1u))
        assertEquals(1u, tracker.peersFlow.value[peer]!!.lastSeq)
        now += 1_000
        tracker.onBeacon(beacon(2u))
        assertEquals(2u, tracker.peersFlow.value[peer]!!.lastSeq)
    }

    @Test
    fun `out-of-order seq ignored`() {
        tracker.onBeacon(beacon(5u))
        tracker.onBeacon(beacon(4u))
        assertEquals(5u, tracker.peersFlow.value[peer]!!.lastSeq)
    }

    @Test
    fun `stale peer loses position then is purged`() {
        tracker.onBeacon(beacon(1u))
        now += 3 * 60_000 + 1
        tracker.tick()
        assertNull(tracker.peersFlow.value[peer]!!.lastPosition)
        assertTrue(tracker.peersFlow.value.containsKey(peer))
        now += 24 * 60 * 60 * 1000
        tracker.tick()
        assertTrue(!tracker.peersFlow.value.containsKey(peer))
    }
}

class BeaconSchedulerTest {
    @Test
    fun `emits on fix and periodic keepalives`() = runTest {
        var now = 0L
        val scheduler = BeaconScheduler(keepAliveMs = 60_000, nowMs = { now })
        val fixes = flowOf(fix(1), fix(2))
        val collected = scheduler.commands(fixes).take(5).toList()
        assertEquals(2, collected.count { it.fix != null })
        assertEquals(3, collected.count { it.fix == null })
    }

    private fun fix(i: Int) = com.icegood.findmeinwood.core.model.GnssFix(1.0 * i, 2.0, 1f, 3.0, i.toLong())
}
