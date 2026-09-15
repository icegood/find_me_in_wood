package com.icegood.findmeinwood.transport.lora

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StreamFramerTest {
    @Test
    fun `roundtrip single frame`() {
        val wire = ByteArray(150) { (it * 7).toByte() }
        val out = StreamFramer().offer(StreamFramer.packet(wire))
        assertEquals(1, out.size)
        assertContentEquals(wire, out[0])
    }

    @Test
    fun `byte-at-a-time delivery`() {
        val wire = ByteArray(40) { it.toByte() }
        val packet = StreamFramer.packet(wire)
        val framer = StreamFramer()
        val out = mutableListOf<ByteArray>()
        packet.forEach { b -> out += framer.offer(byteArrayOf(b)) }
        assertEquals(1, out.size)
        assertContentEquals(wire, out[0])
    }

    @Test
    fun `resyncs after garbage before magic`() {
        val wire = ByteArray(20) { 3 }
        val garbage = byteArrayOf(0, 0x46, 9, 9, 0x46) // partial magic noise
        val out = StreamFramer().offer(garbage + StreamFramer.packet(wire))
        assertEquals(1, out.size)
        assertContentEquals(wire, out[0])
    }

    @Test
    fun `two frames back to back`() {
        val f1 = ByteArray(30) { 1 }
        val f2 = ByteArray(60) { 2 }
        val bytes = StreamFramer.packet(f1) + StreamFramer.packet(f2)
        val out = StreamFramer().offer(bytes)
        assertEquals(2, out.size)
        assertContentEquals(f1, out[0])
        assertContentEquals(f2, out[1])
    }
}
