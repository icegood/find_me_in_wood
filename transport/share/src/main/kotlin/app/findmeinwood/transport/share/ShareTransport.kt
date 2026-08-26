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
        // Contract: must not throw. Viber first, then any text-capable app (FR-9.1).
        val viaViber = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    `package` = VIBER_PACKAGE
                    putExtra(Intent.EXTRA_TEXT, envelope)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            SendResult.HandedToUser
        }
        if (viaViber.getOrNull() == SendResult.HandedToUser) return SendResult.HandedToUser
        return runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, envelope)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    "Send position via…",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            SendResult.HandedToUser
        }.getOrDefault(SendResult.Failed("no share target available"))
    }

    companion object {
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
