package com.icegood.findmeinwood.app

import android.content.Context
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState

/** Plain-language status strings for the screens, from the app's string resources. */
internal object UiText {
    fun name(ctx: Context, t: TransportId): String = ctx.getString(
        when (t) {
            TransportId.BLUETOOTH -> R.string.transport_bluetooth
            TransportId.WIFI_DIRECT -> R.string.transport_wifi_direct
            TransportId.LORA -> R.string.transport_lora
            TransportId.INTERNET -> R.string.transport_messenger
            TransportId.SMS -> R.string.transport_sms
        },
    )

    /** How a member is currently reached, for the member list. */
    fun via(ctx: Context, t: TransportId?): String =
        if (t == null) ctx.getString(R.string.via_other_members) else ctx.getString(R.string.via_transport, name(ctx, t))

    private fun state(ctx: Context, s: RadioState) = ctx.getString(
        when (s) {
            RadioState.READY -> R.string.radio_connected
            RadioState.SCANNING -> R.string.radio_searching
            RadioState.CONNECTING -> R.string.radio_connecting
            RadioState.LINK_DOWN -> R.string.radio_off
            RadioState.STOPPED -> R.string.radio_stopped
        },
    )

    /**
     * One line per radio, Bluetooth first. LoRa (optional external node) is only listed once
     * it works, so a missing node is not an error; the messenger carrier is not a radio.
     */
    fun radios(ctx: Context, states: Map<TransportId, RadioState>): List<String> =
        states.entries
            .filter { (t, s) -> t != TransportId.INTERNET && t != TransportId.SMS && (t != TransportId.LORA || s == RadioState.READY) }
            .sortedBy { it.key.ordinal.let { o -> if (it.key == TransportId.BLUETOOTH) -1 else o } }
            .map { (t, s) -> ctx.getString(R.string.radio_line, name(ctx, t), state(ctx, s)) }

    /** The active network's findability, e.g. "findable by new members: 7 min left". */
    fun findable(ctx: Context, untilMs: Long, nowMs: Long): String {
        val left = untilMs - nowMs
        return if (left <= 0) {
            ctx.getString(R.string.not_findable)
        } else {
            ctx.getString(R.string.findable_min_left, ((left + 59_999) / 60_000).toInt())
        }
    }

    /**
     * FR-8.8: what stops discovery on this phone: networks are found over Bluetooth, and
     * Android hides nearby phones while Location is off. [finding] words it for the joining
     * phone, otherwise for the master who should be found. Null when nothing does.
     */
    fun discoveryBlocked(ctx: Context, bluetoothOn: Boolean, locationOn: Boolean, finding: Boolean): String? {
        val what = when {
            !bluetoothOn && !locationOn -> R.string.off_bluetooth_location
            !bluetoothOn -> R.string.off_bluetooth
            !locationOn -> R.string.off_location
            else -> return null
        }
        // Starts the sentence when finding: some languages write it lowercase mid-sentence.
        return ctx.getString(
            if (finding) R.string.blocked_finding else R.string.blocked_master,
            ctx.getString(what),
        ).replaceFirstChar { it.titlecase() }
    }

    /** How a member can be reached, from their card: "Bluetooth · Wi-Fi Direct · SMS +380…". */
    fun offers(ctx: Context, offers: List<com.icegood.findmeinwood.core.model.LinkOffer>): String =
        if (offers.isEmpty()) ctx.getString(R.string.links_not_announced)
        else offers.joinToString(" · ") { o -> name(ctx, o.t) + (o.addr?.let { " $it" } ?: "") }

    /** When a member was last heard on any radio. */
    fun heard(ctx: Context, lastSeenMs: Long?, nowMs: Long): String {
        if (lastSeenMs == null) return ctx.getString(R.string.heard_never)
        val s = ((nowMs - lastSeenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> ctx.getString(R.string.heard_now)
            s < 3600 -> ctx.getString(R.string.heard_min_ago, (s / 60).toInt())
            else -> ctx.getString(R.string.heard_h_ago, (s / 3600).toInt())
        }
    }
}

/** FR-12.3: whether [launcherPackage] needs to be asked to put the app on the home screen. */
internal fun homeScreenNeedsOffer(launcherPackage: String): Boolean =
    launcherPackage.isNotEmpty() && listOf("huawei", "honor").none { launcherPackage.contains(it, ignoreCase = true) }
