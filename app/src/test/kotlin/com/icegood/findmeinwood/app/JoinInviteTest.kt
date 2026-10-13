package com.icegood.findmeinwood.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinInviteTest {
    @Test
    fun `an invite survives the link, odd characters included`() {
        val invite = JoinInvite("Лісовий табір & co", "p@ss word+1/#?=")
        val uri = invite.toUri()
        assertTrue(uri.startsWith("fmiw://join?n="))
        assertTrue(!uri.contains(' ') && !uri.contains('+'), uri)
        assertEquals(invite, JoinInvite.parse(uri))
        assertEquals(invite, JoinInvite.parse("  $uri\n"))
    }

    @Test
    fun `other codes and invites without a password are not invites`() {
        assertNull(JoinInvite.parse(null))
        assertNull(JoinInvite.parse("https://example.com/?p=x"))
        assertNull(JoinInvite.parse("fmiw://join?n=camp"))
        assertNull(JoinInvite.parse("fmiw://join?n=camp&p="))
        assertNull(JoinInvite.parse("fmiw://join?p=%zz"))
    }

    @Test
    fun `a missing name falls back and junk parameters are ignored`() {
        assertEquals(JoinInvite("network", "x"), JoinInvite.parse("fmiw://join?p=x&junk&=y"))
        assertEquals(JoinInvite("camp", "x"), JoinInvite.parse("fmiw://join?n=%20camp%20&p=x"))
    }
}
