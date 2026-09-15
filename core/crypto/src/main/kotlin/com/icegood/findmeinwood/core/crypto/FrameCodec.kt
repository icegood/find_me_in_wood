package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.EncryptedFrame
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import java.io.EOFException

object FrameCodec {
    const val MAGIC: Int = 0x464D // "FM"
    const val VERSION: Int = 1
    const val HEADER_LEN = 2 + 1 + 8 + 32 + 4 + 1 + 8 + 2

    fun encode(f: EncryptedFrame): ByteArray {
        require(f.networkId.bytes.size == 8) { "networkId must be 8 bytes" }
        require(f.senderId.bytes.size == 32) { "senderId must be 32 bytes" }
        val out = ByteArray(HEADER_LEN + f.ciphertext.size)
        var o = 0
        out[o++] = (MAGIC ushr 8).toByte(); out[o++] = MAGIC.toByte()
        out[o++] = VERSION.toByte()
        System.arraycopy(f.networkId.bytes, 0, out, o, 8); o += 8
        System.arraycopy(f.senderId.bytes, 0, out, o, 32); o += 32
        writeU32(out, o, f.seq.toInt()); o += 4
        out[o++] = f.ttl.toByte()
        writeU64(out, o, f.sentAtMs); o += 8
        writeU16(out, o, f.ciphertext.size); o += 2
        System.arraycopy(f.ciphertext, 0, out, o, f.ciphertext.size)
        return out
    }

    fun decode(bytes: ByteArray): EncryptedFrame {
        if (bytes.size < HEADER_LEN) throw EOFException("frame truncated")
        var o = 0
        val magic = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)
        require(magic == MAGIC) { "bad magic" }; o += 2
        val ver = bytes[o++].toInt() and 0xFF
        require(ver == VERSION) { "unsupported version $ver" }
        val networkId = bytes.copyOfRange(o, o + 8); o += 8
        val senderId = bytes.copyOfRange(o, o + 32); o += 32
        val seq = readU32(bytes, o); o += 4
        val ttl = bytes[o++].toUByte()
        val sentAt = readU64(bytes, o); o += 8
        val len = readU16(bytes, o); o += 2
        if (bytes.size - o < len) throw EOFException("payload truncated")
        return EncryptedFrame(
            NetworkId(networkId), MemberId(senderId), seq, ttl, sentAt,
            bytes.copyOfRange(o, o + len),
        )
    }

    /**
     * AAD = magic|ver|networkId|senderId|seq|sentAt. Deliberately excludes ttl
     * (relays decrement it, FR-3.3) and len (unknown before encryption).
     */
    fun aad(networkId: NetworkId, senderId: MemberId, seq: UInt, sentAtMs: Long): ByteArray {
        val out = ByteArray(2 + 1 + 8 + 32 + 4 + 8)
        var o = 0
        out[o++] = (MAGIC ushr 8).toByte(); out[o++] = MAGIC.toByte()
        out[o++] = VERSION.toByte()
        System.arraycopy(networkId.bytes, 0, out, o, 8); o += 8
        System.arraycopy(senderId.bytes, 0, out, o, 32); o += 32
        writeU32(out, o, seq.toInt()); o += 4
        writeU64(out, o, sentAtMs)
        return out
    }

    fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }
    fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
    fun writeU64(b: ByteArray, o: Int, v: Long) {
        for (i in 0..7) b[o + i] = (v ushr ((7 - i) * 8)).toByte()
    }
    fun readU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun readU32(b: ByteArray, o: Int): UInt {
        var v = 0u
        for (i in 0..3) v = (v shl 8) or (b[o + i].toUInt() and 0xFFu)
        return v
    }
    fun readU64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
