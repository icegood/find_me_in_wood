package com.icegood.findmeinwood.transport.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DebugLogTest {
    @AfterTest
    fun reset() { DebugLog.sink = null }

    @Test
    fun `silent without a sink, messages are not even built`() {
        var built = 0
        DebugLog.d { built++; "x" }
        assertEquals(0, built)
        val got = mutableListOf<String>()
        DebugLog.sink = { got += it }
        DebugLog.d { "hello" }
        assertEquals(listOf("hello"), got)
        assertEquals("icegood.fmiw", DebugLog.TAG)
    }
}
