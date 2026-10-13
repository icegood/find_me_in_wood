package com.icegood.findmeinwood.transport.share

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareSendAndInboxTest {
    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    /** Wraps the app context and fails startActivity [failures] first times. */
    private class FlakyContext(
        base: Context,
        private var failures: Int,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            if (failures > 0) { failures--; throw IllegalStateException("no carrier") }
            super.startActivity(intent)
        }

        override fun startActivity(intent: Intent, options: Bundle?) = startActivity(intent)
    }

    @Test
    fun `send keeps the newest frame in memory, opens no UI and writes no file`() = runBlocking<Unit> {
        val t = ShareTransport(FlakyContext(appCtx, failures = Int.MAX_VALUE))
        val dir = ShareTransport.beaconDir(appCtx)
        val before = dir.listFiles()?.size ?: 0
        // FR-9.2: beacons must never trigger a share sheet on their own.
        assertIs<SendResult.HandedToUser>(t.send(byteArrayOf(1, 2, 3)))
        assertTrue(t.latest!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(before, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `old beacon files are pruned when sharing`() = runBlocking<Unit> {
        val dir = ShareTransport.beaconDir(appCtx)
        repeat(ShareTransport.MAX_BEACON_FILES + 5) { i ->
            ShareTransport.beaconFile(appCtx, 1_700_000_000_000L + i * 1000L).writeText("old")
        }
        ShareTransport(appCtx).shareBeacon(byteArrayOf(4, 5, 6))
        val left = dir.listFiles()!!.filter { it.name.endsWith(".fmiw1") }
        assertEquals(ShareTransport.MAX_BEACON_FILES, left.size)
        // the newest (just shared) envelope survives
        assertTrue(left.any { ShareTransport.parseEnvelope(it.readText())?.contentEquals(byteArrayOf(4, 5, 6)) == true })
    }

    @Test
    fun `shareBeacon hands the file to viber`() = runBlocking<Unit> {
        val t = ShareTransport(appCtx)
        assertIs<SendResult.HandedToUser>(t.shareBeacon(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `shareBeacon falls back to chooser when viber absent`() = runBlocking<Unit> {
        val t = ShareTransport(FlakyContext(appCtx, failures = 1))
        assertIs<SendResult.HandedToUser>(t.shareBeacon(byteArrayOf(9)))
    }

    @Test
    fun `shareBeacon fails when no share target`() = runBlocking<Unit> {
        val t = ShareTransport(FlakyContext(appCtx, failures = Int.MAX_VALUE))
        assertIs<SendResult.Failed>(t.shareBeacon(byteArrayOf(9)))
    }

    @Test
    fun `start emits ready once`() = runBlocking<Unit> {
        val cfg = SessionConfig(com.icegood.findmeinwood.core.model.NetworkId(ByteArray(8)), emptySet())
        val ev = ShareTransport(appCtx).start(cfg).first() as TransportEvent.StateChanged
        assertEquals(RadioState.READY, ev.state)
    }

    @Test
    fun `parse envelope edge cases`() {
        assertNull(ShareTransport.parseEnvelope("fmiw1:"))
        assertNull(ShareTransport.parseEnvelope("no prefix here"))
        val wire = byteArrayOf(7, 7)
        val env = ShareTransport.encodeEnvelope(wire)
        assertEquals(wire.toList(), ShareTransport.parseEnvelope("msg\n$env.\nbye")!!.toList())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareInboxActivityTest {
    @Test
    fun `envelope handed to active sink`() {
        var received: ByteArray? = null
        ShareInbox.sink = { received = it }
        try {
            val wire = byteArrayOf(4, 5, 6)
            val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, ShareTransport.encodeEnvelope(wire))
            Robolectric.buildActivity(ShareInboxActivity::class.java, intent).setup()
            assertNotNull(received)
            assertEquals(wire.toList(), received!!.toList())
        } finally { ShareInbox.sink = null }
    }

    @Test
    fun `non envelope shows toast`() {
        ShareInbox.sink = { error("must not be called") }
        try {
            val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "just words")
            Robolectric.buildActivity(ShareInboxActivity::class.java, intent).setup()
            assertEquals("Not a find-me-in-wood message", ShadowToast.getTextOfLatestToast())
        } finally { ShareInbox.sink = null }
    }

    @Test
    fun `no active session shows toast`() {
        ShareInbox.sink = null
        val wire = byteArrayOf(1)
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, ShareTransport.encodeEnvelope(wire))
        Robolectric.buildActivity(ShareInboxActivity::class.java, intent).setup()
        assertEquals("No active network session", ShadowToast.getTextOfLatestToast())
    }
}
