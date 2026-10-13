package com.icegood.findmeinwood.core.crypto

import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.NetworkId

/**
 * What a phone puts in its Bluetooth advertisement (legacy 31-byte PDUs).
 *
 * - Members code, always: 6 bytes derived from a secret only members hold and the current
 *   10-minute window. Members recognise it and reconnect; anyone else sees bytes that
 *   change every window and name no network.
 * - Network info, only while the network is findable by new members: network id, join
 *   policy and (part of) the name, so a newcomer lists it from a scan without connecting.
 */
object BleAdvert {
    const val TAG_LEN = 6
    const val WINDOW_MS = 10 * 60_000L
    /** Manufacturer-data payload left in a scan response: 31 − (length, type, company id). */
    const val INFO_MAX = 27
    private const val ID_LEN = 8
    private val TAG_INFO = "fmiw-ble-member-tag".toByteArray()

    fun window(nowMs: Long): Long = Math.floorDiv(nowMs, WINDOW_MS)

    /** The members code for [window]. */
    fun memberTag(secret: ByteArray, window: Long): ByteArray {
        val w = ByteArray(8).also { FrameCodec.writeU64(it, 0, window) }
        return Hkdf.derive(secret, w, TAG_INFO, TAG_LEN)
    }

    /** True when [tag] is this network's code now, or in a neighbouring window (clock skew). */
    fun isMember(secret: ByteArray, tag: ByteArray?, nowMs: Long): Boolean {
        if (tag == null || tag.size != TAG_LEN) return false
        val w = window(nowMs)
        return (w - 1..w + 1).any { memberTag(secret, it).contentEquals(tag) }
    }

    data class Info(val networkId: NetworkId, val policy: JoinPolicy, val name: String?)

    /** [networkId 8][policy 1][name, UTF-8, cut to fit on a character boundary]. */
    fun encodeInfo(info: Info): ByteArray {
        require(info.networkId.bytes.size == ID_LEN) { "network id must be $ID_LEN bytes" }
        val name = fit(info.name.orEmpty(), INFO_MAX - ID_LEN - 1)
        return info.networkId.bytes + byteArrayOf(info.policy.ordinal.toByte()) + name
    }

    fun decodeInfo(bytes: ByteArray?): Info? {
        if (bytes == null || bytes.size < ID_LEN + 1) return null
        val policy = JoinPolicy.entries.getOrNull(bytes[ID_LEN].toInt()) ?: return null
        val name = bytes.copyOfRange(ID_LEN + 1, bytes.size).decodeToString().ifEmpty { null }
        return Info(NetworkId(bytes.copyOfRange(0, ID_LEN)), policy, name)
    }

    /** Company id of our manufacturer data (0xFFFF: reserved for tests and unassigned use). */
    const val COMPANY_ID = 0xFFFF

    data class Parsed(val tag: ByteArray?, val info: ByteArray?)

    /**
     * Finds our members code and network info in a raw advertisement + scan response.
     * Both are manufacturer data under [COMPANY_ID] (Android's parsed record keeps only
     * one per company id), told apart by length: the code is exactly [TAG_LEN] bytes.
     */
    fun parseRecord(raw: ByteArray?): Parsed {
        var tag: ByteArray? = null
        var info: ByteArray? = null
        var o = 0
        while (raw != null && o < raw.size) {
            val len = raw[o].toInt() and 0xFF
            if (len == 0 || o + 1 + len > raw.size) break
            val type = raw[o + 1].toInt() and 0xFF
            if (type == 0xFF && len >= 3) {
                val company = (raw[o + 2].toInt() and 0xFF) or ((raw[o + 3].toInt() and 0xFF) shl 8)
                val data = raw.copyOfRange(o + 4, o + 1 + len)
                if (company == COMPANY_ID) {
                    if (data.size == TAG_LEN) tag = data else if (data.size > ID_LEN) info = data
                }
            }
            o += 1 + len
        }
        return Parsed(tag, info)
    }

    private fun fit(s: String, max: Int): ByteArray {
        var out = s.toByteArray()
        var n = s.length
        while (out.size > max) out = s.substring(0, --n).toByteArray()
        return out
    }
}
