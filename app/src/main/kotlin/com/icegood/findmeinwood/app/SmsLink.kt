package com.icegood.findmeinwood.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.icegood.findmeinwood.core.crypto.SmsCodec
import com.icegood.findmeinwood.core.model.BeaconPosition

/** Pure helpers, unit-tested on the JVM. */
internal object SmsLogic {
    /**
     * Phone numbers from free text, separated by , ; or newlines. Spaces, dashes and
     * brackets inside a number ("+380 67 123-45-67") are formatting and are dropped.
     */
    fun parseNumbers(text: String): List<String> =
        text.split(',', ';', '\n')
            .map { raw -> raw.filter { it.isDigit() || it == '+' } }
            .filter { it.count(Char::isDigit) >= 5 }
            .distinct()
}

/** Caps how many SMS go out per hour: satellite SMS is slow, limited and often paid. */
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

/** Sends LNP/2 SMS reports with the platform SMS stack (needs SEND_SMS). */
internal object SmsSender {
    private const val TAG = "FMIW-SMS"

    /** Number of recipients the message was handed to the modem for. */
    fun send(ctx: Context, numbers: List<String>, body: String): Int {
        val sms = runCatching { ctx.getSystemService(SmsManager::class.java) }.getOrNull() ?: return 0
        var ok = 0
        for (n in numbers) {
            runCatching {
                sms.sendTextMessage(n, null, body, null, null)
                ok++
            }.onFailure { Log.w(TAG, "SMS to ${n.takeLast(3)} failed", it) }
        }
        return ok
    }

    /**
     * True when the phone is currently on a non-terrestrial (satellite) network — Android 15+
     * and READ_PHONE_STATE only; null when unknown. For the link log.
     */
    fun onSatellite(ctx: Context): Boolean? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        ctx.getSystemService(TelephonyManager::class.java)?.serviceState?.isUsingNonTerrestrialNetwork
    }.getOrNull()
}

/**
 * Incoming SMS on the LNP/2 SMS link: position reports from members, decrypted with the
 * running network's key and fed to the session like beacons. Anything else is left alone.
 */
class SmsLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        messages.groupBy { it.originatingAddress }.values.forEach { parts ->
            val body = parts.joinToString("") { it.messageBody.orEmpty() }
            if (SmsCodec.looksLikeReport(body)) handle(context, body)
        }
    }

    private fun handle(ctx: Context, body: String) {
        val frame = SmsCodec.frameOf(body) ?: return
        val network = SessionService.activeNetworkId ?: return
        val manager = SessionService.activeManager ?: return
        if (!SmsCodec.networkTag(network).contentEquals(SmsCodec.networkTagOf(frame))) return
        val profile = ProfileStore.load(ctx).map(ProfileStore::toProfile).firstOrNull { it.networkId == network } ?: return
        val r = SmsCodec.decode(frame, profile.trafficKey) ?: return
        if (r.isFrom(profile.myMemberId)) return
        val known = r.latE7 != 0 || r.lonE7 != 0
        manager.acceptSmsReport(
            r.senderPrefix, r.seq, r.timeS * 1000,
            if (known) BeaconPosition(r.latE7, r.lonE7, r.accuracyM * 100, 0) else null,
            r.batteryPct.takeIf { it <= 100 },
        )
    }
}
