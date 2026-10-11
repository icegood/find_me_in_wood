package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState

/** Plain-language status strings for the screens; pure, unit-tested on the JVM. */
internal object UiText {
    private fun name(t: TransportId) = when (t) {
        TransportId.BLUETOOTH -> "Bluetooth"
        TransportId.WIFI_DIRECT -> "Wi-Fi Direct"
        TransportId.LORA -> "LoRa"
        TransportId.INTERNET -> "Messenger"
    }

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
            .filter { (t, s) -> t != TransportId.INTERNET && (t != TransportId.LORA || s == RadioState.READY) }
            .sortedBy { it.key.ordinal.let { o -> if (it.key == TransportId.BLUETOOTH) -1 else o } }
            .map { (t, s) -> "${name(t)}: ${state(s)}" }
}
