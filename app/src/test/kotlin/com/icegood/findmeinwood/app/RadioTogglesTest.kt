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
    fun `bluetooth asks the system prompt first, settings as fallback, on any sdk`() {
        for (sdk in listOf(26, 29, 33, 36)) {
            assertEquals(
                listOf(RadioToggles.BT_REQUEST_ENABLE, RadioToggles.BT_SETTINGS),
                RadioToggles.actions(Radio.BLUETOOTH, sdk),
            )
        }
    }

    @Test
    fun `wifi opens the panel from 29, the settings page before`() {
        assertEquals(listOf(RadioToggles.WIFI_SETTINGS), RadioToggles.actions(Radio.WIFI, 28))
        assertEquals(listOf(RadioToggles.WIFI_PANEL), RadioToggles.actions(Radio.WIFI, 29))
        assertEquals(listOf(RadioToggles.WIFI_PANEL), RadioToggles.actions(Radio.WIFI, 35))
    }

    @Test
    fun `location opens the location source settings on any sdk`() {
        assertEquals(listOf(RadioToggles.LOCATION_SETTINGS), RadioToggles.actions(Radio.LOCATION, 26))
        assertEquals(listOf(RadioToggles.LOCATION_SETTINGS), RadioToggles.actions(Radio.LOCATION, 35))
    }

    @Test
    fun `rows per screen`() {
        assertEquals(listOf(Radio.WIFI, Radio.BLUETOOTH, Radio.LOCATION), RadioToggles.radios(RadioScreen.NETWORKS))
        assertEquals(listOf(Radio.BLUETOOTH, Radio.LOCATION), RadioToggles.radios(RadioScreen.DISCOVERY_BLOCKERS))
    }

    @Test
    fun `off radios are blockers, rows carry the polled state`() {
        assertTrue(RadioToggles.isBlocker(on = false))
        assertFalse(RadioToggles.isBlocker(on = true))
        val rows = RadioToggles.rows(RadioScreen.NETWORKS) { it != Radio.BLUETOOTH }
        assertEquals(
            listOf(
                RadioRow(Radio.WIFI, on = true, blocker = false),
                RadioRow(Radio.BLUETOOTH, on = false, blocker = true),
                RadioRow(Radio.LOCATION, on = true, blocker = false),
            ),
            rows,
        )
        val blockers = RadioToggles.rows(RadioScreen.DISCOVERY_BLOCKERS) { false }
        assertEquals(listOf(Radio.BLUETOOTH, Radio.LOCATION), blockers.map { it.radio })
        assertTrue(blockers.all { it.blocker && !it.on })
    }
}
