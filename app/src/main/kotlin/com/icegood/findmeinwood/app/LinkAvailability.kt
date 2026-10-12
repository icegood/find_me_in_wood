package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.LinkFix
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkStatus
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState

/**
 * Every supported link on this phone, usable or greyed with the reason; pure, JVM-tested.
 * The usable ones are exactly what this phone offers in the LNP/3 handshake.
 */
internal object LinkAvailability {
    data class Inputs(
        val bluetoothOn: Boolean,
        val wifiOn: Boolean,
        val lora: RadioState?,
        val phoneNumber: String,
        val smsPermitted: Boolean,
        val sdk: Int,
        /** Phone is on a satellite (non-terrestrial) network now; null when unknown. */
        val onSatellite: Boolean?,
    )

    fun statuses(i: Inputs): List<LinkStatus> {
        val sms = when {
            i.phoneNumber.isBlank() -> LinkStatus("SMS", TransportId.SMS, "set this phone's number", LinkFix.SET_PHONE_NUMBER)
            !i.smsPermitted -> LinkStatus("SMS", TransportId.SMS, "allow the app to send and receive texts", LinkFix.ALLOW_SMS)
            else -> LinkStatus("SMS", TransportId.SMS, addr = i.phoneNumber.trim())
        }
        val satellite = when {
            i.sdk < 35 -> "not supported before Android 15"
            sms.reason != null -> "goes by SMS: set SMS up first"
            i.onSatellite == true -> "on satellite now: SMS goes through it"
            else -> "used by SMS by itself when the carrier switches to satellite (not now)"
        }
        return listOf(
            if (i.bluetoothOn) LinkStatus("Bluetooth", TransportId.BLUETOOTH)
            else LinkStatus("Bluetooth", TransportId.BLUETOOTH, "Bluetooth is off", LinkFix.TURN_ON_BLUETOOTH),
            if (i.wifiOn) LinkStatus("Wi-Fi Direct", TransportId.WIFI_DIRECT)
            else LinkStatus("Wi-Fi Direct", TransportId.WIFI_DIRECT, "Wi-Fi is off", LinkFix.TURN_ON_WIFI),
            if (i.lora == RadioState.READY) LinkStatus("LoRa", TransportId.LORA)
            else LinkStatus("LoRa", TransportId.LORA, "no LoRa node connected"),
            sms,
            LinkStatus(
                "Satellite", null,
                // Satellite is never a link of its own: the note just says how it is used.
                satellite,
                if (i.sdk >= 35) sms.fix else null,
            ),
        )
    }

    /** What this phone offers in the handshake: the usable links only. */
    fun offers(statuses: List<LinkStatus>): List<LinkOffer> =
        statuses.filter { it.transport != null && it.reason == null }.map { LinkOffer(it.transport!!, it.addr) }
}
