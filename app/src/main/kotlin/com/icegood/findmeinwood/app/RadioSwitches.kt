package com.icegood.findmeinwood.app

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.icegood.findmeinwood.transport.api.DebugLog

/** The polled system state of [radio]: true when on, and when it can't be read. */
internal fun radioOn(ctx: Context, radio: Radio): Boolean = runCatching {
    when (radio) {
        Radio.WIFI -> ctx.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled != false
        Radio.BLUETOOTH ->
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled != false
        Radio.LOCATION -> ctx.getSystemService(android.location.LocationManager::class.java)
            ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } ?: true
    }
}.getOrDefault(true)

/**
 * Opens the system control for [radio] (prompt, panel or settings, from [RadioToggles.actions]):
 * the first action that starts wins, the next is the fallback.
 */
internal fun openRadioControl(ctx: Context, radio: Radio, on: Boolean) {
    val opened = RadioToggles.open(RadioToggles.actions(radio, Build.VERSION.SDK_INT, on)) { action ->
        val intent = Intent(action).apply {
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }.isSuccess
    }
    if (!opened) DebugLog.d { "ui: radio ${radio.name.lowercase()}: no system control could be opened" }
}

/**
 * Radio switch rows. [rows] carry the system state polled by the caller every few seconds:
 * the caller builds them in its own polled scope and passes them in, so a new poll is a new
 * argument and these rows recompose (state read in here would be skipped). A tap only opens
 * the system control, so the row never flips by itself and follows the system once the user
 * has turned the radio on or off there. [withHints] shows the Wi-Fi / Location explanation,
 * in error colour, while that radio is off (Bluetooth has none).
 */
@Composable
internal fun RadioSwitchRows(ctx: Context, rows: List<RadioRow>, withHints: Boolean) {
    rows.forEach { row ->
        SwitchRow(
            title = stringResource(
                when (row.radio) {
                    Radio.WIFI -> R.string.radio_wifi
                    Radio.BLUETOOTH -> R.string.transport_bluetooth
                    Radio.LOCATION -> R.string.radio_location
                },
            ),
            checked = row.on,
            onCheckedChange = {
                DebugLog.d { RadioToggles.tapLog(row.radio, row.on) }
                openRadioControl(ctx, row.radio, row.on)
            },
            hint = if (!withHints || !row.blocker) null else when (row.radio) {
                Radio.WIFI -> stringResource(R.string.wifi_off_explain)
                Radio.LOCATION -> stringResource(R.string.location_off_explain)
                Radio.BLUETOOTH -> null
            },
            hintColor = if (row.blocker) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}
