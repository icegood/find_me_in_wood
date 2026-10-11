package com.icegood.findmeinwood.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.icegood.findmeinwood.core.crypto.SosCodec
import com.icegood.findmeinwood.core.storage.SealedFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One SOS heard from someone — over SMS (possibly relayed by satellite) or over the mesh.
 * [senderHex] is the first 8 bytes of their member id, all an SMS report carries.
 */
@Serializable
data class SosAlert(
    val senderHex: String,
    val networkName: String,
    val name: String? = null,
    val latE7: Int,
    val lonE7: Int,
    val accuracyM: Int,
    val batteryPct: Int? = null,
    /** When the sender measured/sent it (epoch ms). */
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val seq: Long,
    val active: Boolean,
    val via: String,
)

/** Pure helpers, unit-tested on the JVM. */
internal object SosLogic {
    /**
     * Newer report from the same sender replaces the old one, by seq: SMS can arrive late,
     * twice or out of order, and SMS reports and mesh beacons share one per-sender counter.
     * Returns null when [alert] is a duplicate/older report.
     */
    fun upsert(list: List<SosAlert>, alert: SosAlert, max: Int = 50): List<SosAlert>? {
        val cur = list.firstOrNull { it.senderHex == alert.senderHex }
        if (cur != null && alert.seq <= cur.seq) return null
        val merged = alert.copy(name = alert.name ?: cur?.name)
        return (listOf(merged) + list.filterNot { it.senderHex == alert.senderHex }).take(max)
    }

    /**
     * Phone numbers from free text, separated by , ; or newlines. Spaces, dashes and
     * brackets inside a number ("+380 67 123-45-67") are formatting and are dropped.
     */
    fun parseNumbers(text: String): List<String> =
        text.split(',', ';', '\n')
            .map { raw -> raw.filter { it.isDigit() || it == '+' } }
            .filter { it.count(Char::isDigit) >= 5 }
            .distinct()

    /** Human-readable SOS for contacts without the app (plain text: the carrier can read it). */
    fun plainMessage(
        name: String,
        lat: Double?,
        lon: Double?,
        accuracyM: Int?,
        batteryPct: Int?,
        atMs: Long,
        cancelled: Boolean,
    ): String {
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(atMs))
        if (cancelled) return "$name is OK now, SOS cancelled ($time)."
        val where = if (lat != null && lon != null) {
            val p = "%.5f,%.5f".format(Locale.US, lat, lon)
            "at $p" + (accuracyM?.let { " (±$it m)" } ?: "") + " https://maps.google.com/?q=$p"
        } else {
            "position unknown"
        }
        val battery = batteryPct?.let { " Battery $it%." } ?: ""
        return "SOS! $name needs help, $where at $time.$battery"
    }
}

/** Caps how many SOS SMS go out per hour: satellite SMS is slow, limited and often paid. */
internal class SmsBudget(private val maxPerHour: Int) {
    private val sent = ArrayDeque<Long>()

    @Synchronized
    fun tryTake(nowMs: Long, count: Int = 1): Boolean {
        while (sent.isNotEmpty() && nowMs - sent.first() >= 3_600_000L) sent.removeFirst()
        if (sent.size + count > maxPerHour) return false
        repeat(count) { sent.addLast(nowMs) }
        return true
    }
}

/** SOS alerts heard so far, persisted sealed so they survive an app restart. */
object SosAlerts {
    private const val FILE = "sos_alerts.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val _alerts = MutableStateFlow<List<SosAlert>>(emptyList())
    val alerts: StateFlow<List<SosAlert>> = _alerts
    @Volatile private var loaded = false

    fun load(ctx: Context) = synchronized(this) {
        if (loaded) return
        loaded = true
        val bytes = SealedFiles.read(File(ctx.filesDir, FILE)) ?: return
        _alerts.value = runCatching {
            json.decodeFromString(ListSerializer(SosAlert.serializer()), bytes.decodeToString())
        }.getOrDefault(emptyList())
    }

    /** Adds/updates [alert]; true when it was new information (worth a notification). */
    fun upsert(ctx: Context, alert: SosAlert): Boolean = synchronized(this) {
        load(ctx)
        val next = SosLogic.upsert(_alerts.value, alert) ?: return false
        _alerts.value = next
        save(ctx)
        true
    }

