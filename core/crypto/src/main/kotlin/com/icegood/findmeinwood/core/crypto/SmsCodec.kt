package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Position report for the LNP/2 SMS link (FR-3.9), sized for one SMS — the one channel an
 * app can push through a satellite link (direct-to-cell / carrier satellite SMS), and
 * through a single bar of 2G.
 *
 * Text form (GSM-7 safe, ~68 chars, one SMS): `FMIW1:` + base64url(frame)
 *
 * Frame, 47 bytes:
 *   0  flags     1   high nibble = version (1), low nibble reserved (0)
 *   1  netTag    2   first bytes of the network id (which key to try)
 *   3  sender    8   first bytes of the sender's member id
 *  11  seq       4   u32, strictly increasing per sender
 *  15  ct       16   AES-256-GCM of: time u32 (epoch s) | latE7 i32 | lonE7 i32 |
 *                    accuracy u16 (m) | battery u8 (255 = unknown) | reserved u8
 *  31  tag      16
 *
 * No nonce is transmitted: it is sender(8) | seq(4), unique as long as a sender never
 * reuses a seq (the session seeds seq from the clock). Bytes 0..14 are the AAD, so the
 * flags, network tag, sender and seq cannot be altered. The key is derived from the
 * network traffic key with its own HKDF label, so SMS and radio frames never share it.
 */
object SmsCodec {
    const val PREFIX = "FMIW1:"
    private const val VERSION = 1
    private const val HEADER = 1 + 2 + 8 + 4
    private const val PLAIN = 4 + 4 + 4 + 2 + 1 + 1
    private const val TAG_BYTES = 16
    const val FRAME_BYTES = HEADER + PLAIN + TAG_BYTES
    const val BATTERY_UNKNOWN = 255
    private val LABEL = "fmiw-sms-link".toByteArray()

    data class Report(
        val networkTag: ByteArray,
        /** First 8 bytes of the sender's member id. */
        val senderPrefix: ByteArray,
        val seq: UInt,
        val timeS: Long,
        val latE7: Int,
        val lonE7: Int,
        val accuracyM: Int,
        /** 0..100, or [BATTERY_UNKNOWN]. */
        val batteryPct: Int,
    ) {
        fun isFrom(member: MemberId): Boolean = member.bytes.copyOf(8).contentEquals(senderPrefix)
    }

    fun smsKey(trafficKey: ByteArray): ByteArray = Hkdf.derive(trafficKey, ByteArray(32), LABEL, 32)

    fun networkTag(networkId: NetworkId): ByteArray = networkId.bytes.copyOf(2)

    fun encode(
        trafficKey: ByteArray,
        networkId: NetworkId,
        sender: MemberId,
        seq: UInt,
        timeS: Long,
        latE7: Int,
        lonE7: Int,
        accuracyM: Int,
        batteryPct: Int?,
    ): String {
        val header = ByteArray(HEADER)
        header[0] = (VERSION shl 4).toByte()
        networkTag(networkId).copyInto(header, 1)
        sender.bytes.copyInto(header, 3, 0, 8)
        FrameCodec.writeU32(header, 11, seq.toInt())

        val plain = ByteArray(PLAIN)
        FrameCodec.writeU32(plain, 0, timeS.toInt())
        FrameCodec.writeU32(plain, 4, latE7)
        FrameCodec.writeU32(plain, 8, lonE7)
        FrameCodec.writeU16(plain, 12, accuracyM.coerceIn(0, 0xFFFF))
        plain[14] = (batteryPct?.coerceIn(0, 100) ?: BATTERY_UNKNOWN).toByte()

        val ct = cipher(Cipher.ENCRYPT_MODE, trafficKey, header).doFinal(plain)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(header + ct)
    }

    /** True when [text] contains a report (cheap check before any decryption). */
    fun looksLikeReport(text: String): Boolean = text.contains(PREFIX)

    /** The raw frame inside an SMS body, or null when there is none. */
    fun frameOf(text: String): ByteArray? {
        val at = text.indexOf(PREFIX)
        if (at < 0) return null
        val b64 = text.substring(at + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        val frame = runCatching { Base64.getUrlDecoder().decode(b64) }.getOrNull() ?: return null
        return frame.takeIf { it.size == FRAME_BYTES && (it[0].toInt() ushr 4) and 0x0F == VERSION }
    }

    /** Network tag of a frame, to pick candidate networks before decrypting. */
    fun networkTagOf(frame: ByteArray): ByteArray = frame.copyOfRange(1, 3)

    /** Decrypts [frame] with a network's traffic key; null when it is not for that network or forged. */
    fun decode(frame: ByteArray, trafficKey: ByteArray): Report? {
        if (frame.size != FRAME_BYTES) return null
        val header = frame.copyOfRange(0, HEADER)
        val plain = runCatching {
            cipher(Cipher.DECRYPT_MODE, trafficKey, header).doFinal(frame, HEADER, frame.size - HEADER)
        }.getOrNull() ?: return null
        val battery = plain[14].toInt() and 0xFF
        return Report(
            networkTag = header.copyOfRange(1, 3),
            senderPrefix = header.copyOfRange(3, 11),
            seq = FrameCodec.readU32(header, 11),
            timeS = FrameCodec.readU32(plain, 0).toLong(),
            latE7 = FrameCodec.readU32(plain, 4).toInt(),
            lonE7 = FrameCodec.readU32(plain, 8).toInt(),
            accuracyM = FrameCodec.readU16(plain, 12),
            batteryPct = battery,
        )
    }

    private fun cipher(mode: Int, trafficKey: ByteArray, header: ByteArray): Cipher {
        // nonce = sender(8) | seq(4): unique per (sender, seq) under this network's key
        val nonce = header.copyOfRange(3, 15)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(smsKey(trafficKey), "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(header)
        }
    }
}
