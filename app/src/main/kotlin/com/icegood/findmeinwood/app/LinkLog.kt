package com.icegood.findmeinwood.app

import android.content.ContentValues
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.icegood.findmeinwood.core.model.GnssFix
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.session.SessionManager
import com.icegood.findmeinwood.transport.bluetooth.BluetoothTransport
import com.icegood.findmeinwood.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: a persistent per-session link log for field tests, to see which
 * metric (distance, RSSI, radio state, battery, GPS accuracy...) went bad when a peer's
 * signal was lost.
 *
 * Written as JSON Lines to the public Download/FindMeInWood/ folder through MediaStore:
 * no permission is needed, the files survive app updates *and* uninstall/reinstall, and
 * the app never deletes them. One file per session start; every line is flushed, so a
 * crash or a killed process loses nothing. Release builds never create a file
 * ([BuildConfig.DEBUG] is a compile-time constant, so R8 strips the logging).
 *
 * Events: session_start/stop, snapshot (every [SAMPLE_MS]), radio (state changes),
 * peer_lost / peer_back (from [LinkWatch]), link_switch (LNP/3), sms_report.
 */
internal class LinkLog private constructor(private val out: OutputStream, val fileName: String) {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    @Synchronized
    fun write(event: String, fields: JsonObjectBuilder.() -> Unit = {}) {
        val now = System.currentTimeMillis()
        val line = buildJsonObject {
            put("t", iso.format(Date(now)))
            put("ms", now)
            put("ev", event)
            fields()
        }.toString()
        runCatching {
            out.write((line + "\n").toByteArray())
            out.flush()
        }
        Log.d(TAG, line)
    }

    @Synchronized
    fun close() {
        runCatching { out.close() }
    }

    companion object {
        private const val TAG = "FMIW-LINK"
        const val SAMPLE_MS = 10_000L
        const val FOLDER = "FindMeInWood"

        /** Opens a new log file for this session, or null in release builds / on failure. */
        fun open(ctx: Context): LinkLog? {
            if (!BuildConfig.DEBUG) return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val device = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "_")
            val name = "fmiw-link-$device-$stamp.txt"
            return runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
                val stream = ctx.contentResolver.openOutputStream(uri, "wa") ?: error("cannot open $uri")
                LinkLog(stream, name)
            }.onFailure { Log.w(TAG, "link log unavailable", it) }.getOrNull()
        }
    }
}

/**
 * Samples the running session every [LinkLog.SAMPLE_MS] into [log] and turns the samples
 * into peer_lost / peer_back events. Lives in the session scope, so it stops with it.
 */
