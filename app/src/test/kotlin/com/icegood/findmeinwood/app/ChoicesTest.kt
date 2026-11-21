package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.ThemeMode
import com.icegood.findmeinwood.core.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoicesTest {
    @Test
    fun `tapping the selected segment changes nothing`() {
        assertNull(Choices.pick(ThemeMode.DARK, ThemeMode.DARK))
        assertNull(Choices.pick(WifiBand.GHZ_5, WifiBand.GHZ_5))
    }

    @Test
    fun `tapping another segment selects it`() {
        assertEquals(ThemeMode.LIGHT, Choices.pick(ThemeMode.SYSTEM, ThemeMode.LIGHT))
        WifiBand.entries.forEach { from ->
            WifiBand.entries.filter { it != from }.forEach { to -> assertEquals(to, Choices.pick(from, to)) }
        }
    }

    @Test
    fun `tap log names the setting and the value`() {
        assertEquals("ui: choice theme set to DARK", Choices.tapLog(Choices.THEME, ThemeMode.DARK.toString()))
        assertEquals("ui: choice wifi direct band set to 5 GHz", Choices.tapLog(Choices.WIFI_BAND, "5 GHz"))
    }
}
