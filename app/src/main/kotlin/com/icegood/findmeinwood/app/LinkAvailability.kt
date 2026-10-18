package com.icegood.findmeinwood.app

import android.content.Context
import com.icegood.findmeinwood.core.model.LinkFix
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.LinkStatus
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState

/**
 * Every supported link on this phone, usable or greyed with the reason.
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

    fun statuses(ctx: Context, i: Inputs): List<LinkStatus> {
        fun s(id: Int) = ctx.getString(id)
        val smsLabel = s(R.string.transport_sms)
        val sms = when {
            i.phoneNumber.isBlank() -> LinkStatus(smsLabel, TransportId.SMS, s(R.string.link_set_number), LinkFix.SET_PHONE_NUMBER)
            !i.smsPermitted -> LinkStatus(smsLabel, TransportId.SMS, s(R.string.link_allow_sms), LinkFix.ALLOW_SMS)
            else -> LinkStatus(smsLabel, TransportId.SMS, addr = i.phoneNumber.trim())
        }
        val satellite = when {
            i.sdk < 35 -> s(R.string.satellite_unsupported)
            sms.reason != null -> s(R.string.satellite_needs_sms)
            i.onSatellite == true -> s(R.string.satellite_now)
            else -> s(R.string.satellite_not_now)
        }
        val bt = s(R.string.transport_bluetooth)
        val wd = s(R.string.transport_wifi_direct)
        val lora = s(R.string.transport_lora)
        return listOf(
            if (i.bluetoothOn) LinkStatus(bt, TransportId.BLUETOOTH)
            else LinkStatus(bt, TransportId.BLUETOOTH, s(R.string.bluetooth_is_off), LinkFix.TURN_ON_BLUETOOTH),
            if (i.wifiOn) LinkStatus(wd, TransportId.WIFI_DIRECT)
            else LinkStatus(wd, TransportId.WIFI_DIRECT, s(R.string.wifi_is_off), LinkFix.TURN_ON_WIFI),
            if (i.lora == RadioState.READY) LinkStatus(lora, TransportId.LORA)
            else LinkStatus(lora, TransportId.LORA, s(R.string.no_lora_node)),
            sms,
            LinkStatus(
                s(R.string.satellite), null,
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
