package com.icegood.findmeinwood.app

/**
 * Plain-Kotlin side of the app's own on/off switches (constitution P6): position sharing,
 * the master's discovery and Wi-Fi Direct "always host the group". Unlike the radio rows,
 * these flip at once: the tap writes the setting and the row reads it back.
 *
 * ```
 *  Share my position   on ──tap──▶ Prefs sharingPaused = true  ──▶ off (beacons stop)
 *  Discoverable        off ──tap──▶ SessionBus.setFindable(true) ──▶ on until findableUntil
 *                      (disabled while Bluetooth or Location blocks discovery)
 *  Always host group   off ──tap──▶ Prefs p2pGroupOwner = true  ──▶ on
 * ```
 */
internal object FeatureSwitches {
    const val SHARE_POSITION = "share position"
    const val DISCOVERABLE = "discoverable"
    const val ALWAYS_HOST = "always host"

    /** The master is discoverable while its findable window is still open. */
    fun discoverable(findableUntilMs: Long, nowMs: Long): Boolean = findableUntilMs > nowMs

    /** Debug log line for a tap: [on] is the state the switch asks for. */
    fun tapLog(feature: String, on: Boolean): String = "ui: switch $feature tapped, now ${if (on) "on" else "off"}"
}
