package com.icegood.findmeinwood.app

/** A system radio the app needs and shows as a switch row. */
internal enum class Radio { WIFI, BLUETOOTH, LOCATION }

/** Where radio switch rows appear. */
internal enum class RadioScreen {
    /** The Networks tab's "Radios" card: every radio. */
    NETWORKS,

    /** Under the red "discovery can't work" sentence: only what discovery needs. */
    DISCOVERY_BLOCKERS,
}

/** One radio switch row: [on] is the polled system state, [blocker] paints it in error colour. */
internal data class RadioRow(val radio: Radio, val on: Boolean, val blocker: Boolean)

/**
 * Decisions behind the radio switch rows, with no Android types (constitution P6): which
 * system intent a tap opens, and which rows a screen shows. A row never flips by itself;
 * its checked state is always the system state, and a tap only opens the system control:
 *
 * ```
 *  system state off ──tap──▶ intent (prompt / panel / settings) ──user turns it on──▶ poll: on
 *  system state on  ──tap──▶ intent (settings / panel)          ──user turns it off─▶ poll: off
 * ```
 */
internal object RadioToggles {
    const val BT_REQUEST_ENABLE = "android.bluetooth.adapter.action.REQUEST_ENABLE"
    const val BT_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
    const val WIFI_PANEL = "android.settings.panel.action.WIFI"
    const val WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val LOCATION_SETTINGS = "android.settings.LOCATION_SOURCE_SETTINGS"

    /** First SDK level with `Settings.Panel.ACTION_WIFI`. */
    const val WIFI_PANEL_SDK = 29

    /**
     * Intent actions to try, in order, when [radio]'s row is tapped on [sdk]: the caller
     * starts the first one, and the next only when starting it fails. Bluetooth asks the
     * system prompt first, with its settings page as the fallback.
     */
    fun actions(radio: Radio, sdk: Int): List<String> = when (radio) {
        Radio.BLUETOOTH -> listOf(BT_REQUEST_ENABLE, BT_SETTINGS)
        Radio.WIFI -> listOf(if (sdk >= WIFI_PANEL_SDK) WIFI_PANEL else WIFI_SETTINGS)
        Radio.LOCATION -> listOf(LOCATION_SETTINGS)
    }

    /** The radios [screen] shows, in display order. */
    fun radios(screen: RadioScreen): List<Radio> = when (screen) {
        RadioScreen.NETWORKS -> listOf(Radio.WIFI, Radio.BLUETOOTH, Radio.LOCATION)
        RadioScreen.DISCOVERY_BLOCKERS -> listOf(Radio.BLUETOOTH, Radio.LOCATION)
    }

    /** A radio that is off blocks what the app needs it for. */
    fun isBlocker(on: Boolean): Boolean = !on

    /** The rows for [screen], given the polled system state of each radio. */
    fun rows(screen: RadioScreen, state: (Radio) -> Boolean): List<RadioRow> =
        radios(screen).map { r -> state(r).let { on -> RadioRow(r, on, isBlocker(on)) } }

    /** The debug log line for a tap on [radio]'s row while the system state is [on]. */
    fun tapLog(radio: Radio, on: Boolean): String =
        "ui: radio switch ${radio.name.lowercase()} tapped, state ${if (on) "on" else "off"}"
}
