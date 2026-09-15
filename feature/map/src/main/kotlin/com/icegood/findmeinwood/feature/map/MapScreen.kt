package com.icegood.findmeinwood.feature.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.session.PeerState
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/**
 * FR-6.2/FR-6.5: shows my position (device GNSS) plus every located peer on OSM tiles.
 * Tiles come from the network by default (NFR-2 note: INTERNET is already used for
 * Google sign-in); when an MBTiles archive is dropped in files/maps/offline.mbtiles the
 * map switches to that archive and stays offline.
 */
enum class TileSource { OFFLINE, OSM, SATELLITE }

/** FR-6.4: distance (m) and bearing (deg, 0 = north) from [from] to [to]. */
internal fun distanceBearing(from: Location, toLatE7: Int, toLonE7: Int): Pair<Double, Double> {
    val results = FloatArray(2)
    Location.distanceBetween(from.latitude, from.longitude, toLatE7 / 1e7, toLonE7 / 1e7, results)
    val bearing = (results[1].toDouble() + 360) % 360
    return results[0].toDouble() to bearing
}

private fun compass(bearing: Double): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((bearing + 22.5) / 45).toInt() % 8]
}

@Composable
fun MapScreen(
    profile: StateFlow<NetworkProfile?>,
    peers: StateFlow<Map<MemberId, PeerState>>,
    modifier: Modifier = Modifier,
    tileSource: TileSource = TileSource.OFFLINE,
    onTileSourceChange: (TileSource) -> Unit = {},
) {
    val context = LocalContext.current
    val p by profile.collectAsState()
    val ps by peers.collectAsState()
    val me = rememberDeviceLocation(context)
    val mapView = remember(tileSource) { createMapView(context, tileSource) }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.overlays.filterIsInstance<MyLocationNewOverlay>().forEach { it.disableMyLocation() }
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView }) { mv ->
            renderMarkers(mv, p, ps, me)
        }
        Card(Modifier.padding(12.dp).align(Alignment.TopStart)) {
            Text(
                "${p?.name ?: "no session"} · members ${ps.size + 1} · located ${ps.values.count { it.lastPosition != null }}" +
                    " · me ${if (me != null) "fix ±${me.accuracy.toInt()} m" else "no fix"}",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Card(Modifier.padding(12.dp).align(Alignment.BottomEnd)) {
            Text(
                "© ${if (tileSource == TileSource.SATELLITE) "Esri, Maxar, Earthstar Geographics" else "OpenStreetMap contributors"}",
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Card(Modifier.padding(12.dp).align(Alignment.TopEnd)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tiles", style = MaterialTheme.typography.labelSmall)
                TileSource.entries.forEach { src ->
                    if (src == tileSource) {
                        Button(onClick = { }) { Text(src.name, style = MaterialTheme.typography.labelSmall) }
                    } else {
                        OutlinedButton(onClick = { onTileSourceChange(src) }) {
                            Text(src.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** FR-6.4: mapless alternative — distance and bearing to each peer. */
@Composable
fun PeerList(
    peers: StateFlow<Map<MemberId, PeerState>>,
    me: Location?,
    modifier: Modifier = Modifier,
) {
    val ps by peers.collectAsState()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Peers", style = MaterialTheme.typography.titleMedium)
        if (ps.isEmpty()) {
            Text("No peers seen yet.", style = MaterialTheme.typography.bodySmall)
        }
        ps.values.forEach { peer ->
            val pos = peer.lastPosition
            if (pos == null || me == null) {
                Text(
                    "${peer.nickname ?: "member"} — unlocated, last seen ${age(peer.lastSeenMs)} ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            } else {
                val (meters, bearing) = distanceBearing(me, pos.latE7, pos.lonE7)
                Text(
                    "${peer.nickname ?: "member"} — ${meters.toInt()} m, bearing ${bearing.toInt()}° ${compass(bearing)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun age(lastSeenMs: Long): String {
    val s = (System.currentTimeMillis() - lastSeenMs).coerceAtLeast(0) / 1000
    return if (s < 60) "${s}s" else "${s / 60}m"
}

private fun createMapView(context: Context, source: TileSource): MapView {
    val cfg = Configuration.getInstance()
    cfg.userAgentValue = context.packageName
    cfg.osmdroidBasePath = File(context.filesDir, "osmdroid")
    cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")

    val archive = context.getExternalFilesDir(null)?.let { File(it, "maps/offline.mbtiles") }
    val haveArchive = archive?.exists() == true

    val mv = MapView(context).apply {
        // OFFLINE means "no network": render the MBTiles archive when one is present,
        // otherwise keep a blank canvas instead of crashing on a missing database.
        val offline = haveArchive && source == TileSource.OFFLINE
        val mapnik = if (source == TileSource.SATELLITE) {
            org.osmdroid.tileprovider.tilesource.TileSourceFactory.USGS_SAT
        } else {
            TileSourceFactory.MAPNIK
        }
        if (offline) {
            val source = XYTileSource("mbtiles", 0, 19, 256, ".png", arrayOf())
            val provider = MapTileFileArchiveProvider(
                SimpleRegisterReceiver(context), source,
                arrayOf(MBTilesFileArchive.getDatabaseFileArchive(archive!!)),
            )
            tileProvider = MapTileProviderArray(source, SimpleRegisterReceiver(context), arrayOf(provider))
            setUseDataConnection(false)
        } else {
            tileProvider = MapTileProviderBasic(context, mapnik)
            setUseDataConnection(true)
        }
        setMultiTouchControls(true)
        setBuiltInZoomControls(false)
        controller.setZoom(15.0)
    }

    return mv
}

/** FR-4.1/FR-6.2: my own position from GNSS + network fixes, last known first. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberDeviceLocation(context: Context): Location? {
    var location by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { location = loc }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        try {
            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )) {
                if (!lm.isProviderEnabled(provider)) continue
                val last = lm.getLastKnownLocation(provider)
                if (last != null && (location == null || last.time > location!!.time)) location = last
                lm.requestLocationUpdates(provider, 2_000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            // no location permission: map stays without "me" (UI gates session start)
        } catch (_: IllegalArgumentException) {
            // provider unavailable
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return location
}

private fun renderMarkers(
    mv: MapView,
    profile: NetworkProfile?,
    peers: Map<MemberId, PeerState>,
    me: Location?,
) {
    mv.overlays.removeAll { it is Marker }
    if (me != null) {
        mv.overlays.add(
            Marker(mv).apply {
                position = GeoPoint(me.latitude, me.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "You"
                snippet = "±${me.accuracy.toInt()} m"
            },
        )
        if (mv.zoomLevelDouble < 10.0 || !hasPeerPosition(peers)) {
            mv.controller.animateTo(GeoPoint(me.latitude, me.longitude))
        }
    }
    peers.values.forEach { peer ->
        val pos = peer.lastPosition ?: return@forEach
        mv.overlays.add(
            Marker(mv).apply {
                position = GeoPoint(pos.latE7 / 1e7, pos.lonE7 / 1e7)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = peer.nickname ?: "member"
                snippet = if (peer.viaMemberId != null) "relayed" else "direct"
            },
        )
    }
    if (me == null) {
        peers.values.firstNotNullOfOrNull { it.lastPosition }?.let { first ->
            if (mv.zoomLevelDouble < 10.0) {
                mv.controller.animateTo(GeoPoint(first.latE7 / 1e7, first.lonE7 / 1e7))
            }
        }
    }
    mv.invalidate()
}

private fun hasPeerPosition(peers: Map<MemberId, PeerState>): Boolean =
    peers.values.any { it.lastPosition != null }
