package com.icegood.findmeinwood.transport.share

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Share target (FR-9.3): user shares the Viber message into the app; the envelope is
 * decoded and handed to the session sink registered by the app layer.
 */
object ShareInbox {
    /** Set by the app when a session is active; consumes decoded wire frames. */
    @Volatile
    var sink: ((ByteArray) -> Unit)? = null
}

class ShareInboxActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.readStreamEnvelope()
        val wire = text?.let { ShareTransport.parseEnvelope(it) }
        if (wire == null) {
            Toast.makeText(this, "Not a find-me-in-wood message", Toast.LENGTH_SHORT).show()
        } else {
            ShareInbox.sink?.invoke(wire)
                ?: Toast.makeText(this, "No active network session", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** FR-9.3: the envelope may arrive as a shared `.fmiw1` file, not only as text. */
    private fun Intent.readStreamEnvelope(): String? {
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
        } ?: data
        return uri?.let {
            runCatching {
                contentResolver.openInputStream(it)?.use { s -> s.readBytes() }?.decodeToString()
            }.getOrNull()
        }
    }
}
