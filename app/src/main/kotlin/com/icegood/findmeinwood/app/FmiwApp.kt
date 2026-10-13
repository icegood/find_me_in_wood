package com.icegood.findmeinwood.app

import android.app.Application
import com.icegood.findmeinwood.transport.api.DebugLog

/** Process entry: debug builds route radio/discovery diagnostics to logcat (debug level). */
class FmiwApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) DebugLog.sink = { message -> android.util.Log.d(DebugLog.TAG, message) }
    }
}
