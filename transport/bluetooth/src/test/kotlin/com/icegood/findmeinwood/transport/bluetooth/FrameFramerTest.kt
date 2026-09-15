package com.icegood.findmeinwood.transport.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class FrameFramerTest {
    @Test
    fun `chunk and reassemble across chunk boundaries`() {
        val wire = ByteArray(600) { (it * 3).toByte() }
        val chunks = FrameFramer.frameToChunks(wire, 244)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.size <= 244 })
        val r = FrameFramer.Reassembler()
        assertNull(r.offer(chunks[0]))
        assertNull(r.offer(chunks[1]))
        assertContentEquals(wire, r.offer(chunks[2])!!)
    }

    @Test
    fun `single small frame in one chunk`() {
        val wire = ByteArray(100) { 7 }
        val chunks = FrameFramer.frameToChunks(wire, 244)
        assertEquals(1, chunks.size)
        assertContentEquals(wire, FrameFramer.Reassembler().offer(chunks[0])!!)
    }

    @Test
    fun `two frames back to back reassembled in order`() {
        val f1 = ByteArray(50) { 1 }
        val f2 = ByteArray(70) { 2 }
        val r = FrameFramer.Reassembler()
        val out = mutableListOf<ByteArray>()
        (FrameFramer.frameToChunks(f1, 244) + FrameFramer.frameToChunks(f2, 244)).forEach {
            r.offer(it)?.let(out::add)
        }
        assertEquals(2, out.size)
        assertContentEquals(f1, out[0])
        assertContentEquals(f2, out[1])
    }

    @Test
    fun `reset clears partial state`() {
        val r = FrameFramer.Reassembler()
        val chunk = FrameFramer.frameToChunks(ByteArray(300), 244)[0]
        r.offer(chunk)
        r.reset()
        val wire = ByteArray(10) { 9 }
        assertContentEquals(wire, r.offer(FrameFramer.frameToChunks(wire, 244)[0])!!)
    }
}
