package com.icegood.findmeinwood.feature.map

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.session.PeerState
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.api.IGeoPoint
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * FR-6.2/FR-6.5: shows my position (device GNSS) plus every located peer on OSM tiles.
 * Tiles come from the network by default (NFR-2 note: INTERNET is already used for
 * Google sign-in); when an MBTiles archive is dropped in files/maps/offline.mbtiles the
 * map switches to that archive and stays offline.
 *
 * Extra navigation aids: pick a member to find (tap their marker, or "Find" in the peer
 * list) — others turn grey, an arrow points at them with the distance at its tip, and a
 * card shows distance + bearing until cleared with ✕; a scale bar (bottom-left), a compass, and an arrow for my own movement
 * derived from consecutive GPS fixes.
 */
/** My GNSS fix plus the movement heading derived from consecutive fixes. */
data class DeviceFix(val location: Location, val headingDeg: Float?)

/** FR-6.4: distance (m) and bearing (deg, 0 = north) from [from] to [to]. */
internal fun distanceBearing(from: Location, toLatE7: Int, toLonE7: Int): Pair<Double, Double> {
    val results = FloatArray(2)
    Location.distanceBetween(from.latitude, from.longitude, toLatE7 / 1e7, toLonE7 / 1e7, results)
    val bearing = (results[1].toDouble() + 360) % 360
    return results[0].toDouble() to bearing
}

/**
 * Exponential smoothing of a compass heading along the shortest arc, so 350° and 10°
 * blend to ~0° rather than ~180°.
 */
internal fun blendHeading(prev: Float?, bearing: Float, weight: Float = 0.4f): Float {
    if (prev == null) return (bearing + 360f) % 360f
    val diff = ((bearing - prev + 540f) % 360f) - 180f
    return (prev + weight * diff + 360f) % 360f
}

/** Where the arrow to the target ends, and whether the target itself is off-screen. */
internal data class ArrowEnd(val x: Float, val y: Float, val offScreen: Boolean)

/**
 * End point of the arrow from ([fromX],[fromY]) towards ([toX],[toY]) in a [width]x[height]
 * view: the target itself when it is visible (inset by [margin]), otherwise the point where
 * the ray leaves the view, so the arrow and its distance label sit right on the edge.
 * Null when the two points coincide.
 */
internal fun arrowEnd(
    fromX: Float, fromY: Float, toX: Float, toY: Float,
    width: Float, height: Float, margin: Float,
): ArrowEnd? {
    val dx = toX - fromX
    val dy = toY - fromY
    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
    if (dist < 1f) return null
    val inside = toX in margin..(width - margin) && toY in margin..(height - margin)
    if (inside) return ArrowEnd(toX, toY, offScreen = false)
    val ux = dx / dist
    val uy = dy / dist
    val ts = buildList {
        if (ux > 0f) add((width - margin - fromX) / ux)
        if (ux < 0f) add((margin - fromX) / ux)
        if (uy > 0f) add((height - margin - fromY) / uy)
        if (uy < 0f) add((margin - fromY) / uy)
    }
    // Ray parameter where it first leaves the inset view; if we start outside it, clamp.
    val t = (ts.filter { it > 0f }.minOrNull() ?: 0f).coerceAtMost(dist)
    return ArrowEnd(
        (fromX + ux * t).coerceIn(margin, width - margin),
        (fromY + uy * t).coerceIn(margin, height - margin),
        offScreen = true,
    )
}

internal fun formatDistance(meters: Double): String =
    if (meters >= 1000) "%.1f km".format(meters / 1000) else "${meters.toInt()} m"

private fun sourceLabel(s: TileSource) = when (s) {
    TileSource.OFFLINE -> "Offline"
    TileSource.OSM -> "Streets"
    TileSource.SATELLITE -> "Satellite"
}

private fun sourceHint(s: TileSource) = when (s) {
    TileSource.OFFLINE -> "areas saved with ↓ and places already viewed — no internet needed"
    TileSource.OSM -> "OpenStreetMap, needs internet"
    TileSource.SATELLITE -> "imagery, needs internet (USA only for now)"
}

private fun compass(bearing: Double): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((bearing + 22.5) / 45).toInt() % 8]
}

