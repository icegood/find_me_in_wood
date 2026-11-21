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

/** One radio switch row: [on] is the polled system state; an off radio is a [blocker], in error colour. */
internal data class RadioRow(val radio: Radio, val on: Boolean) {
    val blocker: Boolean get() = !on
}

/**
 * Decisions behind the radio switch rows, with no Android types (constitution P6): which
 * system intent a tap opens, and which rows a screen shows. A row never flips by itself;
 * its checked state is always the system state, and a tap only opens the system control:
 *
 * ```
 *  system state off ──tap──▶ intent (prompt / panel / settings) ──user turns it on──▶ poll: on
 *  system state on  ──tap──▶ intent (settings / panel)          ──user turns it off─▶ poll: off
 *
 *  Bluetooth off: enable prompt, settings as fallback.  Bluetooth on: settings (the prompt
 *  would close at once with nothing to ask).  Wi-Fi: panel from SDK 29, settings as fallback.
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
     * Intent actions to try, in order, when [radio]'s row is tapped on [sdk] while its system
     * state is [on]: the caller starts the first one, and the next only when starting it fails.
     * Bluetooth that is off asks the system prompt first, with its settings page as the fallback.
     */
    fun actions(radio: Radio, sdk: Int, on: Boolean): List<String> = when (radio) {
        Radio.BLUETOOTH -> if (on) listOf(BT_SETTINGS) else listOf(BT_REQUEST_ENABLE, BT_SETTINGS)
        Radio.WIFI -> if (sdk >= WIFI_PANEL_SDK) listOf(WIFI_PANEL, WIFI_SETTINGS) else listOf(WIFI_SETTINGS)
        Radio.LOCATION -> listOf(LOCATION_SETTINGS)
    }

    /** The radios [screen] shows, in display order. */
    fun radios(screen: RadioScreen): List<Radio> = when (screen) {
        RadioScreen.NETWORKS -> listOf(Radio.WIFI, Radio.BLUETOOTH, Radio.LOCATION)
        RadioScreen.DISCOVERY_BLOCKERS -> listOf(Radio.BLUETOOTH, Radio.LOCATION)
    }

    /** The rows for [screen], given the polled system state of each radio. */
    fun rows(screen: RadioScreen, state: (Radio) -> Boolean): List<RadioRow> =
        radios(screen).map { r -> RadioRow(r, state(r)) }

    /**
     * Runs [actions] in order through [start] (true when it started) until one starts;
     * false when none did.
     */
    fun open(actions: List<String>, start: (String) -> Boolean): Boolean = actions.any(start)

    /** The debug log line for a tap on [radio]'s row while the system state is [on]. */
    fun tapLog(radio: Radio, on: Boolean): String =
        "ui: radio switch ${radio.name.lowercase()} tapped, state ${if (on) "on" else "off"}"
}
