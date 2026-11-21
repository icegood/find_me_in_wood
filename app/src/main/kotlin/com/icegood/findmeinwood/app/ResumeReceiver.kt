package com.icegood.findmeinwood.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.icegood.findmeinwood.transport.api.DebugLog

/**
 * The network the user left active comes back by itself after an app update and after a
 * reboot (FR-2.x): an update force-stops the app and START_STICKY does not bring a stopped
 * app back, so without this a phone stayed out of reach until someone opened the app (seen
 * on the VOG-L29 after `adb install -r`: the host's group stayed up, nobody answered in it).
 * Both broadcasts are exempt from the limits on starting a foreground service from the
 * background.
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val started = runCatching { SessionService.resume(context) }.getOrElse { e ->
            DebugLog.d { "resume after ${intent.action} failed: $e" }
            false
        }
        DebugLog.d { "resume after ${intent.action}: ${if (started) "network started" else "nothing to start"}" }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
        )
    }
}