internal class LinkLogSampler(
    private val ctx: Context,
    private val log: LinkLog,
    private val manager: SessionManager,
    private val bluetooth: BluetoothTransport,
    private val wifiDirect: WifiDirectTransport,
    private val lastFix: () -> GnssFix?,
    beaconIntervalMs: Long,
) {
    // Lost = more than ~2.5 beacon intervals without a frame (one missed beacon is normal).
    private val lostAfterMs = (beaconIntervalMs * 5 / 2).coerceAtLeast(30_000L)
    private val watch = LinkWatch(lostAfterMs)

    fun start(scope: CoroutineScope, beaconIntervalMs: Long) {
        log.write("session_start") {
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("beaconIntervalMs", beaconIntervalMs)
            put("lostAfterMs", lostAfterMs)
            put("transports", manager.transportIds().joinToString())
        }
        scope.launch {
            var previous = emptyMap<String, String>()
            manager.radioStates.collect { states ->
                val now = states.map { (t, s) -> t.name to s.name }.toMap()
                now.forEach { (t, s) ->
                    if (previous[t] != s) {
                        log.write("radio") {
                            put("transport", t)
                            put("from", previous[t])
                            put("to", s)
                        }
                    }
                }
                previous = now
            }
        }
        scope.launch {
            while (true) {
                runCatching { sample() }.onFailure { e -> log.write("sampler_error") { put("error", e.toString()) } }
                delay(LinkLog.SAMPLE_MS)
            }
        }
    }

    fun onLinkSwitch(sw: com.icegood.findmeinwood.core.session.LinkNegotiator.Switch) {
        log.write("link_switch") {
            put("peer", sw.peer.short())
            put("from", sw.from?.name)
            put("to", sw.to?.name ?: "none")
            put("common", manager.links?.common(sw.peer)?.joinToString())
            put("agreed", manager.links?.agreed(sw.peer))
        }
    }

    private fun sample() {
        val nowMs = System.currentTimeMillis()
        val fix = lastFix()
        val edges = manager.edges().associateBy { it.peer }
        val peers = manager.peerFlow.value
        val rssi = bluetooth.rssiByLink()
        val samples = peers.values.map { p ->
            val pos = p.lastPosition
            val distance = if (fix != null && pos != null) {
                LinkWatch.distanceM(fix.lat, fix.lon, pos.latE7 / 1e7, pos.lonE7 / 1e7)
            } else {
                null
            }
            LinkWatch.PeerSample(
                id = p.memberId.short(),
                silentMs = nowMs - p.lastSeenMs,
                distanceM = distance,
                transport = (edges[p.memberId]?.link ?: p.transport)?.name,
            )
        }
        val d = manager.diagnostics.value
        log.write("snapshot") {
            putJsonObject("me") {
                put("lat", fix?.lat)
                put("lon", fix?.lon)
                put("accM", fix?.accuracyM)
                put("fixAgeS", fix?.let { (nowMs - it.epochMs) / 1000 })
                put("batteryPct", batteryPct())
            }
            putJsonObject("radios") {
                manager.radioStates.value.forEach { (t, s) -> put(t.name, s.name) }
            }
            // Agreed Wi-Fi Direct role: HOST, JOIN, or null (agreed with nobody).
            put("wifiDirectRole", manager.links?.wifiDirectRole()?.name)
            putJsonObject("ble") {
                put("central", bluetooth.centralLinkCount())
                put("peripheral", bluetooth.peripheralLinkCount())
                putJsonArray("rssi") {
                    rssi.forEach { (addr, r) ->
                        add(
                            buildJsonObject {
                                put("link", addr.takeLast(5))
                                put("dbm", r.dbm)
                                put("ageS", (nowMs - r.atMs) / 1000)
                            },
                        )
                    }
                }
            }
            put("wifiDirectSockets", wifiDirect.peerLinkCount())
            putJsonObject("counters") {
                put("sent", d.sent)
                put("rx", d.received)
                put("relayed", d.relayed)
                put("authFailed", d.authFailed)
                put("dropped", d.dropped)
            }
            putJsonArray("peers") {
                samples.forEach { s ->
                    add(
                        buildJsonObject {
                            put("id", s.id)
                            put("silentS", s.silentMs / 1000)
                            put("distM", s.distanceM?.let { Math.round(it) })
                            put("via", s.transport)
                            val link = edges.values.firstOrNull { it.peer.short() == s.id }
                            put("link", link?.link?.name)
                            put("agreed", link?.agreed?.joinToString())
                            put("allowedByMe", link?.allowedByMe?.joinToString())
                            put("allowedByPeer", link?.allowedByPeer?.joinToString())
                            put("suggestion", link?.suggestion?.name)
                            put("common", link?.common?.joinToString())
                        },
                    )
                }
            }
        }
        watch.update(nowMs, samples).forEach { e ->
            when (e) {
                is LinkWatch.Event.Lost -> log.write("peer_lost") {
                    put("peer", e.id)
                    put("silentS", e.silentMs / 1000)
                    put("lastDistM", e.lastDistanceM?.let { Math.round(it) })
                    put("lastVia", e.lastTransport)
                    put("distTrendM", buildJsonArray { e.distanceTrend.forEach { add(kotlinx.serialization.json.JsonPrimitive(Math.round(it))) } })
                    put("myAccM", fix?.accuracyM)
                    put("batteryPct", batteryPct())
                    putJsonObject("radios") { manager.radioStates.value.forEach { (t, s) -> put(t.name, s.name) } }
                    putJsonArray("bleRssi") {
                        rssi.forEach { (addr, r) ->
                            add(buildJsonObject { put("link", addr.takeLast(5)); put("dbm", r.dbm); put("ageS", (nowMs - r.atMs) / 1000) })
                        }
                    }
                }
                is LinkWatch.Event.Back -> log.write("peer_back") {
                    put("peer", e.id)
                    put("gapS", e.gapMs / 1000)
                    put("distM", e.distanceM?.let { Math.round(it) })
                    put("via", e.transport)
                }
            }
        }
    }

    private fun batteryPct(): Int? = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()

    private fun MemberId.short() = bytes.take(4).joinToString("") { "%02x".format(it) }
}
