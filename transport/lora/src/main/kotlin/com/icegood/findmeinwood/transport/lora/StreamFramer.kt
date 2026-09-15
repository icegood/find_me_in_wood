package com.icegood.findmeinwood.transport.lora

/**
 * Stream framing over the node byte pipe (lora-node.wsd):
 * 0x46 0x4D magic + u16be(len) + frame; resyncs on magic after reconnect garbage.
 * Pure Kotlin, JVM-testable.
 */
class StreamFramer {
    private var state = State.MAGIC0
    private var len = 0
    private var acc = ByteArray(0)
    private var got = 0

    private enum class State { MAGIC0, MAGIC1, LEN_HI, LEN_LO, PAYLOAD }

    /** Feeds stream bytes; returns each complete frame. */
    fun offer(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            when (state) {
                State.MAGIC0 -> if (b == MAGIC0_BYTE) state = State.MAGIC1
                State.MAGIC1 -> state = when (b) {
                    MAGIC1_BYTE -> State.LEN_HI
                    MAGIC0_BYTE -> State.MAGIC1 // overlapping prefix: 0x46 0x46 0x4D...
                    else -> State.MAGIC0
                }
                State.LEN_HI -> { len = (b.toInt() and 0xFF) shl 8; state = State.LEN_LO }
                State.LEN_LO -> {
                    len = len or (b.toInt() and 0xFF)
                    if (len in 1..MAX_FRAME) { acc = ByteArray(len); got = 0; state = State.PAYLOAD }
                    else state = State.MAGIC0
                }
                State.PAYLOAD -> {
                    val n = minOf(bytes.size - i, len - got)
                    bytes.copyInto(acc, got, i, i + n)
                    got += n
                    i += n - 1 // loop increments
                    if (got == len) { out += acc.copyOf(); state = State.MAGIC0 }
                }
            }
            i++
        }
        return out
    }

    companion object {
        const val MAGIC0_BYTE: Byte = 0x46
        const val MAGIC1_BYTE: Byte = 0x4D
        const val MAX_FRAME = 65_507

        fun packet(wire: ByteArray): ByteArray {
            val out = ByteArray(4 + wire.size)
            out[0] = MAGIC0_BYTE; out[1] = MAGIC1_BYTE
            out[2] = ((wire.size ushr 8) and 0xFF).toByte()
            out[3] = (wire.size and 0xFF).toByte()
            wire.copyInto(out, 4)
            return out
        }
    }
}
