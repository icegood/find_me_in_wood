package com.icegood.findmeinwood.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureSwitchesTest {
    @Test
    fun discoverableWhileTheWindowIsOpen() {
        assertFalse(FeatureSwitches.discoverable(0, 1_000), "not findable at all")
        assertTrue(FeatureSwitches.discoverable(2_000, 1_000))
        assertFalse(FeatureSwitches.discoverable(1_000, 1_000), "the window ends at findableUntil")
    }

    @Test
    fun discoverableSwitchFollowsSetFindable() {
        SessionBus.setFindable(true, now = 5_000)
        assertTrue(FeatureSwitches.discoverable(SessionBus.findableUntil.value, 5_000 + SessionBus.FINDABLE_MS - 1))
        assertFalse(FeatureSwitches.discoverable(SessionBus.findableUntil.value, 5_000 + SessionBus.FINDABLE_MS))
        SessionBus.setFindable(false, now = 5_000)
        assertFalse(FeatureSwitches.discoverable(SessionBus.findableUntil.value, 5_000))
    }

    @Test
    fun tapLogNamesFeatureAndNewState() {
        assertEquals("ui: switch share position tapped, now off", FeatureSwitches.tapLog(FeatureSwitches.SHARE_POSITION, false))
        assertEquals("ui: switch discoverable tapped, now on", FeatureSwitches.tapLog(FeatureSwitches.DISCOVERABLE, true))
        assertEquals("ui: switch always host tapped, now on", FeatureSwitches.tapLog(FeatureSwitches.ALWAYS_HOST, true))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class FeatureSwitchPrefsTest {
    private val ctx: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Test
    fun `sharing is paused per network and the switch path writes it`() {
        assertFalse(Prefs.sharingPaused(ctx, "aa"), "shared by default")
        SessionService.setSharingPaused(ctx, "aa", true)
        assertTrue(Prefs.sharingPaused(ctx, "aa"))
        assertFalse(Prefs.sharingPaused(ctx, "bb"), "other networks keep their own")
        SessionService.setSharingPaused(ctx, "aa", false)
        assertFalse(Prefs.sharingPaused(ctx, "aa"))
    }

    @Test
    fun `always host the group is off until switched on`() {
        assertFalse(Prefs.p2pGroupOwner(ctx))
        SessionService.setP2pGroupOwner(ctx, true)
        assertTrue(Prefs.p2pGroupOwner(ctx))
        SessionService.setP2pGroupOwner(ctx, false)
        assertFalse(Prefs.p2pGroupOwner(ctx))
    }
}
