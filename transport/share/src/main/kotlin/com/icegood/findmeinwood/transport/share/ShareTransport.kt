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

    override suspend fun send(wireFrame: ByteArray): SendResult {
        val envelope = encodeEnvelope(wireFrame)
        return runCatching {
            beaconFile(context, System.currentTimeMillis()).writeText(envelope)
            SendResult.HandedToUser
        }.getOrDefault(SendResult.Failed("cannot buffer envelope"))
    }

    /** FR-9.1/FR-9.2: explicit, user-in-loop share of the newest envelope as a file. */
    fun shareBeacon(wireFrame: ByteArray): SendResult {
        val envelope = encodeEnvelope(wireFrame)
        val file = beaconFile(context, System.currentTimeMillis()).apply { writeText(envelope) }
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
        fun beaconDir(context: Context): File =
            File(context.filesDir, "beacons").apply { mkdirs() }

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