/**
 * Confirmation sheet for an offline area: name it, pick the zoom window, see the tile/size
 * estimate before anything is downloaded. The zoom range is clamped so the request always
 * fits the provider and a sane tile budget.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OfflineAreaDialog(
    bbox: BoundingBox,
    providerLabel: String,
    currentZoom: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, zoomMin: Int, zoomMax: Int) -> Unit,
) {
    val initialName = remember(bbox) { "Area %.3f°, %.3f°".format(bbox.centerLatitude, bbox.centerLongitude) }
    var name by remember(bbox) { mutableStateOf(initialName) }
    // Default to the level the user is actually looking at: deeper levels are invisible and
    // would dominate the tile count, so the slider starts there rather than at the max.
    // Default to walking detail even when the area was framed zoomed out; the tile budget
    // trims it back if the area is too big for that.
    var zoomMax by remember(bbox, currentZoom) {
        mutableIntStateOf(currentZoom.coerceAtLeast(DETAIL_ZOOM).coerceIn(MIN_ZOOM, PROVIDER_MAX_ZOOM))
    }
    val range = remember(bbox, zoomMax) { clampZoomRange(PROVIDER_MAX_ZOOM, MIN_ZOOM, zoomMax, bbox) }
    val tiles = remember(bbox, range) { estimateTiles(bbox, range.first, range.last) }
    val zoomCapped = zoomMax > range.last

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save offline area") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "provider: $providerLabel\n" +
                        "bounds: %.4f, %.4f → %.4f, %.4f".format(
                            bbox.latSouth, bbox.lonWest, bbox.latNorth, bbox.lonEast,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Area name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text("Maximum zoom: $zoomMax", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = zoomMax.toFloat(),
                        onValueChange = { zoomMax = it.toInt() },
                        valueRange = MIN_ZOOM.toFloat()..PROVIDER_MAX_ZOOM.toFloat(),
                        steps = PROVIDER_MAX_ZOOM - MIN_ZOOM - 1,
                    )
                }
                Text(
                    "zoom ${range.first}–${range.last} · ${estimateLabel(tiles)}" +
                        if (zoomCapped) "\nlimited to zoom ${range.last}: deeper levels exceed $MAX_TILES_PER_AREA tiles" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim().ifBlank { initialName }, range.first, range.last) },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_ZOOM = 8
/** Street/trail detail needed on foot; the default deepest level of an offline area. */
private const val DETAIL_ZOOM = 17
private const val INITIAL_ZOOM = 15.0
private const val PROVIDER_MAX_ZOOM = 19

