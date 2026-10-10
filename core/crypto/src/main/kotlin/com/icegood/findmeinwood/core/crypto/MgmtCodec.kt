package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.MgmtType
import com.icegood.findmeinwood.core.model.NetworkId
import java.io.EOFException
import java.security.KeyPair
import java.security.PublicKey

/**
 * Cleartext management frame (FR-8.1/FR-8.4): HELLO discovery and the join handshake.
 * Wire layout: "FM" | version=3 | type | netId(8) | senderId(32) | nonce(8)
 *              | payloadLen(2) | payload | keyLen(2) | senderKey | sigLen(2) | signature
 *
 * Every frame carries the sender's device public key (X.509) and a signature over the
 * header, payload and that key. A frame without a valid signature never verifies;
 * receivers pin the key per member id on first sight (trust-on-first-use).
 */
data class MgmtFrame(
    val type: MgmtType,
    val networkId: NetworkId,
    val senderId: MemberId,
    val nonce: Long,
    val payload: ByteArray,
    val signature: ByteArray,
    val senderKey: ByteArray = ByteArray(0),
)

private const val MGMT_VERSION = 3
private const val MGMT_HEADER = 2 + 1 + 1 + 8 + 32 + 8 + 2

object MgmtCodec {
    /** Signs [f] with [signer], whose public key replaces [MgmtFrame.senderKey]. */
    fun encode(f: MgmtFrame, signer: KeyPair): ByteArray {
        val key = signer.public.encoded
        require(f.payload.size <= 0xFFFF && key.size <= 0xFFFF) { "mgmt field too large" }
        val sig = Identity.sign(signer, signedBytes(f.type, f.networkId, f.senderId, f.nonce, f.payload, key))
            ?: ByteArray(0)
        val out = ByteArray(MGMT_HEADER + f.payload.size + 2 + key.size + 2 + sig.size)
        var o = 0
        out[o++] = (FrameCodec.MAGIC ushr 8).toByte(); out[o++] = FrameCodec.MAGIC.toByte()
        out[o++] = MGMT_VERSION.toByte()
        out[o++] = (f.type.ordinal + 1).toByte()
        System.arraycopy(f.networkId.bytes, 0, out, o, 8); o += 8
        System.arraycopy(f.senderId.bytes, 0, out, o, 32); o += 32
        FrameCodec.writeU64(out, o, f.nonce); o += 8
        FrameCodec.writeU16(out, o, f.payload.size); o += 2
        System.arraycopy(f.payload, 0, out, o, f.payload.size); o += f.payload.size
        FrameCodec.writeU16(out, o, key.size); o += 2
        System.arraycopy(key, 0, out, o, key.size); o += key.size
        FrameCodec.writeU16(out, o, sig.size); o += 2
        System.arraycopy(sig, 0, out, o, sig.size)
        return out
    }

    fun decode(bytes: ByteArray): MgmtFrame {
        if (bytes.size < MGMT_HEADER) throw EOFException("mgmt frame truncated")
        var o = 0
        val magic = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)
        require(magic == FrameCodec.MAGIC) { "bad magic" }; o += 2
        val ver = bytes[o++].toInt() and 0xFF
        require(ver == MGMT_VERSION) { "not a management frame ($ver)" }
        val typeCode = bytes[o++].toInt() and 0xFF
        require(typeCode in 1..MgmtType.entries.size) { "unknown mgmt type $typeCode" }
        val type = MgmtType.entries[typeCode - 1]
        val netId = bytes.copyOfRange(o, o + 8); o += 8
        val senderId = bytes.copyOfRange(o, o + 32); o += 32
        val nonce = FrameCodec.readU64(bytes, o); o += 8
        val len = FrameCodec.readU16(bytes, o); o += 2
        if (bytes.size - o < len) throw EOFException("mgmt payload truncated")
        val payload = bytes.copyOfRange(o, o + len); o += len
        if (bytes.size - o < 2) throw EOFException("mgmt key length missing")
        val keyLen = FrameCodec.readU16(bytes, o); o += 2
        if (bytes.size - o < keyLen) throw EOFException("mgmt key truncated")
        val key = bytes.copyOfRange(o, o + keyLen); o += keyLen
        if (bytes.size - o < 2) throw EOFException("mgmt signature length missing")
        val sigLen = FrameCodec.readU16(bytes, o); o += 2
        if (bytes.size - o < sigLen) throw EOFException("mgmt signature truncated")
        val sig = bytes.copyOfRange(o, o + sigLen)
        return MgmtFrame(type, NetworkId(netId), MemberId(senderId), nonce, payload, sig, key)
    }

    fun isMgmt(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == (FrameCodec.MAGIC ushr 8).toByte() &&
            bytes[1] == FrameCodec.MAGIC.toByte() && (bytes[2].toInt() and 0xFF) == MGMT_VERSION

    /**
     * true only when [f] is signed by [publicKey] and names that same key as its sender
     * key. Unsigned frames never verify.
     */
    fun verify(f: MgmtFrame, publicKey: PublicKey): Boolean =
        f.signature.isNotEmpty() &&
            f.senderKey.contentEquals(publicKey.encoded) &&
            Identity.verify(
                publicKey,
                signedBytes(f.type, f.networkId, f.senderId, f.nonce, f.payload, f.senderKey),
                f.signature,
            )

    /** The sender key the frame carries, or null when it is missing or unparseable. */
    fun senderKeyOf(f: MgmtFrame): PublicKey? =
        if (f.senderKey.isEmpty()) null else runCatching { KeyParse.publicKey(f.senderKey) }.getOrNull()

    /** FR-8.4: transcript binding — nonce echo makes replays detectable. */
    private fun signedBytes(
        type: MgmtType,
        netId: NetworkId,
        senderId: MemberId,
        nonce: Long,
        payload: ByteArray,
        senderKey: ByteArray,
    ): ByteArray = byteArrayOf(MGMT_VERSION.toByte(), (type.ordinal + 1).toByte()) + netId.bytes +
        senderId.bytes + ByteArray(8).also { FrameCodec.writeU64(it, 0, nonce) } +
        ByteArray(2).also { FrameCodec.writeU16(it, 0, payload.size) } + payload + senderKey
}
