package com.icegood.findmeinwood.transport.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.icegood.findmeinwood.core.model.TransportId
import com.icegood.findmeinwood.transport.api.RadioState
import com.icegood.findmeinwood.transport.api.SendResult
import com.icegood.findmeinwood.transport.api.SessionConfig
import com.icegood.findmeinwood.transport.api.Transport
import com.icegood.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

const val ENVELOPE_PREFIX = "fmiw1:"
const val VIBER_PACKAGE = "com.viber.voip"
const val BEACON_MIME = "application/fmiw1"
const val BEACON_AUTHORITY_SUFFIX = ".beacons"

/**
 * Internet carrier via messenger share (US-9): user-in-loop, no INTERNET permission.
 * The envelope is the same AEAD wire frame, base64url — carriers are untrusted.
 *
 * FR-9.2/FR-9.4: [send] never opens a UI (this transport is never auto-selected); it only
 * buffers the newest envelope. The user shares it explicitly via [shareBeacon], which
 * exports a named `.fmiw1` file so messengers attach it instead of showing a bare text
 * blob the receiving device cannot classify.
 */
class ShareTransport(private val context: Context) : Transport {
    override val id: TransportId = TransportId.INTERNET

    override fun start(config: SessionConfig): Flow<TransportEvent> = flow {
        emit(TransportEvent.StateChanged(RadioState.READY))
    }

    override suspend fun stop() {}

    /** Newest frame, kept in memory only: written to a file when the user shares it. */
    @Volatile var latest: ByteArray? = null
        private set

    override suspend fun send(wireFrame: ByteArray): SendResult {
        // No file per beacon: that was a flash write every few seconds nobody read.
        latest = wireFrame
        return SendResult.HandedToUser
    }

    /** FR-9.1/FR-9.2: explicit, user-in-loop share of the newest envelope as a file. */
    fun shareBeacon(wireFrame: ByteArray): SendResult {
        val envelope = encodeEnvelope(wireFrame)
        val file = beaconFile(context, System.currentTimeMillis()).apply { writeText(envelope) }
        pruneBeacons(context)
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + BEACON_AUTHORITY_SUFFIX, file)
        }.getOrNull()

        val intent = Intent(Intent.ACTION_SEND).apply {
            if (uri != null) {
                type = BEACON_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
            } else {
                type = "text/plain"
            }
            putExtra(Intent.EXTRA_TEXT, envelope)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val viaViber = runCatching {
            context.startActivity(
                Intent(intent).apply {
                    `package` = VIBER_PACKAGE
                    uri?.let {
                        clipData = android.content.ClipData.newUri(context.contentResolver, file.name, it)
                    }
                },
            )
            SendResult.HandedToUser
        }
        if (viaViber.getOrNull() == SendResult.HandedToUser) return SendResult.HandedToUser
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Send position via…")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            SendResult.HandedToUser
        }.getOrDefault(SendResult.Failed("no share target available"))
    }

    companion object {
        /** Only the newest envelopes matter; older ones are superseded positions. */
        const val MAX_BEACON_FILES = 10

        fun beaconDir(context: Context): File {
            // Never fall back to a relative path (the process cwd) when there is no files dir.
            val base = context.filesDir ?: error("no files dir")
            return File(base, "beacons").apply { mkdirs() }
        }

        /**
         * [send] runs every beacon interval (down to 5 s): without pruning the directory
         * grows by thousands of files a day. Names embed a sortable timestamp.
         */
        fun pruneBeacons(context: Context, keep: Int = MAX_BEACON_FILES) {
            val files = beaconDir(context).listFiles { f -> f.isFile && f.name.endsWith(".fmiw1") } ?: return
            files.sortedByDescending { it.name }.drop(keep).forEach { it.delete() }
        }

        /** Human-meaningful name: what it is, which session, when. */
        fun beaconFileName(epochMs: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(epochMs))
            return "findmeinwood-beacon-$stamp.fmiw1"
        }

        fun beaconFile(context: Context, epochMs: Long): File =
            File(beaconDir(context), beaconFileName(epochMs))

        fun encodeEnvelope(wireFrame: ByteArray): String =
            ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(wireFrame)

        fun parseEnvelope(text: String): ByteArray? {
            val idx = text.indexOf(ENVELOPE_PREFIX)
            if (idx < 0) return null
            val raw = text.substring(idx + ENVELOPE_PREFIX.length).trim()
            val b64 = raw.lineSequence().first().trimEnd('.')
            if (b64.isEmpty()) return null
            return try {
                Base64.getUrlDecoder().decode(b64)
            } catch (_: Exception) {
                null
            }
        }
    }
}