@Composable
fun MapScreen(
    profile: StateFlow<NetworkProfile?>,
    peers: StateFlow<Map<MemberId, PeerState>>,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
    tileSource: TileSource = TileSource.OFFLINE,
    tileApiKeys: Map<TileProvider, String> = emptyMap(),
    onTileSourceChange: (TileSource) -> Unit = {},
    /**
     * The member being searched for. Hoisted so the choice survives tab switches and can
     * be made from the peer list; only the ✕ in the target card clears it.
     */
    findTarget: MemberId? = null,
    onFindTargetChange: (MemberId?) -> Unit = {},
) {
    val context = LocalContext.current
    val p by profile.collectAsState()
    val ps by peers.collectAsState()
    val fix = rememberDeviceFix(context)
    val me = fix?.location
    val follow = findTarget
    var mapVersion by remember { mutableStateOf(0) }
    val azimuth = rememberCompassAzimuth(context)
    // The source OFFLINE reads: the last source used while online, so saved offline
    // areas (and anything previously viewed) appear without a connection.
    var lastOnlineSource by remember { mutableStateOf(TileSource.OSM) }
    LaunchedEffect(tileSource) {
        if (tileSource != TileSource.OFFLINE) lastOnlineSource = tileSource
    }
    // The concrete provider actually being requested, and the api key that belongs to
    // *that* provider (OSM resolves to Mapnik or CARTO dark depending on the theme).
    val effectiveSource = if (tileSource == TileSource.OFFLINE) lastOnlineSource else tileSource
    val provider = TileProvider.forSource(effectiveSource, isDarkTheme)
    val providerLabel = provider.label
    val tileApiKey = tileApiKeys[provider].orEmpty()

    // Offline-area picking: drag a rectangle on the map, then name it and confirm.
    var selecting by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<IGeoPoint?>(null) }
    var dragEnd by remember { mutableStateOf<IGeoPoint?>(null) }
    var pendingArea by remember { mutableStateOf<BoundingBox?>(null) }
    var downloadUi by remember { mutableStateOf<DownloadUi?>(null) }
    val activeCacheManager = remember { mutableStateOf<CacheManager?>(null) }
    // Current map zoom, so the offline-area dialog can default to the level actually in view.
    var mapZoom by remember { mutableIntStateOf(INITIAL_ZOOM.toInt()) }

    fun exitSelection() {
        selecting = false
        dragStart = null
        dragEnd = null
        pendingArea = null
    }

    // Recreate the mapView whenever the tile source, theme or API key changes:
    // AndroidView's factory lambda only runs once per node, so the whole subtree
    // (view creation + effects + HUD overlay) is scoped under key(...).
    val accent = MaterialTheme.colorScheme.primary
    val myDirColor = MaterialTheme.colorScheme.secondary
    val labelBg = MaterialTheme.colorScheme.primary
    val labelFg = MaterialTheme.colorScheme.onPrimary
    val textMeasurer = rememberTextMeasurer()
    val peer = follow?.let { ps[it] }

    Box(modifier.fillMaxSize()) {
        // tileSource is part of the key: OSM -> OFFLINE keeps the same provider, and without
        // it the online view (with data connection) was reused in OFFLINE mode.
        key(tileSource, provider, tileApiKey) {
            val mapView = remember { createMapView(context, tileSource, isDarkTheme, provider, tileApiKey) }

            DisposableEffect(mapView) {
                mapView.onResume()
                onDispose {
                    saveLastView(context, mapView)
                    mapView.onPause()
                    mapView.onDetach()
                }
            }

            DisposableEffect(mapView) {
                // No tap-to-clear: a stray tap must never drop the person being searched for.
                val listener = object : MapListener {
                    override fun onScroll(event: ScrollEvent): Boolean { mapVersion++; return true }
                    override fun onZoom(event: ZoomEvent): Boolean {
                        mapVersion++
                        mapZoom = event.zoomLevel.toInt()
                        return true
                    }
                }
                mapView.addMapListener(listener)
                onDispose { mapView.removeMapListener(listener) }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
            ) { mv -> renderMarkers(mv, p, ps, me, follow, onFindTargetChange) }

            // HUD, two arrows: my own direction (movement heading or compass) at my position,
            // and the direction to the member being searched for, ending at that member when
            // visible or on the screen edge with the distance written there.
            Canvas(Modifier.fillMaxSize()) {
                val tick = mapVersion
                if (!(mapView.width > 0 && mapView.height > 0)) return@Canvas
                val meNow = me
                if (meNow != null) {
                    val myPx = mapView.projection.toPixels(GeoPoint(meNow.latitude, meNow.longitude), null)
                    val my = Offset(myPx.x.toFloat(), myPx.y.toFloat())
                    // My direction: movement heading from consecutive fixes, falling back
                    // to the compass azimuth so it always shows (not only while walking).
                    val myDirection = fix?.headingDeg ?: azimuth.value
                    if (myDirection != null) {
                        rotate(myDirection, pivot = my) {
                            drawPath(
                                Path().apply {
                                    moveTo(my.x, my.y - 16.dp.toPx())
                                    lineTo(my.x - 7.dp.toPx(), my.y + 9.dp.toPx())
                                    lineTo(my.x, my.y + 3.dp.toPx())
                                    lineTo(my.x + 7.dp.toPx(), my.y + 9.dp.toPx())
                                    close()
                                },
                                color = myDirColor,
                            )
                        }
                    }
                    val target = follow?.let { ps[it] }?.lastPosition
                    if (target != null) {
                        val tp = mapView.projection.toPixels(
                            GeoPoint(target.latE7 / 1e7, target.lonE7 / 1e7), null,
                        )
                        val t = Offset(tp.x.toFloat(), tp.y.toFloat())
                        val ring = 18.dp.toPx()
                        val end = arrowEnd(my.x, my.y, t.x, t.y, size.width, size.height, 12.dp.toPx())
                        if (end != null && !end.offScreen) {
                            drawCircle(color = accent.copy(alpha = 0.30f), radius = ring, center = t)
                            drawCircle(color = accent, radius = ring, center = t, style = Stroke(2.dp.toPx()))
                        }
                        if (end != null) {
                            // Stop at the ring of a visible target instead of covering it.
                            val tip = if (end.offScreen) {
                                Offset(end.x, end.y)
                            } else {
                                val dx = t.x - my.x
                                val dy = t.y - my.y
                                val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                                Offset(t.x - dx / d * ring, t.y - dy / d * ring)
                            }
                            drawDirectionArrow(my, tip, accent)
                            val (meters, _) = distanceBearing(meNow, target.latE7, target.lonE7)
                            val layout = textMeasurer.measure(
                                formatDistance(meters),
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = labelFg),
                            )
                            val pad = 6.dp.toPx()
                            val w = layout.size.width + 2 * pad
                            val h = layout.size.height + 2 * pad
                            // Beside the arrow tip, pushed inward so it stays fully on screen.
                            val gap = 22.dp.toPx()
                            val ux = (tip.x - my.x)
                            val uy = (tip.y - my.y)
                            val ul = kotlin.math.sqrt(ux * ux + uy * uy).coerceAtLeast(1f)
                            val cx = (tip.x - ux / ul * gap).coerceIn(w / 2, size.width - w / 2)
                            val cy = (tip.y - uy / ul * gap).coerceIn(h / 2, size.height - h / 2)
                            drawRoundRect(
                                color = labelBg,
                                topLeft = Offset(cx - w / 2, cy - h / 2),
                                size = Size(w, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2),
                            )
                            drawText(layout, topLeft = Offset(cx - w / 2 + pad, cy - h / 2 + pad))
                        }
                    }
                }
                tick.hashCode()
            }

            // Rectangle picker: a transparent layer over the map that captures the drag so
            // the map itself does not pan, exactly like picking an area in Google Maps.
            if (selecting) {
                val boxColor = MaterialTheme.colorScheme.tertiary
                val a = dragStart
                val b = dragEnd
                Canvas(Modifier.fillMaxSize()) {
                    if (a == null || b == null) return@Canvas
                    val pa = mapView.projection.toPixels(a.toGeoPoint(), null)
                    val pb = mapView.projection.toPixels(b.toGeoPoint(), null)
                    val tl = Offset(minOf(pa.x, pb.x).toFloat(), minOf(pa.y, pb.y).toFloat())
                    val br = Offset(maxOf(pa.x, pb.x).toFloat(), maxOf(pa.y, pb.y).toFloat())
                    val r = kotlin.math.abs(br.x - tl.x)
                    val rect = androidx.compose.ui.geometry.Rect(tl, br)
                    drawRect(
                        color = boxColor.copy(alpha = 0.18f),
                        topLeft = tl,
                        size = androidx.compose.ui.geometry.Size(r, kotlin.math.abs(br.y - tl.y)),
                    )
                    drawRect(color = boxColor, topLeft = tl, size = rect.size, style = Stroke(2.dp.toPx()))
                    // Corner handles make it obvious a rectangle (not a path) is being picked.
                    listOf(tl, Offset(br.x, tl.y), br, Offset(tl.x, br.y)).forEach { c ->
                        drawCircle(color = boxColor, radius = 5.dp.toPx(), center = c)
                    }
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { off ->
                                val p = mapView.projection.fromPixels(off.x.toInt(), off.y.toInt())
                                dragStart = p
                                dragEnd = p
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragEnd = mapView.projection.fromPixels(
                                    change.position.x.toInt(),
                                    change.position.y.toInt(),
                                )
                            },
                            onDragEnd = { dragStart?.let { dragEnd?.let { end -> pendingArea = dragToBoundingBox(it, end) } } },
                            onDragCancel = { dragStart = null; dragEnd = null },
                        )
                    },
                )
            }

            // Recenter the map on my current location.
            if (me != null) {
                SmallFloatingActionButton(
                    onClick = { mapView.controller.animateTo(GeoPoint(me.latitude, me.longitude)) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Text("⌖", style = MaterialTheme.typography.titleMedium) }
            }

            // Pick a rectangle to save for offline use, Google-Maps style.
            if (tileSource != TileSource.OFFLINE && !selecting && downloadUi == null) {
                SmallFloatingActionButton(
                    onClick = {
                        selecting = true
                        dragStart = null
                        dragEnd = null
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 88.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) { Text("↓", style = MaterialTheme.typography.titleMedium) }
            }
        }

        Card(Modifier.padding(12.dp).align(Alignment.TopStart).fillMaxWidth()) {
            Text(
                "${p?.name ?: "No network running"} · ${ps.size} other${if (ps.size == 1) "" else "s"} heard" +
                    " · ${if (me != null) "GPS ±${me.accuracy.toInt()} m" else "waiting for GPS"}",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        CompassHud(azimuth = azimuth.value, isDarkTheme = isDarkTheme, Modifier.padding(start = 12.dp, top = 84.dp).align(Alignment.TopStart))


        Card(Modifier.padding(12.dp).align(Alignment.BottomEnd)) {
            Text(
                provider.attribution,
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // One small chip instead of a panel of buttons that covered the status line and compass.
        Box(Modifier.padding(top = 80.dp, end = 12.dp).align(Alignment.TopEnd)) {
            var open by remember { mutableStateOf(false) }
            androidx.compose.material3.FilledTonalButton(onClick = { open = true }) {
                Text("Map: ${sourceLabel(tileSource)} ▾", style = MaterialTheme.typography.labelMedium)
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                TileSource.entries.forEach { src ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text((if (src == tileSource) "✓ " else "") + sourceLabel(src))
                                Text(sourceHint(src), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = { open = false; onTileSourceChange(src) },
                    )
                }
            }
        }

        if (follow != null && peer != null) {
            Card(Modifier.padding(16.dp).align(Alignment.BottomCenter)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Finding ${peer.nickname ?: "member"}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onFindTargetChange(null) }) { Text("✕") }
                    }
                    val pos = peer.lastPosition
                    val fix0 = me
                    if (pos != null && fix0 != null) {
                        val (meters, bearing) = distanceBearing(fix0, pos.latE7, pos.lonE7)
                        val dist = formatDistance(meters)
                        Text(
                            "$dist · ${bearing.toInt()}° ${compass(bearing)}" +
                                if (peer.viaMemberId != null) " · relayed" else "",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    } else {
                        Text("waiting for first fix…", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Selection toolbar: instructions while dragging, confirm/cancel once a box exists.
        if (selecting) {
            val a = dragStart
            val b = dragEnd
            val area = pendingArea
            Card(
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Save this area offline", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (a == null) "drag a rectangle over the map" else
                            if (area == null) "drag a larger rectangle" else
                                "%.4f°, %.4f°".format(area.centerLatitude, area.centerLongitude),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (area != null) {
                        val preview = clampZoomRange(
                            PROVIDER_MAX_ZOOM, MIN_ZOOM, mapZoom.coerceAtLeast(DETAIL_ZOOM), area,
                        )
                        Text(
                            "zoom ${preview.first}–${preview.last} · ${estimateLabel(estimateTiles(area, preview.first, preview.last))}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { exitSelection() }) { Text("Cancel") }
                        Button(
                            onClick = { exitSelection() },
                            enabled = area != null,
                        ) { Text("Use this area") }
                    }
                }
            }
        }

        // Confirm + name the offline area.
        pendingArea?.let { area ->
            OfflineAreaDialog(
                bbox = area,
                providerLabel = providerLabel,
                currentZoom = mapZoom,
                onDismiss = { pendingArea = null },
                onConfirm = { name, zoomMin, zoomMax ->
                    pendingArea = null
                    selecting = false
                    dragStart = null
                    dragEnd = null
                    startOfflineDownload(
                        context = context,
                        area = area,
                        name = name,
                        zoomMin = zoomMin,
                        zoomMax = zoomMax,
                        provider = provider,
                        apiKey = tileApiKey,
                        onProgress = { ui, cm ->
                            downloadUi = ui
                            if (cm != null) activeCacheManager.value = cm
                        },
                        onDone = { ui ->
                            downloadUi = ui
                            activeCacheManager.value = null
                        },
                    )
                },
            )
        }

        // Download progress with cancel.
        downloadUi?.let { ui ->
            Card(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ui.title, style = MaterialTheme.typography.titleSmall)
                    val frac = if (ui.total > 0) ui.done.toFloat() / ui.total.toFloat() else 0f
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        buildString {
                            if (ui.total > 0) append("${ui.done} / ${ui.total} tiles") else append("preparing…")
                            if (ui.zoom > 0) append(" · z${ui.zoom}")
                            if (ui.errors > 0) append(" · ${ui.errors} error(s)")
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (ui.finished) {
                        Button(onClick = { downloadUi = null }) { Text("Done") }
                    } else {
                        TextButton(onClick = {
                            activeCacheManager.value?.cancelAllJobs()
                            downloadUi = ui.copy(title = "cancelling…", finished = true)
                        }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** Progress of a running offline-area download. */
data class DownloadUi(
    val title: String,
    val done: Int = 0,
    val total: Int = 0,
    val zoom: Int = 0,
    val errors: Int = 0,
    val finished: Boolean = false,
)

/**
 * Runs a [CacheManager] download for [area] on a worker thread, reporting progress through
 * [onProgress] and the final state through [onDone]. Kept out of the composable so the
 * threading and region bookkeeping are testable and not buried in UI code.
 */
fun startOfflineDownload(
    context: Context,
    area: BoundingBox,
    name: String,
    zoomMin: Int,
    zoomMax: Int,
    provider: TileProvider,
    apiKey: String = "",
    onProgress: (DownloadUi, CacheManager?) -> Unit = { _, _ -> },
    onDone: (DownloadUi) -> Unit = { },
) {
    val handler = Handler(Looper.getMainLooper())
    fun post(fn: () -> Unit) = handler.post(fn)
    val title = "Downloading \"$name\""
    onProgress(DownloadUi(title), null)

    Thread {
        var ui = DownloadUi(title)
        try {
            configureOsmdroid(context)
            // The key is part of the URL, never the source name, so it can't affect cache identity.
            val cm = CacheManager(buildTileSource(provider, apiKey), SqlTileWriter(), zoomMin, zoomMax)
            val total = cm.possibleTilesInArea(area, zoomMin, zoomMax)
            post { ui = ui.copy(total = total); onProgress(ui, cm) }
            val region = OfflineRegion(
                id = OfflineRegions.newId(),
                name = name,
                latNorth = area.latNorth, lonEast = area.lonEast,
                latSouth = area.latSouth, lonWest = area.lonWest,
                zoomMin = zoomMin, zoomMax = zoomMax,
                tileCount = total,
                sizeBytes = 0L,
                createdAt = System.currentTimeMillis(),
                sourceName = provider.id,
            )
            cm.downloadAreaAsyncNoUI(
                context, area, zoomMin, zoomMax,
                object : CacheManager.CacheManagerCallback {
                    override fun downloadStarted() = Unit
                    override fun setPossibleTilesInArea(n: Int) {
                        post { ui = ui.copy(total = n); onProgress(ui, cm) }
                    }
                    // osmdroid's signature is (progress, currentZoomLevel, zoomMin, zoomMax).
                    override fun updateProgress(progress: Int, currentZoom: Int, zMin: Int, zMax: Int) {
                        post {
                            ui = ui.copy(done = progress, zoom = currentZoom)
                            onProgress(ui, cm)
                        }
                    }
                    override fun onTaskComplete() = saveRegion {
                        ui.copy(title = "Saved \"$name\" for offline use", done = ui.total, finished = true)
                    }
                    // osmdroid passes the number of failed tile downloads, not an error code.
                    override fun onTaskFailed(errors: Int) = saveRegion {
                        ui.copy(
                            title = "Download finished with $errors error(s)",
                            done = ui.total,
                            errors = errors,
                            finished = true,
                        )
                    }

                    // Sizing walks every tile key and queries cache.db: keep it off the main thread.
                    private fun saveRegion(final: () -> DownloadUi) {
                        Thread {
                            val size = OfflineRegions.regionSizeBytes(context, region)
                            OfflineRegions.add(context, region.copy(sizeBytes = size))
                            post { onDone(final()) }
                        }.apply { isDaemon = true }.start()
                    }
                },
            )
            // downloadAreaAsync returns immediately; keep the CacheManager reachable so the
            // UI can cancel, and let the callbacks above drive completion.
        } catch (e: Exception) {
            post { onDone(ui.copy(title = "Download error: ${e.message}", finished = true)) }
        }
    }.apply { isDaemon = true }.start()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDirectionArrow(
    from: Offset,
    end: Offset,
    color: Color,
) {
    val dx = end.x - from.x
    val dy = end.y - from.y
    if (dx * dx + dy * dy < 20f * 20f) return
    drawLine(color, from, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
    val angle = atan2(dy, dx)
    val wing = 16.dp.toPx()
    listOf(-0.45f, 0.45f).forEach { da ->
        drawLine(
            color, end,
            Offset(end.x - kotlin.math.cos(angle + da) * wing, end.y - kotlin.math.sin(angle + da) * wing),
            strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CompassHud(azimuth: Float?, isDarkTheme: Boolean, modifier: Modifier = Modifier) {
    val errorColor = MaterialTheme.colorScheme.error
    val needleColor = MaterialTheme.colorScheme.primary
    val ringColor = if (isDarkTheme) Color(0xFF9A9A9A) else Color(0xFF555555)
    val tickColor = if (isDarkTheme) Color(0xFFBBBBBB) else Color(0xFF444444)
    val labelColor = if (isDarkTheme) Color(0xFFEEEEEE) else Color(0xFF222222)
    val textMeasurer = rememberTextMeasurer()
    Card(modifier) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(68.dp)) {
                val c = center
                val r = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(color = ringColor, radius = r, style = Stroke(2.dp.toPx()))
                val labelHop = 22.dp.toPx()
                val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = labelColor)
                for ((idx, name) in listOf("N", "E", "S", "W").withIndex()) {
                    val ang = Math.toRadians(idx * 90.0)
                    val sx = (c.x + sin(ang) * (r - 6.dp.toPx())).toFloat()
                    val sy = (c.y - cos(ang) * (r - 6.dp.toPx())).toFloat()
                    val tx = (c.x + sin(ang) * (r - labelHop)).toFloat()
                    val ty = (c.y - cos(ang) * (r - labelHop)).toFloat()
                    if (name == "N") {
                        drawLine(errorColor, Offset(sx, sy), Offset(tx, ty), strokeWidth = 2.5.dp.toPx())
                    } else {
                        drawLine(tickColor, Offset(sx, sy), Offset(tx, ty), strokeWidth = 1.5.dp.toPx())
                    }
                    val layout = textMeasurer.measure(name, style = labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(tx - layout.size.width / 2f, ty - layout.size.height / 2f),
                    )
                }
                if (azimuth != null) {
                    rotate(azimuth, pivot = c) {
                        drawLine(
                            needleColor,
                            Offset(c.x, c.y - r + 18.dp.toPx()),
                            c,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            Text(
                if (azimuth == null) "--°" else "${azimuth.toInt()}°",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** FR-6.4: [PeerList] measured from this device's own live GNSS fix. */
@Composable
fun PeerList(
    peers: StateFlow<Map<MemberId, PeerState>>,
    modifier: Modifier = Modifier,
    findTarget: MemberId? = null,
    onFind: (MemberId?) -> Unit = {},
    emptyText: String = "No members heard yet.",
    onRename: ((MemberId, String?) -> Unit)? = null,
) {
    val me = rememberDeviceFix(LocalContext.current)?.location
    PeerList(peers, me, modifier, findTarget, onFind, emptyText, onRename)
}

/** FR-6.4: mapless alternative — distance and bearing to each peer. */
@Composable
fun PeerList(
    peers: StateFlow<Map<MemberId, PeerState>>,
    me: Location?,
    modifier: Modifier = Modifier,
    findTarget: MemberId? = null,
    onFind: (MemberId?) -> Unit = {},
    emptyText: String = "No members heard yet.",
    /** Give a member a name on this phone (null/blank = back to their own name). */
    onRename: ((MemberId, String?) -> Unit)? = null,
) {
    val ps by peers.collectAsState()
    var renaming by remember { mutableStateOf<PeerState?>(null) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Members", style = MaterialTheme.typography.titleMedium)
        if (ps.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Tap Find to get an arrow and distance to someone on the map.", style = MaterialTheme.typography.bodySmall)
        }
        ps.values.forEach { peer ->
            val pos = peer.lastPosition
            val isTarget = peer.memberId == findTarget
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val name = (if (isTarget) "→ " else "") + (peer.nickname ?: "member") + " (${viaLabel(peer.transport)})"
                if (pos == null || me == null) {
                    Text(
                        "$name — unlocated, last seen ${age(peer.lastSeenMs)} ago",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val (meters, bearing) = distanceBearing(me, pos.latE7, pos.lonE7)
                    Text(
                        "$name — ${formatDistance(meters)}, bearing ${bearing.toInt()}° ${compass(bearing)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (findTarget != null && !isTarget) Color.Gray else Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onRename != null) TextButton(onClick = { renaming = peer }) { Text("Rename") }
                if (isTarget) {
                    TextButton(onClick = { onFind(null) }) { Text("Stop") }
                } else {
                    TextButton(onClick = { onFind(peer.memberId) }) { Text("Find") }
                }
            }
        }
    }
    renaming?.let { peer ->
        var text by remember(peer.memberId) { mutableStateOf(peer.nickname.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename member") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Only on this phone. Empty = the name they chose.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename?.invoke(peer.memberId, text); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/** How the member is reached now (LNP/1 rung, or relays). */
internal fun viaLabel(t: com.icegood.findmeinwood.core.model.TransportId?): String = when (t) {
    null -> "via others"
    com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH -> "Bluetooth"
    com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT -> "Wi-Fi Direct"
    com.icegood.findmeinwood.core.model.TransportId.LORA -> "LoRa"
    com.icegood.findmeinwood.core.model.TransportId.SMS -> "SMS"
    com.icegood.findmeinwood.core.model.TransportId.INTERNET -> "messenger"
}

private fun age(lastSeenMs: Long): String {
    val s = (System.currentTimeMillis() - lastSeenMs).coerceAtLeast(0) / 1000
    return if (s < 60) "${s}s" else "${s / 60}m"
}

private data class LastView(val lat: Double, val lon: Double, val zoom: Double)

private fun lastView(context: Context): LastView? {
    val prefs = context.getSharedPreferences("map_view", Context.MODE_PRIVATE)
    if (prefs.contains("lat")) {
        return LastView(
            prefs.getFloat("lat", 0f).toDouble(),
            prefs.getFloat("lon", 0f).toDouble(),
            prefs.getFloat("zoom", INITIAL_ZOOM.toFloat()).toDouble(),
        )
    }
    // First run: whatever location the phone already knows (no new fix is requested).
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
    val known = runCatching {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull() ?: return null
    return LastView(known.latitude, known.longitude, INITIAL_ZOOM)
}

private fun saveLastView(context: Context, mv: MapView) {
    val c = mv.mapCenter
    // A never-laid-out view still sits at 0,0: nothing worth remembering.
    if (c.latitude == 0.0 && c.longitude == 0.0) return
    context.getSharedPreferences("map_view", Context.MODE_PRIVATE).edit()
        .putFloat("lat", c.latitude.toFloat())
        .putFloat("lon", c.longitude.toFloat())
        .putFloat("zoom", mv.zoomLevelDouble.toFloat())
        .apply()
}

private fun createMapView(
    context: Context,
    source: TileSource,
    isDarkTheme: Boolean,
    provider: TileProvider,
    apiKey: String,
): MapView {
    configureOsmdroid(context)

    val archive = context.getExternalFilesDir(null)?.let { File(it, "maps/offline.mbtiles") }
    val haveArchive = archive?.exists() == true

    // Tile source shared by online download and the offline cache: cache reuse only
    // works when the same provider id is used in both paths. In OFFLINE the caller
    // passes the provider that was last used online, so saved areas are found.
    val tiles = buildTileSource(provider, apiKey)

    val mv = MapView(context).apply {
        if (source == TileSource.OFFLINE) {
            val src = XYTileSource("mbtiles", 0, 19, 256, ".png", arrayOf())
            val providers = mutableListOf<MapTileModuleProviderBase>()
            // MBTiles archive takes priority, then the on-device tile cache so saved
            // offline areas (and anything viewed online) are usable while fully offline.
            // Downloads and online browsing both go to osmdroid's SQLite cache (cache.db),
            // so that is what must be read here — the folder-based filesystem provider
            // never sees those tiles and the map stayed an empty grid.
            if (haveArchive) {
                providers += MapTileFileArchiveProvider(
                    SimpleRegisterReceiver(context), src,
                    arrayOf(MBTilesFileArchive.getDatabaseFileArchive(archive!!)),
                )
            }
            providers += MapTileSqlCacheProvider(SimpleRegisterReceiver(context), tiles)
            tileProvider = MapTileProviderArray(tiles, SimpleRegisterReceiver(context), providers.toTypedArray())
            setUseDataConnection(false)
        } else {
            tileProvider = MapTileProviderBasic(context, tiles)
            setUseDataConnection(true)
        }
        setBackgroundColor(if (isDarkTheme) 0xFF1A1A1A.toInt() else android.graphics.Color.WHITE)
        setMultiTouchControls(true)
        setBuiltInZoomControls(false)
        // Open where the map was last left (or at the last known location), not at 0°,0°
        // in the ocean while waiting for the first GPS fix.
        val last = lastView(context)
        controller.setZoom(last?.zoom ?: INITIAL_ZOOM)
        last?.let { controller.setCenter(GeoPoint(it.lat, it.lon)) }
        // Bottom-left scale bar (theme-aware colors).
        val barText = if (isDarkTheme) 0xFFDDDDDD.toInt() else 0xFF222222.toInt()
        overlays.add(
            ScaleBarOverlay(this).apply {
                setCentred(false)
                setAlignBottom(true)
                setAlignRight(false)
                setScaleBarOffset(12, 12)
                setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric)
                setLineWidth(3f * resources.displayMetrics.density)
                setTextPaint(android.graphics.Paint().apply {
                    color = barText
                    textSize = 13f * resources.displayMetrics.scaledDensity
                })
                setBarPaint(android.graphics.Paint().apply {
                    // Default FILL style closes the bar path into a solid black box.
                    style = android.graphics.Paint.Style.STROKE
                    color = barText
                    strokeWidth = 3f * resources.displayMetrics.density
                })
            },
        )
    }

    return mv
}

/** FR-4.1/FR-6.2: my own position + movement bearing from consecutive GNSS fixes. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberDeviceFix(context: Context): DeviceFix? {
    var location by remember { mutableStateOf<Location?>(null) }
    var heading by remember { mutableStateOf<Float?>(null) }
    var lastFix by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val prev = lastFix
                lastFix = loc
                if (prev != null) {
                    val results = FloatArray(2)
                    Location.distanceBetween(prev.latitude, prev.longitude, loc.latitude, loc.longitude, results)
                    val moved = results[0]
                    val dt = loc.time - prev.time
                    if (moved >= 3f && dt in 300L..30_000L) {
                        val brg = (results[1] + 360) % 360
                        heading = blendHeading(heading, brg)
                    }
                }
                location = loc
            }
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
                if (last != null && (location == null || last.time > location!!.time)) {
                    location = last
                    lastFix = last
                }
                lm.requestLocationUpdates(provider, 2_000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            // no location permission: map stays without "me" (UI gates session start)
        } catch (_: IllegalArgumentException) {
            // provider unavailable
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return location?.let { DeviceFix(it, heading) }
}

/** Device azimuth (heading the top of the phone points to) from accel + magnetometer. */
@Composable
private fun rememberCompassAzimuth(context: Context): State<Float?> {
    val azimuth = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = FloatArray(3)
        val mag = FloatArray(3)
        var haveAccel = false
        var haveMag = false
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accel, 0, 3)
                        haveAccel = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, mag, 0, 3)
                        haveMag = true
                    }
                }
                if (haveAccel && haveMag && SensorManager.getRotationMatrix(rot, null, accel, mag)) {
                    SensorManager.getOrientation(rot, ori)
                    val deg = (Math.toDegrees(ori[0].toDouble()).toFloat() + 360f) % 360f
                    val prev = azimuth.value
                    if (prev == null || abs(prev - deg) > 1f) azimuth.value = deg
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelSensor != null) sm.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        if (magSensor != null) sm.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return azimuth
}

// Maps recentered once per (re)created MapView so a fresh view with a default
// center (0,0, mid-Atlantic) is brought onto the user instead of staying blank.
private val centeredOnce: MutableSet<MapView> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<MapView, Boolean>())

private fun renderMarkers(
    mv: MapView,
    profile: NetworkProfile?,
    peers: Map<MemberId, PeerState>,
    me: Location?,
    follow: MemberId?,
    onSelect: (MemberId?) -> Unit,
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
        if (!centeredOnce.contains(mv)) {
            centeredOnce.add(mv)
            mv.controller.animateTo(GeoPoint(me.latitude, me.longitude))
        }
    }
    peers.values.forEach { peer ->
        val pos = peer.lastPosition ?: return@forEach
        val isTarget = peer.memberId == follow
        mv.overlays.add(
            Marker(mv).apply {
                position = GeoPoint(pos.latE7 / 1e7, pos.lonE7 / 1e7)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = (if (isTarget) "→ " else "") + (peer.nickname ?: "member")
                snippet = viaLabel(peer.transport)
                if (follow != null && !isTarget) {
                    // While searching for someone, everyone else stays visible but greyed out.
                    icon = icon?.mutate()?.apply {
                        colorFilter = android.graphics.ColorMatrixColorFilter(
                            android.graphics.ColorMatrix().apply { setSaturation(0f) },
                        )
                    }
                    alpha = 0.45f
                } else if (follow == null) {
                    // With no target yet, tapping a member picks them; once set, taps only
                    // show the info bubble so the target is not switched by accident.
                    setOnMarkerClickListener { _, _ -> onSelect(peer.memberId); true }
                }
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
