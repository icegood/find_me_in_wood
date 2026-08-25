package app.findmeinwood.feature.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.session.PeerState
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * Offline-first map (T3.3): MBTiles archive from app files dir if present,
 * blank canvas otherwise; peer markers from the session (FR-6.2). No network tiles.
 */
@Composable
fun MapScreen(
    profile: StateFlow<NetworkProfile?>,
    peers: StateFlow<Map<MemberId, PeerState>>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val p by profile.collectAsState()
    val ps by peers.collectAsState()
    val mapView = remember { createMapView(context) }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView }) { mv ->
            renderMarkers(mv, p, ps)
        }
        Card(Modifier.padding(12.dp)) {
            Text(
                "${p?.name ?: "no session"} · members ${ps.size + 1} · located ${ps.values.count { it.lastPosition != null }}",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun createMapView(context: android.content.Context): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    val mv = MapView(context).apply {
        setTileSource(XYTileSource("offline", 0, 19, 256, ".png", arrayOf("offline")))
        setUseDataConnection(false) // NFR-2: offline only
        setMultiTouchControls(true)
        controller.setZoom(14.0)
    }
    val archive = File(context.getExternalFilesDir(null), "maps/offline.mbtiles")
    if (archive.exists()) {
        val tileSource = XYTileSource("offline", 0, 19, 256, ".png", arrayOf("offline"))
        val provider = MapTileFileArchiveProvider(
            SimpleRegisterReceiver(context), tileSource,
            arrayOf(org.osmdroid.tileprovider.modules.MBTilesFileArchive.getDatabaseFileArchive(archive)),
        )
        mv.tileProvider.tileRequestCompleteHandlers // keep default chain; add archive first
        mv.tileProvider = org.osmdroid.tileprovider.MapTileProviderArray(
            tileSource,
            SimpleRegisterReceiver(context),
            arrayOf(provider),
        )
    }
    return mv
}

private fun renderMarkers(
    mv: MapView,
    profile: NetworkProfile?,
    peers: Map<MemberId, PeerState>,
) {
    mv.overlays.removeAll { it is Marker }
    peers.values.forEach { peer ->
        val pos = peer.lastPosition ?: return@forEach
        val marker = Marker(mv).apply {
            position = GeoPoint(pos.latE7 / 1e7, pos.lonE7 / 1e7)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "${peer.nickname ?: "member"}${if (peer.lastPosition != null) "" else " (no fix)"}"
        }
        mv.overlays.add(marker)
    }
    if (peers.isNotEmpty()) mv.controller.animateTo(
        GeoPoint(
            peers.values.firstNotNullOf { it.lastPosition!! }.latE7 / 1e7,
            peers.values.firstNotNullOf { it.lastPosition!! }.lonE7 / 1e7,
        ),
    )
    mv.invalidate()
}
