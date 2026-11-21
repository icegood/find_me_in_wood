package com.icegood.findmeinwood.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioTogglesTest {
    @Test
    fun `action strings match the platform constants`() {
        assertEquals(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE, RadioToggles.BT_REQUEST_ENABLE)
        assertEquals(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, RadioToggles.BT_SETTINGS)
        assertEquals(android.provider.Settings.Panel.ACTION_WIFI, RadioToggles.WIFI_PANEL)
        assertEquals(android.provider.Settings.ACTION_WIFI_SETTINGS, RadioToggles.WIFI_SETTINGS)
        assertEquals(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, RadioToggles.LOCATION_SETTINGS)
    }

    @Test
    fun `bluetooth off asks the system prompt first, settings as fallback, on any sdk`() {
        for (sdk in listOf(26, 29, 33, 36)) {
            assertEquals(
                listOf(RadioToggles.BT_REQUEST_ENABLE, RadioToggles.BT_SETTINGS),
                RadioToggles.actions(Radio.BLUETOOTH, sdk, on = false),
            )
        }
    }

    @Test
    fun `bluetooth on opens its settings, where it can be turned off`() {
        for (sdk in listOf(26, 29, 33, 36)) {
            assertEquals(listOf(RadioToggles.BT_SETTINGS), RadioToggles.actions(Radio.BLUETOOTH, sdk, on = true))
        }
    }

    @Test
    fun `wifi opens the panel from 29 with settings as fallback, the settings page before`() {
        for (on in listOf(false, true)) {
            assertEquals(listOf(RadioToggles.WIFI_SETTINGS), RadioToggles.actions(Radio.WIFI, 28, on))
            val panel = listOf(RadioToggles.WIFI_PANEL, RadioToggles.WIFI_SETTINGS)
            assertEquals(panel, RadioToggles.actions(Radio.WIFI, 29, on))
            assertEquals(panel, RadioToggles.actions(Radio.WIFI, 35, on))
        }
    }

    @Test
    fun `open starts the first action that starts and stops there`() {
        val tried = mutableListOf<String>()
        assertTrue(RadioToggles.open(listOf("a", "b")) { tried += it; true })
        assertEquals(listOf("a"), tried, "the fallback is not tried once the first started")
        tried.clear()
        assertTrue(RadioToggles.open(listOf("a", "b")) { tried += it; it == "b" })
        assertEquals(listOf("a", "b"), tried, "the fallback is tried when the first fails")
        assertFalse(RadioToggles.open(listOf("a", "b")) { false }, "none started")
        assertFalse(RadioToggles.open(emptyList()) { true })
    }

    @Test
    fun `location opens the location source settings on any sdk`() {
        assertEquals(listOf(RadioToggles.LOCATION_SETTINGS), RadioToggles.actions(Radio.LOCATION, 26, on = false))
        assertEquals(listOf(RadioToggles.LOCATION_SETTINGS), RadioToggles.actions(Radio.LOCATION, 35, on = true))
    }

    @Test
    fun `rows per screen`() {
        assertEquals(listOf(Radio.WIFI, Radio.BLUETOOTH, Radio.LOCATION), RadioToggles.radios(RadioScreen.NETWORKS))
        assertEquals(listOf(Radio.BLUETOOTH, Radio.LOCATION), RadioToggles.radios(RadioScreen.DISCOVERY_BLOCKERS))
    }

    @Test
    fun `off radios are blockers, rows carry the polled state`() {
        assertTrue(RadioRow(Radio.WIFI, on = false).blocker)
        assertFalse(RadioRow(Radio.WIFI, on = true).blocker)
        val rows = RadioToggles.rows(RadioScreen.NETWORKS) { it != Radio.BLUETOOTH }
        assertEquals(
            listOf(
                RadioRow(Radio.WIFI, on = true),
                RadioRow(Radio.BLUETOOTH, on = false),
                RadioRow(Radio.LOCATION, on = true),
            ),
            rows,
        )
        val blockers = RadioToggles.rows(RadioScreen.DISCOVERY_BLOCKERS) { false }
        assertEquals(listOf(Radio.BLUETOOTH, Radio.LOCATION), blockers.map { it.radio })
        assertTrue(blockers.all { it.blocker && !it.on })
    }

    @Test
    fun tapLogNamesRadioAndState() {
        assertEquals("ui: radio switch wifi tapped, state off", RadioToggles.tapLog(Radio.WIFI, on = false))
        assertEquals("ui: radio switch bluetooth tapped, state on", RadioToggles.tapLog(Radio.BLUETOOTH, on = true))
        assertEquals("ui: radio switch location tapped, state off", RadioToggles.tapLog(Radio.LOCATION, on = false))
    }
}
