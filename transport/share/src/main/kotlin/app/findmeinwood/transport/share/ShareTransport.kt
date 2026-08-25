package app.findmeinwood.transport.share

import android.content.Context
import android.content.Intent
import app.findmeinwood.core.model.EncryptedFrame
import app.findmeinwood.core.model.TransportId
import app.findmeinwood.transport.api.RadioState
import app.findmeinwood.transport.api.SendResult
import app.findmeinwood.transport.api.SessionConfig
import app.findmeinwood.transport.api.Transport
import app.findmeinwood.transport.api.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Base64

const val ENVELOPE_PREFIX = "fmiw1:"
const val VIBER_PACKAGE = "com.viber.voip"

/**
 * Internet carrier via messenger share (US-9): user-in-loop, no INTERNET permission.
 * The envelope is the same AEAD wire frame, base64url — carriers are untrusted.
 */
class ShareTransport(private val context: Context) : Transport {
    override val id: TransportId = TransportId.INTERNET

    override fun start(config: SessionConfig): Flow<TransportEvent> = flow {
        emit(TransportEvent.StateChanged(RadioState.READY))
    }

    override suspend fun stop() {}

    override suspend fun send(wireFrame: ByteArray): SendResult {
        val envelope = encodeEnvelope(wireFrame)
        val viber = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            `package` = VIBER_PACKAGE
            putExtra(Intent.EXTRA_TEXT, envelope)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(viber)
            SendResult.HandedToUser
        } catch (_: Exception) {
            // Viber absent: generic chooser — Telegram, SMS, any text-capable app (FR-9.1)
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, envelope)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "Send position via…",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chooser)
                SendResult.HandedToUser
            } catch (e: Exception) {
                SendResult.Failed("no share target available")
            }
        }
    }

    companion object {
        fun encodeEnvelope(wireFrame: ByteArray): String =
            ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(wireFrame)

        fun parseEnvelope(text: String): ByteArray? {
            val idx = text.indexOf(ENVELOPE_PREFIX)
            if (idx < 0) return null
            val raw = text.substring(idx + ENVELOPE_PREFIX.length).trim()
            val b64 = raw.lineSequence().first().trimEnd('.')
            return try {
                Base64.getUrlDecoder().decode(b64)
            } catch (_: Exception) {
                null
            }
        }
    }
}