    fun dismiss(ctx: Context, senderHex: String) = synchronized(this) {
        load(ctx)
        _alerts.value = _alerts.value.filterNot { it.senderHex == senderHex }
        save(ctx)
    }

    private fun save(ctx: Context) {
        runCatching {
            SealedFiles.write(
                File(ctx.filesDir, FILE),
                json.encodeToString(ListSerializer(SosAlert.serializer()), _alerts.value).toByteArray(),
            )
        }
    }
}

/** Sends SOS texts with the platform SMS stack (needs SEND_SMS). */
internal object SosSms {
    private const val TAG = "FMIW-SOS"

    /** Number of recipients the message was handed to the modem for. */
    fun send(ctx: Context, numbers: List<String>, body: String): Int {
        val sms = runCatching { ctx.getSystemService(SmsManager::class.java) }.getOrNull() ?: return 0
        var ok = 0
        for (n in numbers) {
            runCatching {
                val parts = sms.divideMessage(body)
                if (parts.size == 1) sms.sendTextMessage(n, null, body, null, null)
                else sms.sendMultipartTextMessage(n, null, parts, null, null)
                ok++
            }.onFailure { Log.w(TAG, "SMS to ${n.takeLast(3)} failed", it) }
        }
        return ok
    }

    /**
     * True when the phone is currently on a non-terrestrial (satellite) network — Android 15+
     * and READ_PHONE_STATE only; null when unknown.
     */
    fun onSatellite(ctx: Context): Boolean? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        ctx.getSystemService(TelephonyManager::class.java)?.serviceState?.isUsingNonTerrestrialNetwork
    }.getOrNull()
}

/** Loud notification for an incoming SOS; tapping it opens the map. */
internal object SosNotify {
    private const val CHANNEL = "sos_alerts"

    fun show(ctx: Context, alert: SosAlert) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Someone in your network needs help"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SOS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = alert.name ?: "member ${alert.senderHex.take(8)}"
        val text = if (alert.active) {
            "%.5f, %.5f ±%d m · via %s".format(Locale.US, alert.latE7 / 1e7, alert.lonE7 / 1e7, alert.accuracyM, alert.via)
        } else {
            "SOS cancelled — they are OK"
        }
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (alert.active) "SOS from $who" else "$who is OK")
            .setContentText(text)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(alert.senderHex.hashCode(), n) }
    }
}

/**
 * Incoming SMS: picks out SOS frames and decrypts them against every stored network. Works
 * without a running session — an SOS must get through even if the app was closed.
 */
class SmsSosReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        // Multipart texts arrive as several PDUs from the same sender: join them first.
        messages.groupBy { it.originatingAddress }.values.forEach { parts ->
            val body = parts.joinToString("") { it.messageBody.orEmpty() }
            if (SosCodec.looksLikeSos(body)) handle(context, body)
        }
    }

    private fun handle(ctx: Context, body: String) {
        val frame = SosCodec.frameOf(body) ?: return
        val tag = SosCodec.networkTagOf(frame)
        for (p in ProfileStore.load(ctx)) {
            val profile = ProfileStore.toProfile(p)
            if (!SosCodec.networkTag(profile.networkId).contentEquals(tag)) continue
            val r = SosCodec.decode(frame, profile.trafficKey) ?: continue
            if (r.isFrom(profile.myMemberId)) return // our own report echoed back
            val alert = SosAlert(
                senderHex = r.senderPrefix.joinToString("") { "%02x".format(it) },
                networkName = profile.name,
                latE7 = r.latE7,
                lonE7 = r.lonE7,
                accuracyM = r.accuracyM,
                batteryPct = r.batteryPct.takeIf { it <= 100 },
                sentAtMs = r.timeS * 1000,
                receivedAtMs = System.currentTimeMillis(),
                seq = r.seq.toLong(),
                active = r.active && !r.cancelled,
                via = "sms",
            )
            if (SosAlerts.upsert(ctx, alert)) SosNotify.show(ctx, alert)
            return
        }
    }
}
