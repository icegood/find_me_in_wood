package app.findmeinwood.transport.share

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `send hands envelope to viber`() = runBlocking<Unit> {
        val t = ShareTransport(appCtx)
        assertIs<SendResult.HandedToUser>(t.send(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `send falls back to chooser when viber absent`() = runBlocking<Unit> {
        val t = ShareTransport(FlakyContext(appCtx, failures = 1))
        assertIs<SendResult.HandedToUser>(t.send(byteArrayOf(9)))
    }

    @Test
    fun `send fails when no share target`() = runBlocking<Unit> {
        val t = ShareTransport(FlakyContext(appCtx, failures = Int.MAX_VALUE))
        assertIs<SendResult.Failed>(t.send(byteArrayOf(9)))
    }

    @Test
    fun `start emits ready once`() = runBlocking<Unit> {
        val cfg = SessionConfig(app.findmeinwood.core.model.NetworkId(ByteArray(8)), emptySet())
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
