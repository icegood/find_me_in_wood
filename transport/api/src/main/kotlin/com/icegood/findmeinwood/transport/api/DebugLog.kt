package com.icegood.findmeinwood.transport.api

/**
 * Debug-level diagnostics, all under one logcat tag ([TAG]). Silent until the app plugs in
 * a [sink], which it does in debug builds only: release builds never build the messages.
 */
object DebugLog {
    const val TAG = "icegood.fmiw"

    @Volatile var sink: ((message: String) -> Unit)? = null

    fun d(message: () -> String) {
        sink?.invoke(message())
    }
}
