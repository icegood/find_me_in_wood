package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState

/** Plain-language status strings for the screens; pure, unit-tested on the JVM. */
internal object UiText {
    fun name(t: TransportId) = when (t) {
        TransportId.BLUETOOTH -> "Bluetooth"
        TransportId.WIFI_DIRECT -> "Wi-Fi Direct"
        TransportId.LORA -> "LoRa"
        TransportId.INTERNET -> "Messenger"
        TransportId.SMS -> "SMS"
    }

    /** How a member is currently reached, for the member list. */
    fun via(t: TransportId?): String = if (t == null) "via other members" else "via ${name(t)}"

    private fun state(s: RadioState) = when (s) {
        RadioState.READY -> "connected"
        RadioState.SCANNING -> "searching"
        RadioState.CONNECTING -> "connecting"
        RadioState.LINK_DOWN -> "off or unavailable"
        RadioState.STOPPED -> "stopped"
    }

    /**
     * One line per radio, Bluetooth first. LoRa (optional external node) is only listed once
     * it works, so a missing node is not an error; the messenger carrier is not a radio.
     */
    fun radios(states: Map<TransportId, RadioState>): List<String> =
        states.entries
            .filter { (t, s) -> t != TransportId.INTERNET && t != TransportId.SMS && (t != TransportId.LORA || s == RadioState.READY) }
            .sortedBy { it.key.ordinal.let { o -> if (it.key == TransportId.BLUETOOTH) -1 else o } }
            .map { (t, s) -> "${name(t)}: ${state(s)}" }

    /** The active network's findability, e.g. "findable by new members: 7 min left". */
    fun findable(untilMs: Long, nowMs: Long): String {
        val left = untilMs - nowMs
        return if (left <= 0) {
            "not findable by new members (members still reach each other)"
        } else {
            "findable by new members: ${(left + 59_999) / 60_000} min left"
        }
    }

    /**
     * FR-8.8: what stops discovery on this phone: networks are found over Bluetooth, and
     * Android hides nearby phones while Location is off. [finding] words it for the joining
     * phone, otherwise for the master who should be found. Null when nothing does.
     */
    fun discoveryBlocked(bluetoothOn: Boolean, locationOn: Boolean, finding: Boolean): String? {
        val off = listOfNotNull("Bluetooth".takeIf { !bluetoothOn }, "Location".takeIf { !locationOn })
        if (off.isEmpty()) return null
        val what = off.joinToString(" and ") + if (off.size == 1) " is off" else " are off"
        return if (finding) "$what: no network nearby can show up here."
        else "Nobody can find this network: $what."
    }

    /** How a member can be reached, from their card: "Bluetooth · Wi-Fi Direct · SMS +380…". */
    fun offers(offers: List<com.icegood.findmeinwood.core.model.LinkOffer>): String =
        if (offers.isEmpty()) "links not announced yet"
        else offers.joinToString(" · ") { o -> name(o.t) + (o.addr?.let { " $it" } ?: "") }

    /** When a member was last heard on any radio. */
    fun heard(lastSeenMs: Long?, nowMs: Long): String {
        if (lastSeenMs == null) return "not heard yet"
        val s = ((nowMs - lastSeenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "heard just now"
            s < 3600 -> "heard ${s / 60} min ago"
            else -> "heard ${s / 3600} h ago"
        }
    }
}

/** FR-12.3: whether [launcherPackage] needs to be asked to put the app on the home screen. */
internal fun homeScreenNeedsOffer(launcherPackage: String): Boolean =
    launcherPackage.isNotEmpty() && listOf("huawei", "honor").none { launcherPackage.contains(it, ignoreCase = true) }
