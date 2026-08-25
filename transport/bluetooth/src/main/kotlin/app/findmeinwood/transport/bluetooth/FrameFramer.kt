package app.findmeinwood.transport.bluetooth

/**
 * Wire framing over GATT ops: [u16be totalLen][frame bytes], split into
 * maxChunk-sized writes (bt-transport.wsd). Pure Kotlin, JVM-testable.
 */
object FrameFramer {
    fun frameToChunks(wire: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk > 2) { "maxChunk too small for length prefix" }
        val out = ByteArray(2 + wire.size)
        out[0] = ((wire.size ushr 8) and 0xFF).toByte()
        out[1] = (wire.size and 0xFF).toByte()
        wire.copyInto(out, 2)
        return out.toList().chunked(maxChunk).map { it.toByteArray() }
    }

    /** Reassembles chunk streams into complete wire frames (one stream per peer). */
    class Reassembler {
        private var expected = -1
        private var acc = ByteArray(0)

        /** Returns a complete wire frame when ready, else null. */
        fun offer(chunk: ByteArray): ByteArray? {
            var data = chunk
            if (expected < 0) {
                if (data.size < 2) return null
                expected = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                data = data.copyOfRange(2, data.size)
            }
            acc += data
            if (acc.size < expected) return null
            val frame = acc.copyOf(expected)
            val rest = acc.copyOfRange(expected, acc.size)
            resetWith(rest)
            return frame
        }

        private fun resetWith(leftover: ByteArray) {
            expected = -1
            acc = ByteArray(0)
            if (leftover.isNotEmpty()) offer(leftover) // next frame may start in same chunk
        }

        fun reset() {
            expected = -1
            acc = ByteArray(0)
        }
    }
}
