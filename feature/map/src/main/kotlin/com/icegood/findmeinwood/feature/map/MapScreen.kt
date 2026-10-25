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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
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
import com.icegood.findmeinwood.core.session.SpoofGuard
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Projection
import org.maplibre.android.maps.Style

/**
 * FR-6.2/FR-6.5: shows my position (device GNSS) plus every located peer on a MapLibre map:
 * OpenFreeMap vector data by default, or a raster provider with its own options. Offline
 * shows only what is on the phone (saved areas and places viewed before).
 *
 * Extra navigation aids: pick a member to find (tap their marker, or "Find" in the peer
 * list) — others turn grey, an arrow points at them with the distance at its tip, and a
 * card shows distance + bearing until cleared with ✕; a scale bar (bottom-left), a compass, and an arrow for my own movement
 * derived from consecutive GPS fixes.
 */
/** My GNSS fix plus the movement heading derived from consecutive fixes. */
data class DeviceFix(val location: Location, val headingDeg: Float?, val gpsSpoofed: Boolean = false)

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

internal fun formatDistance(ctx: Context, meters: Double): String =
    if (meters >= 1000) ctx.getString(R.string.distance_km, "%.1f".format(meters / 1000))
    else ctx.getString(R.string.distance_m, meters.toInt())

internal fun layerLabel(ctx: Context, l: MapLayer) =
    if (l.offline) ctx.getString(R.string.layer_offline, l.provider.label) else l.provider.label

/** The chooser's second line for a provider: what it is, coverage, and what it still needs. */
internal fun providerHint(ctx: Context, p: TileProvider, values: Map<String, String>): String = buildList {
    add(ctx.getString(if (p.satellite) R.string.kind_satellite else R.string.kind_streets))
    p.coverage?.let { add(ctx.getString(it)) }
    val missing = p.missing(values)
    when {
        missing.isNotEmpty() -> add(ctx.getString(R.string.needs, missing.joinToString { ctx.getString(it.label) }))
        p.options.any { !values[it.param].isNullOrBlank() } -> add(ctx.getString(R.string.option_set, ctx.getString(p.options.first().label)))
    }
}.joinToString(" · ")

private fun compass(ctx: Context, bearing: Double): String =
    ctx.resources.getStringArray(R.array.compass_points)[((bearing + 22.5) / 45).toInt() % 8]

/**
 * Confirmation sheet for an offline area: name it and see what it costs. The zooms are
 * not a choice: overview down to the provider's trail detail, always.
 */
@Composable
private fun OfflineAreaDialog(
    bbox: GeoBox,
    provider: TileProvider,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    val ctx = LocalContext.current
    val initialName = remember(bbox) { ctx.getString(R.string.area_name_default, "%.3f°, %.3f°".format(bbox.centerLatitude, bbox.centerLongitude)) }
    var name by remember(bbox) { mutableStateOf(initialName) }
    val tiles = remember(bbox, provider) { offlineTiles(bbox, provider) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_offline_area)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.area_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (tiles != null) {
                        stringResource(R.string.area_trail_detail, provider.label, estimateLabel(ctx, tiles, provider.tileBytes()))
                    } else {
                        stringResource(R.string.area_too_large, provider.label, provider.downloadTiles.toString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tiles != null) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = tiles != null,
                onClick = { onConfirm(name.trim().ifBlank { initialName }) },
            ) { Text(stringResource(R.string.download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val INITIAL_ZOOM = 14.0

@Composable
fun MapScreen(
    profile: StateFlow<NetworkProfile?>,
    peers: StateFlow<Map<MemberId, PeerState>>,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
    layer: MapLayer = MapLayer.DEFAULT,
    /** Each provider's own option values (param -> value). */
    tileOptions: Map<TileProvider, Map<String, String>> = emptyMap(),
    onLayerChange: (MapLayer) -> Unit = {},
    onTileOptions: (TileProvider, Map<String, String>) -> Unit = { _, _ -> },
    /**
     * The member being searched for. Hoisted so the choice survives tab switches and can
     * be made from the peer list; only the ✕ in the target card clears it.
     */
    findTarget: MemberId? = null,
    onFindTargetChange: (MemberId?) -> Unit = {},
) {
    val context = LocalContext.current
    val memberWord = stringResource(R.string.member)
    val p by profile.collectAsState()
    val ps by peers.collectAsState()
    val fix = rememberDeviceFix(context)
    val me = fix?.location
    val follow = findTarget
    var mapVersion by remember { mutableStateOf(0) }
    val azimuth = rememberCompassAzimuth(context)
    // Offline reads the tiles saved from the layer's provider, so saved areas appear
    // without a connection; the option values belong to that provider only.
    val provider = layer.provider
    val providerLabel = provider.label
    val providerValues = tileOptions[provider].orEmpty()
    var optionsFor by remember { mutableStateOf<TileProvider?>(null) }

    // Offline-area picking: drag a rectangle on the map, then name it and confirm.
    var selecting by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<GeoPoint?>(null) }
    var dragEnd by remember { mutableStateOf<GeoPoint?>(null) }
    var pendingArea by remember { mutableStateOf<GeoBox?>(null) }
    var downloadUi by remember { mutableStateOf<DownloadUi?>(null) }
    val activeDownload = remember { mutableStateOf<OfflineRegions.Download?>(null) }

    fun exitSelection() {
        selecting = false
        dragStart = null
        dragEnd = null
        pendingArea = null
    }

    val accent = MaterialTheme.colorScheme.primary
    val myDirColor = MaterialTheme.colorScheme.secondary
    val labelBg = MaterialTheme.colorScheme.primary
    val labelFg = MaterialTheme.colorScheme.onPrimary
    val greyBg = MaterialTheme.colorScheme.surfaceVariant
    val greyFg = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val peer = follow?.let { ps[it] }

    Box(modifier.fillMaxSize()) {
        // A new map whenever the layer, its options or the theme change: the style and the
        // connection mode belong to the view (AndroidView's factory runs once per node).
        key(layer, providerValues, isDarkTheme) {
            val mapView = remember { createMapView(context) }
            var map by remember { mutableStateOf<MapLibreMap?>(null) }
            var centered by remember { mutableStateOf(false) }
            val currentFollow by rememberUpdatedState(follow)
            val currentPeers by rememberUpdatedState(ps)
            val currentOnFind by rememberUpdatedState(onFindTargetChange)
            val tapRadius = with(LocalDensity.current) { 32.dp.toPx() }

            DisposableEffect(mapView) {
                // Offline shows only what is on the phone: saved areas and places viewed.
                MapLibre.setConnected(!layer.offline)
                mapView.onStart()
                mapView.onResume()
                mapView.getMapAsync { m ->
                    m.uiSettings.apply {
                        setLogoEnabled(false)
                        setAttributionEnabled(false) // our own attribution card
                        setCompassEnabled(false) // our own compass card
                        setRotateGesturesEnabled(false) // north stays up, as the compass assumes
                        setTiltGesturesEnabled(false)
                    }
                    // Open where the map was last left (or at the last known location), not
                    // in the ocean while waiting for the first GPS fix.
                    lastView(context)?.let {
                        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), it.zoom))
                    }
                    // OpenStreetMap's tile policy: fetch what is viewed, no lower zooms ahead.
                    m.setPrefetchZoomDelta(if (layer.provider == TileProvider.MAPNIK) 0 else 4)
                    m.setStyle(mapStyle(layer, providerValues, isDarkTheme))
                    m.addOnCameraMoveListener { mapVersion++ }
                    m.addOnCameraIdleListener { mapVersion++ }
                    m.addOnMapClickListener { ll ->
                        // With no target yet, tapping a member picks them; once set, a stray
                        // tap must never switch or drop the person being searched for.
                        if (currentFollow != null) return@addOnMapClickListener false
                        val tap = m.projection.toScreenLocation(ll)
                        val hit = currentPeers.values.mapNotNull { peer ->
                            peer.lastPosition?.let { pos ->
                                val pt = m.projection.toScreenLocation(LatLng(pos.latE7 / 1e7, pos.lonE7 / 1e7))
                                peer to kotlin.math.hypot(pt.x - tap.x, pt.y - tap.y)
                            }
                        }.filter { it.second <= tapRadius }.minByOrNull { it.second }?.first
                        hit?.let { currentOnFind(it.memberId) }
                        hit != null
                    }
                    map = m
                    mapVersion++
                }
                onDispose {
                    map?.let { saveLastView(context, it) }
                    mapView.onPause()
                    mapView.onStop()
                    mapView.onDestroy()
                }
            }

            // Bring a fresh map onto me once, or onto the first located member while I have
            // no fix of my own and the map still shows the whole world.
            LaunchedEffect(map, me != null) {
                val m = map ?: return@LaunchedEffect
                if (me != null && !centered) {
                    centered = true
                    m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(me.latitude, me.longitude)))
                }
            }
            LaunchedEffect(map, ps) {
                val m = map ?: return@LaunchedEffect
                if (me == null && m.cameraPosition.zoom < 9.0) {
                    ps.values.firstNotNullOfOrNull { it.lastPosition }?.let {
                        m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(it.latE7 / 1e7, it.lonE7 / 1e7)))
                    }
                }
            }

            AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })

            // Everything on the map is drawn here: members (named; greyed while searching for
            // someone else), me with my direction, and the arrow to the member being searched
            // for, ending at them when visible or on the screen edge with the distance there.
            Canvas(Modifier.fillMaxSize()) {
                val tick = mapVersion
                val proj = map?.projection ?: return@Canvas
                fun px(lat: Double, lon: Double) = proj.toScreenLocation(LatLng(lat, lon)).let { Offset(it.x, it.y) }
                fun label(text: String, at: Offset, bg: Color, fg: Color) {
                    val layout = textMeasurer.measure(
                        text, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg),
                    )
                    val pad = 4.dp.toPx()
                    val w = layout.size.width + 2 * pad
                    val h = layout.size.height + pad
                    val top = Offset(at.x - w / 2, at.y + 12.dp.toPx())
                    drawRoundRect(bg, top, Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2))
                    drawText(layout, topLeft = Offset(top.x + pad, top.y + pad / 2))
                }
                ps.values.forEach { peer ->
                    val pos = peer.lastPosition ?: return@forEach
                    val at = px(pos.latE7 / 1e7, pos.lonE7 / 1e7)
                    val greyed = follow != null && peer.memberId != follow
                    drawCircle(Color.White, 9.dp.toPx(), at)
                    drawCircle(if (greyed) Color.Gray else accent, 7.dp.toPx(), at)
                    label(
                        (if (peer.memberId == follow) "→ " else "") + (peer.nickname ?: memberWord),
                        at, if (greyed) greyBg else labelBg, if (greyed) greyFg else labelFg,
                    )
                }
                val meNow = me
                if (meNow != null) {
                    val my = px(meNow.latitude, meNow.longitude)
                    // How rough my own fix is, as a faint disc.
                    val mpp = metersPerPx(proj, my)
                    if (mpp > 0) {
                        drawCircle(myDirColor.copy(alpha = 0.12f), (meNow.accuracy / mpp).toFloat(), my)
                    }
                    // My direction: movement heading from consecutive fixes, falling back
                    // to the compass azimuth so it always shows (not only while walking).
                    val myDirection = fix?.headingDeg ?: azimuth.value
                    if (myDirection != null) {
                        rotate(myDirection, pivot = my) {
                            val arrow = Path().apply {
                                moveTo(my.x, my.y - 16.dp.toPx())
                                lineTo(my.x - 7.dp.toPx(), my.y + 9.dp.toPx())
                                lineTo(my.x, my.y + 3.dp.toPx())
                                lineTo(my.x + 7.dp.toPx(), my.y + 9.dp.toPx())
                                close()
                            }
                            drawPath(arrow, color = Color.White, style = Stroke(3.dp.toPx()))
                            drawPath(arrow, color = myDirColor)
                        }
                    } else {
                        drawCircle(Color.White, 9.dp.toPx(), my)
                        drawCircle(myDirColor, 7.dp.toPx(), my)
                    }
                    val target = follow?.let { ps[it] }?.lastPosition
                    if (target != null) {
                        val t = px(target.latE7 / 1e7, target.lonE7 / 1e7)
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
                            val targetPeer = ps[follow]
                            val bound = targetPeer?.let { radioBoundM(meters, it.transport, heardRecently(it)) }
                            val layout = textMeasurer.measure(
                                bound?.let { context.getString(R.string.nearby_within, it) } ?: formatDistance(context, meters),
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
                // Scale bar, bottom-left.
                val barLeft = Offset(16.dp.toPx(), size.height - 20.dp.toPx())
                val mpp = metersPerPx(proj, barLeft)
                scaleBar(mpp, 100.dp.toPx())?.let { (meters, len) ->
                    val barColor = if (isDarkTheme) Color(0xFFDDDDDD) else Color(0xFF222222)
                    val right = Offset(barLeft.x + len, barLeft.y)
                    val tickH = 6.dp.toPx()
                    drawLine(barColor, barLeft, right, strokeWidth = 3.dp.toPx())
                    drawLine(barColor, barLeft, Offset(barLeft.x, barLeft.y - tickH), strokeWidth = 3.dp.toPx())
                    drawLine(barColor, right, Offset(right.x, right.y - tickH), strokeWidth = 3.dp.toPx())
                    val layout = textMeasurer.measure(
                        formatDistance(context, meters.toDouble()),
                        style = TextStyle(fontSize = 13.sp, color = barColor),
                    )
                    drawText(layout, topLeft = Offset(barLeft.x, barLeft.y - tickH - layout.size.height))
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
                    val proj = map?.projection ?: return@Canvas
                    if (a == null || b == null) return@Canvas
                    val pa = proj.toScreenLocation(LatLng(a.latitude, a.longitude))
                    val pb = proj.toScreenLocation(LatLng(b.latitude, b.longitude))
                    val tl = Offset(minOf(pa.x, pb.x), minOf(pa.y, pb.y))
                    val br = Offset(maxOf(pa.x, pb.x), maxOf(pa.y, pb.y))
                    val rect = androidx.compose.ui.geometry.Rect(tl, br)
                    drawRect(color = boxColor.copy(alpha = 0.18f), topLeft = tl, size = rect.size)
                    drawRect(color = boxColor, topLeft = tl, size = rect.size, style = Stroke(2.dp.toPx()))
                    // Corner handles make it obvious a rectangle (not a path) is being picked.
                    listOf(tl, Offset(br.x, tl.y), br, Offset(tl.x, br.y)).forEach { c ->
                        drawCircle(color = boxColor, radius = 5.dp.toPx(), center = c)
                    }
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        fun at(o: Offset): GeoPoint? = map?.projection
                            ?.fromScreenLocation(android.graphics.PointF(o.x, o.y))
                            ?.let { GeoPoint(it.latitude, it.longitude) }
                        detectDragGestures(
                            onDragStart = { off ->
                                dragStart = at(off)
                                dragEnd = dragStart
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragEnd = at(change.position)
                            },
                            onDragEnd = { dragStart?.let { dragEnd?.let { end -> pendingArea = dragToGeoBox(it, end) } } },
                            onDragCancel = { dragStart = null; dragEnd = null },
                        )
                    },
                )
            }

            // Recenter the map on my current location.
            if (me != null) {
                SmallFloatingActionButton(
                    onClick = { map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(me.latitude, me.longitude))) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Text("⌖", style = MaterialTheme.typography.titleMedium) }
            }

            // Pick a rectangle to save for offline use, Google-Maps style.
            if (!layer.offline && !selecting && downloadUi == null) {
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
                (p?.name ?: stringResource(R.string.no_network_running)) + " · " +
                    stringResource(R.string.others_heard, ps.size) + " · " + myFixText(context, fix),
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
                Text(stringResource(R.string.map_chip, layerLabel(context, layer)), style = MaterialTheme.typography.labelMedium)
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text((if (layer.offline) "✓ " else "") + stringResource(R.string.offline))
                            Text(
                                stringResource(R.string.offline_hint, provider.label),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    onClick = { open = false; onLayerChange(layer.copy(offline = true)) },
                )
                TileProvider.entries.forEach { p ->
                    val values = tileOptions[p].orEmpty()
                    val selected = !layer.offline && p == provider
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text((if (selected) "✓ " else "") + p.label)
                                Text(providerHint(context, p, values), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            open = false
                            onLayerChange(MapLayer(p, offline = false))
                            // A provider that cannot serve tiles yet asks for what it needs at once.
                            if (p.missing(values).isNotEmpty()) optionsFor = p
                        },
                    )
                    // The selected provider's own options: key, token… as that service names them.
                    if (p == provider && p.options.isNotEmpty()) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("    " + stringResource(R.string.provider_options_menu, p.label), style = MaterialTheme.typography.labelMedium) },
                            onClick = { open = false; optionsFor = p },
                        )
                    }
                }
            }
        }

        // The selected provider still lacks a required option: say so instead of blank tiles.
        if (!layer.offline && provider.missing(providerValues).isNotEmpty() && optionsFor == null) {
            Card(Modifier.padding(16.dp).align(Alignment.Center)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.provider_needs, provider.label, provider.missing(providerValues).joinToString { context.getString(it.label) }),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Button(onClick = { optionsFor = provider }) { Text(stringResource(R.string.set_it)) }
                }
            }
        }

        optionsFor?.let { p ->
            ProviderOptionsDialog(
                provider = p,
                values = tileOptions[p].orEmpty(),
                onDismiss = { optionsFor = null },
                onSave = { optionsFor = null; onTileOptions(p, it) },
            )
        }

        if (follow != null && peer != null) {
            Card(Modifier.padding(16.dp).align(Alignment.BottomCenter)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.finding, peer.nickname ?: memberWord),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onFindTargetChange(null) }) { Text("✕") }
                    }
                    val pos = peer.lastPosition
                    val fix0 = me
                    if (pos != null && fix0 != null) {
                        val (meters, bearing) = distanceBearing(fix0, pos.latE7, pos.lonE7)
                        val dist = distanceText(context, meters, pos.accuracyCm / 100, fix0.accuracy.toInt(), peer.transport, heardRecently(peer))
                        val bound = radioBoundM(meters, peer.transport, heardRecently(peer))
                        Text(
                            (if (bound == null) "$dist · ${bearing.toInt()}° ${compass(context, bearing)}" else "$dist · " + stringResource(R.string.too_rough_direction)) +
                                if (peer.viaMemberId != null) " · " + stringResource(R.string.relayed) else "",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    } else {
                        Text(stringResource(R.string.waiting_first_fix), style = MaterialTheme.typography.labelLarge)
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
                    Text(stringResource(R.string.save_area_offline), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (a == null) stringResource(R.string.drag_rectangle) else
                            if (area == null) stringResource(R.string.drag_larger) else
                                "%.4f°, %.4f°".format(area.centerLatitude, area.centerLongitude),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (area != null) {
                        // Live while drawing, so the area is sized before anything is asked.
                        val tiles = offlineTiles(area, provider)
                        Text(
                            tiles?.let { estimateLabel(context, it) } ?: stringResource(R.string.too_large_draw_smaller, provider.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tiles != null) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { exitSelection() }) { Text(stringResource(R.string.cancel)) }
                        Button(
                            onClick = { exitSelection() },
                            enabled = area != null,
                        ) { Text(stringResource(R.string.use_this_area)) }
                    }
                }
            }
        }

        // Confirm + name the offline area.
        pendingArea?.let { area ->
            OfflineAreaDialog(
                bbox = area,
                provider = provider,
                onDismiss = { pendingArea = null },
                onConfirm = { name ->
                    pendingArea = null
                    selecting = false
                    dragStart = null
                    dragEnd = null
                    activeDownload.value = OfflineRegions.download(
                        context, area, name, provider, providerValues,
                        onProgress = { downloadUi = it },
                        onDone = {
                            downloadUi = it
                            activeDownload.value = null
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
                        (if (ui.total > 0) stringResource(R.string.parts_done, ui.done.toString(), ui.total.toString()) else stringResource(R.string.preparing)) +
                            if (ui.errors > 0) " · " + stringResource(R.string.errors_count, ui.errors) else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (ui.finished) {
                        Button(onClick = { downloadUi = null }) { Text(stringResource(R.string.done)) }
                    } else {
                        TextButton(onClick = {
                            activeDownload.value?.cancel()
                            activeDownload.value = null
                            downloadUi = ui.copy(title = context.getString(R.string.cancelling), finished = true)
                        }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
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
    val points = androidx.compose.ui.res.stringArrayResource(R.array.compass_points)
    Card(modifier) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(68.dp)) {
                val c = center
                val r = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(color = ringColor, radius = r, style = Stroke(2.dp.toPx()))
                val labelHop = 22.dp.toPx()
                val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = labelColor)
                for ((idx, name) in listOf(points[0], points[2], points[4], points[6]).withIndex()) {
                    val ang = Math.toRadians(idx * 90.0)
                    val sx = (c.x + sin(ang) * (r - 6.dp.toPx())).toFloat()
                    val sy = (c.y - cos(ang) * (r - 6.dp.toPx())).toFloat()
                    val tx = (c.x + sin(ang) * (r - labelHop)).toFloat()
                    val ty = (c.y - cos(ang) * (r - labelHop)).toFloat()
                    if (idx == 0) {
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
    emptyText: String? = null,
    onRename: ((MemberId, String?) -> Unit)? = null,
    onSelectLink: ((MemberId, com.icegood.findmeinwood.core.model.TransportId) -> Unit)? = null,
    onAllowLink: ((MemberId, com.icegood.findmeinwood.core.model.TransportId, Boolean) -> Unit)? = null,
    linkStatuses: List<com.icegood.findmeinwood.core.model.LinkStatus> = emptyList(),
    onLinkFix: ((com.icegood.findmeinwood.core.model.LinkFix) -> Unit)? = null,
) {
    val me = rememberDeviceFix(LocalContext.current)?.location
    PeerList(peers, me, modifier, findTarget, onFind, emptyText, onRename, onSelectLink, onAllowLink, linkStatuses, onLinkFix)
}

/** FR-6.4: mapless alternative — distance and bearing to each peer. */
@Composable
fun PeerList(
    peers: StateFlow<Map<MemberId, PeerState>>,
    me: Location?,
    modifier: Modifier = Modifier,
    findTarget: MemberId? = null,
    onFind: (MemberId?) -> Unit = {},
    /** Shown while nobody is heard; null = a plain "no members heard yet". */
    emptyText: String? = null,
    /** Give a member a name on this phone (null/blank = back to their own name). */
    onRename: ((MemberId, String?) -> Unit)? = null,
    /** Switch the link to a transport of the agreed set (LNP/3, no further handshake). */
    onSelectLink: ((MemberId, com.icegood.findmeinwood.core.model.TransportId) -> Unit)? = null,
    /** This user allows or turns down one transport both offer (LNP/3 handshake step). */
    onAllowLink: ((MemberId, com.icegood.findmeinwood.core.model.TransportId, Boolean) -> Unit)? = null,
    /** Every supported link on this phone; the unusable ones are shown greyed with why. */
    linkStatuses: List<com.icegood.findmeinwood.core.model.LinkStatus> = emptyList(),
    onLinkFix: ((com.icegood.findmeinwood.core.model.LinkFix) -> Unit)? = null,
) {
    val ps by peers.collectAsState()
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<PeerState?>(null) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.members), style = MaterialTheme.typography.titleMedium)
        if (ps.isEmpty()) {
            Text(emptyText ?: stringResource(R.string.no_members_heard), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.tap_find_hint), style = MaterialTheme.typography.bodySmall)
        }
        ps.values.forEach { peer ->
            val pos = peer.lastPosition
            val isTarget = peer.memberId == findTarget
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val name = (if (isTarget) "→ " else "") + (peer.nickname ?: stringResource(R.string.member))
                if (pos == null || me == null) {
                    Text(
                        stringResource(R.string.unlocated_seen, name, age(ctx, peer.lastSeenMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val (meters, bearing) = distanceBearing(me, pos.latE7, pos.lonE7)
                    val bound = radioBoundM(meters, peer.transport, heardRecently(peer))
                    Text(
                        "$name — " + distanceText(ctx, meters, pos.accuracyCm / 100, me.accuracy.toInt(), peer.transport, heardRecently(peer)) +
                            // A bearing from positions that contradict the radio is meaningless too.
                            (if (bound == null) ", " + stringResource(R.string.bearing, "${bearing.toInt()}° ${compass(ctx, bearing)}") else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (findTarget != null && !isTarget) Color.Gray else Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onRename != null) TextButton(onClick = { renaming = peer }) { Text(stringResource(R.string.rename)) }
                if (isTarget) {
                    TextButton(onClick = { onFind(null) }) { Text(stringResource(R.string.stop)) }
                } else {
                    TextButton(onClick = { onFind(peer.memberId) }) { Text(stringResource(R.string.find)) }
                }
            }
            LinkRow(peer, onSelectLink, onAllowLink, linkStatuses)
            UnavailableLinks(peer, linkStatuses, onLinkFix)
        }
    }
    renaming?.let { peer ->
        var text by remember(peer.memberId) { mutableStateOf(peer.nickname.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename_member)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.rename_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename?.invoke(peer.memberId, text); renaming = null }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** How far a direct link on [t] can reach at most; null when it says nothing useful. */
internal fun radioRangeM(t: com.icegood.findmeinwood.core.model.TransportId?): Int? = when (t) {
    com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH -> 100
    com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT -> 200
    else -> null
}

/**
 * Upper bound on the real distance from a direct radio link heard recently, when the two
 * positions claim more than that radio can reach (a rough indoor network fix can be off by
 * kilometres while the phones lie side by side). Null when the positions are plausible.
 */
internal fun radioBoundM(meters: Double, directVia: com.icegood.findmeinwood.core.model.TransportId?, heardRecently: Boolean): Int? {
    val range = if (heardRecently) radioRangeM(directVia) else null
    return range?.takeIf { meters > it }
}

/** Distance as shown to the user: radio evidence beats positions that contradict it. */
internal fun distanceText(
    ctx: Context, meters: Double, peerAccM: Int, myAccM: Int,
    directVia: com.icegood.findmeinwood.core.model.TransportId?, heardRecently: Boolean,
): String = radioBoundM(meters, directVia, heardRecently)
    ?.let { ctx.getString(R.string.nearby_range, it, viaLabel(ctx, directVia)) }
    ?: (formatDistance(ctx, meters) + uncertainty(ctx, peerAccM, myAccM))

private fun heardRecently(peer: PeerState) = System.currentTimeMillis() - peer.lastSeenMs < 3 * 60_000L

/**
 * " (±N m)" when the two positions together are too rough for the distance to mean much
 * (e.g. an indoor network fix of ±500 m), otherwise nothing.
 */
internal fun uncertainty(ctx: Context, peerAccM: Int, myAccM: Int): String {
    val total = peerAccM.coerceAtLeast(0) + myAccM.coerceAtLeast(0)
    return if (total > 100) " (" + ctx.getString(R.string.plus_minus_m, total) + ")" else ""
}

/**
 * The link to one member: every transport both offer, each allowed or turned down by this
 * user on its own (a transport connects them only when both users allowed it), then the
 * selected transport with a picker over the agreed ones (switching is instant, no new
 * handshake) and — when the selected link has gone quiet — a one-tap suggestion. Nothing
 * connects or switches unless the user taps.
 */
@Composable
private fun LinkRow(
    peer: PeerState,
    onSelect: ((MemberId, com.icegood.findmeinwood.core.model.TransportId) -> Unit)?,
    onAllow: ((MemberId, com.icegood.findmeinwood.core.model.TransportId, Boolean) -> Unit)?,
    statuses: List<com.icegood.findmeinwood.core.model.LinkStatus>,
) {
    val ctx = LocalContext.current
    val them = peer.nickname ?: stringResource(R.string.them)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        if (peer.linkCommon.isEmpty()) {
            Text(
                stringResource(R.string.link_exchanging, viaLabel(ctx, peer.transport)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
        }
        linkVerdict(peer, System.currentTimeMillis())?.let { v ->
            val radios = v.radios.joinToString(", ") { viaLabel(ctx, it) }
            Text(
                when (v.kind) {
                    LinkVerdict.Kind.CONNECTED -> stringResource(R.string.link_state_ok, radios)
                    LinkVerdict.Kind.DECIDE -> stringResource(R.string.link_state_decide, radios)
                    LinkVerdict.Kind.WAIT_PEER -> stringResource(R.string.link_state_wait, radios, them)
                    LinkVerdict.Kind.ALL_OFF -> stringResource(R.string.link_state_off, them)
                    LinkVerdict.Kind.SILENT -> stringResource(R.string.link_state_silent, age(ctx, peer.lastSeenMs))
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (v.kind == LinkVerdict.Kind.CONNECTED) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
        peer.linkCommon.forEach { t -> TransportDecision(peer, t, them, onAllow) }
        val link = peer.link ?: return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, enabled = onSelect != null) {
                    Text(stringResource(R.string.link_chip, viaLabel(ctx, link)), style = MaterialTheme.typography.labelSmall)
                }
                androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    peer.linkOptions.forEach { t ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (t == link) "✓ " else "") + viaLabel(ctx, t)) },
                            onClick = { open = false; onSelect?.invoke(peer.memberId, t) },
                        )
                    }
                    unavailableLinks(ctx, peer, statuses).forEach { (label, why) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("$label — $why", color = Color.Gray) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                }
            }
            peer.linkSuggestion?.let { t ->
                TextButton(onClick = { onSelect?.invoke(peer.memberId, t) }) {
                    Text(stringResource(R.string.link_quiet, viaLabel(ctx, link), viaLabel(ctx, t)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * The pair's link in one line, so the user sees what is missing: connected, a decision
 * of theirs or of this user, every link turned down, or the member not heard although a
 * link is agreed. Null while the offers are still being exchanged.
 */
internal data class LinkVerdict(val kind: Kind, val radios: List<com.icegood.findmeinwood.core.model.TransportId> = emptyList()) {
    enum class Kind { CONNECTED, DECIDE, WAIT_PEER, ALL_OFF, SILENT }
}

internal fun linkVerdict(peer: PeerState, nowMs: Long): LinkVerdict? {
    if (peer.linkCommon.isEmpty()) return null
    // What the peer allowed first: allowing that one connects at once.
    val open = peer.linkCommon.filter { it !in peer.linkAllowedByMe && it !in peer.linkDeniedByMe }
        .sortedBy { it !in peer.linkAllowedByPeer }
    val waiting = peer.linkCommon.filter { linkDecision(peer, it) == LinkDecision.WAITING }
    return when {
        peer.link != null && nowMs - peer.lastSeenMs > SILENT_AFTER_MS -> LinkVerdict(LinkVerdict.Kind.SILENT)
        peer.link != null -> LinkVerdict(LinkVerdict.Kind.CONNECTED, listOfNotNull(peer.link))
        open.isNotEmpty() -> LinkVerdict(LinkVerdict.Kind.DECIDE, open)
        waiting.isNotEmpty() -> LinkVerdict(LinkVerdict.Kind.WAIT_PEER, waiting)
        else -> LinkVerdict(LinkVerdict.Kind.ALL_OFF)
    }
}

/** Some member waits for this user to allow or turn down a link (marks the Members tab). */
fun needsLinkDecision(peers: Collection<PeerState>): Boolean =
    peers.any { linkVerdict(it, 0L)?.kind == LinkVerdict.Kind.DECIDE }

/** No beacon for three intervals (60 s each): the member is called silent. */
private const val SILENT_AFTER_MS = 3 * 60_000L

/** What one transport both offer means for the pair, as shown next to its control. */
internal enum class LinkDecision { CONNECTED, WAITING, OFF, PEER_WANTS, UNDECIDED }

internal fun linkDecision(peer: PeerState, t: com.icegood.findmeinwood.core.model.TransportId): LinkDecision {
    val mine = t in peer.linkAllowedByMe
    val theirs = t in peer.linkAllowedByPeer
    return when {
        mine && theirs -> LinkDecision.CONNECTED
        mine -> LinkDecision.WAITING
        theirs -> LinkDecision.PEER_WANTS
        t in peer.linkDeniedByMe -> LinkDecision.OFF
        else -> LinkDecision.UNDECIDED
    }
}

/**
 * One transport both offer: a switch once this user decided, or Allow / No while the
 * decision is still open — nothing connects on it before this user allows it.
 */
@Composable
private fun TransportDecision(
    peer: PeerState,
    t: com.icegood.findmeinwood.core.model.TransportId,
    them: String,
    onAllow: ((MemberId, com.icegood.findmeinwood.core.model.TransportId, Boolean) -> Unit)?,
) {
    val ctx = LocalContext.current
    val label = viaLabel(ctx, t)
    val decision = linkDecision(peer, t)
    val decided = t in peer.linkAllowedByMe || t in peer.linkDeniedByMe
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (decision) {
                LinkDecision.CONNECTED -> stringResource(R.string.link_on, label)
                LinkDecision.WAITING -> stringResource(R.string.link_waiting_for, label, them)
                LinkDecision.PEER_WANTS -> stringResource(R.string.link_peer_wants, label, them)
                LinkDecision.OFF -> stringResource(R.string.link_off, label)
                LinkDecision.UNDECIDED -> stringResource(R.string.link_decide, label)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (decision == LinkDecision.CONNECTED) Color.Unspecified else Color.Gray,
            modifier = Modifier.weight(1f),
        )
        if (decided) {
            androidx.compose.material3.Switch(
                checked = t in peer.linkAllowedByMe,
                onCheckedChange = { on -> onAllow?.invoke(peer.memberId, t, on) },
                enabled = onAllow != null,
            )
        } else {
            TextButton(onClick = { onAllow?.invoke(peer.memberId, t, false) }, enabled = onAllow != null) {
                Text(stringResource(R.string.link_decline), style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = { onAllow?.invoke(peer.memberId, t, true) }, enabled = onAllow != null) {
                Text(stringResource(R.string.link_allow), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The supported links this member cannot be reached on, with why: off or not set up on
 * this phone, or not offered by them. Notes such as satellite (carried by SMS) come last.
 */
internal fun unavailableLinks(
    ctx: Context,
    peer: PeerState,
    statuses: List<com.icegood.findmeinwood.core.model.LinkStatus>,
): List<Pair<String, String>> {
    // Transports both offer have a row of their own (allowed or not), so only the rest show.
    val usable = peer.linkCommon + peer.linkOptions
    val offersKnown = peer.link != null || peer.linkCommon.isNotEmpty()
    val links = statuses.filter { it.transport != null }.mapNotNull { s ->
        when {
            s.reason != null -> s.label to s.reason!!
            s.transport in usable || !offersKnown -> null
            else -> s.label to ctx.getString(R.string.not_offered_by, peer.nickname ?: ctx.getString(R.string.them))
        }
    }
    val notes = statuses.filter { it.transport == null && it.reason != null }.map { it.label to it.reason!! }
    return links + notes
}

/** The other supported links, greyed with the reason each one cannot be used, and a fix. */
@Composable
private fun UnavailableLinks(
    peer: PeerState,
    statuses: List<com.icegood.findmeinwood.core.model.LinkStatus>,
    onFix: ((com.icegood.findmeinwood.core.model.LinkFix) -> Unit)?,
) {
    val ctx = LocalContext.current
    val lines = unavailableLinks(ctx, peer, statuses)
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        lines.forEach { (label, why) ->
            val fix = statuses.firstOrNull { it.label == label }?.fix
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "$label — $why",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f),
                )
                if (fix != null && onFix != null) {
                    TextButton(onClick = { onFix(fix) }) { Text(fixLabel(ctx, fix), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

internal fun fixLabel(ctx: Context, f: com.icegood.findmeinwood.core.model.LinkFix): String = ctx.getString(
    when (f) {
        com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_BLUETOOTH -> R.string.fix_turn_on
        com.icegood.findmeinwood.core.model.LinkFix.TURN_ON_WIFI -> R.string.fix_turn_on
        com.icegood.findmeinwood.core.model.LinkFix.SET_PHONE_NUMBER -> R.string.fix_set_number
        com.icegood.findmeinwood.core.model.LinkFix.ALLOW_SMS -> R.string.fix_allow
    },
)

/** How the member is reached now (LNP/3 link, or relays). */
internal fun viaLabel(ctx: Context, t: com.icegood.findmeinwood.core.model.TransportId?): String = ctx.getString(
    when (t) {
        null -> R.string.via_others
        com.icegood.findmeinwood.core.model.TransportId.BLUETOOTH -> R.string.via_bluetooth
        com.icegood.findmeinwood.core.model.TransportId.WIFI_DIRECT -> R.string.via_wifi_direct
        com.icegood.findmeinwood.core.model.TransportId.LORA -> R.string.via_lora
        com.icegood.findmeinwood.core.model.TransportId.SMS -> R.string.via_sms
        com.icegood.findmeinwood.core.model.TransportId.INTERNET -> R.string.via_messenger
    },
)

private fun age(ctx: Context, lastSeenMs: Long): String {
    val s = (System.currentTimeMillis() - lastSeenMs).coerceAtLeast(0) / 1000
    return if (s < 60) ctx.getString(R.string.age_s, s.toInt()) else ctx.getString(R.string.age_min, (s / 60).toInt())
}

private data class LastView(val lat: Double, val lon: Double, val zoom: Double)

// Without the permission getLastKnownLocation throws, and runCatching makes that "no view".
@SuppressLint("MissingPermission")
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
        trustedLastKnown(lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) })
    }.getOrNull() ?: return null
    return LastView(known.latitude, known.longitude, INITIAL_ZOOM)
}

private fun saveLastView(context: Context, m: MapLibreMap) {
    val c = m.cameraPosition.target ?: return
    // A never-laid-out map still sits at 0,0: nothing worth remembering.
    if (c.latitude == 0.0 && c.longitude == 0.0) return
    context.getSharedPreferences("map_view", Context.MODE_PRIVATE).edit()
        .putFloat("lat", c.latitude.toFloat())
        .putFloat("lon", c.longitude.toFloat())
        .putFloat("zoom", m.cameraPosition.zoom.toFloat())
        .apply()
}

private fun createMapView(context: Context): MapView {
    initMapLibre(context)
    OfflineRegions.configure(context)
    // A texture view, so the members and arrows drawn above it stay in step while panning.
    return MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
}

/**
 * MapLibre with a User-Agent naming the app and where to reach its authors, as the
 * OpenStreetMap tile policy asks of every app using its tiles.
 */
internal fun initMapLibre(context: Context) {
    if (MapLibre.hasInstance()) return
    MapLibre.getInstance(context.applicationContext)
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    val agent = "FindMeInWood/$version (+$PROJECT_URL)"
    org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
        okhttp3.OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
            }
            .build(),
    )
}

private const val PROJECT_URL = "https://github.com/icegood/find_me_in_wood"

/** The MapLibre style of [layer]: OpenFreeMap's own style, or one raster provider. */
private fun mapStyle(layer: MapLayer, values: Map<String, String>, isDark: Boolean): Style.Builder =
    if (layer.provider.vector) {
        Style.Builder().fromUri(vectorStyleUrl(isDark))
    } else {
        Style.Builder().fromJson(rasterStyleJson(layer.provider, values, layer.offline, isDark))
    }

/** Ground metres per screen pixel around [at]. */
private fun metersPerPx(proj: Projection, at: Offset): Double {
    val a = proj.fromScreenLocation(android.graphics.PointF(at.x, at.y))
    val b = proj.fromScreenLocation(android.graphics.PointF(at.x + 100f, at.y))
    val out = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
    return out[0] / 100.0
}

/**
 * The longest round distance (1, 2 or 5 × 10ⁿ m) whose bar fits in [maxPx] at [metersPerPx],
 * with its length in pixels; null when the scale is unknown.
 */
internal fun scaleBar(metersPerPx: Double, maxPx: Float): Pair<Int, Float>? {
    if (!(metersPerPx > 0) || metersPerPx.isInfinite()) return null
    val maxM = metersPerPx * maxPx
    var best = 0
    var step = 1
    while (step <= 10_000_000) {
        for (k in intArrayOf(1, 2, 5)) if (k * step <= maxM) best = k * step
        step *= 10
    }
    return if (best == 0) null else best to (best / metersPerPx).toFloat()
}

/** The status-line words for my own position. */
internal fun myFixText(ctx: Context, fix: DeviceFix?): String = when {
    fix == null -> ctx.getString(R.string.waiting_gps)
    fix.gpsSpoofed -> ctx.getString(R.string.gps_faked, fix.location.accuracy.toInt())
    fix.location.provider == LocationManager.GPS_PROVIDER -> ctx.getString(R.string.fix_gps, fix.location.accuracy.toInt())
    else -> ctx.getString(R.string.fix_network, fix.location.accuracy.toInt())
}

/**
 * FR-4.1/FR-6.2: my own position + movement bearing from consecutive GNSS fixes; FR-4.5:
 * faked satellite positions are dropped and Wi-Fi/cell ones used meanwhile.
 */
@SuppressLint("MissingPermission")
@Composable
private fun rememberDeviceFix(context: Context): DeviceFix? {
    var location by remember { mutableStateOf<Location?>(null) }
    var heading by remember { mutableStateOf<Float?>(null) }
    var lastFix by remember { mutableStateOf<Location?>(null) }
    var spoofed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val guard = SpoofGuard()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val raw = loc.toRawFix() ?: return
                val passed = guard.offer(raw)
                spoofed = guard.gpsSpoofed
                if (passed == null) return
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
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).filter { lm.isProviderEnabled(it) }
            // The last known ones in time order prime the check and give a start position.
            providers.mapNotNull { lm.getLastKnownLocation(it) }.sortedBy { it.time }.forEach(listener::onLocationChanged)
            providers.forEach { lm.requestLocationUpdates(it, 2_000L, 0f, listener, Looper.getMainLooper()) }
        } catch (_: SecurityException) {
            // no location permission: map stays without "me" (UI gates session start)
        } catch (_: IllegalArgumentException) {
            // provider unavailable
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return location?.let { DeviceFix(it, heading, spoofed) }
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

/** One provider's own options: each field as that service names it, plus where to get it. */
@Composable
private fun ProviderOptionsDialog(
    provider: TileProvider,
    values: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var edited by remember(provider) { mutableStateOf(values) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_options, provider.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.options.forEach { o ->
                    androidx.compose.material3.OutlinedTextField(
                        value = edited[o.param].orEmpty(),
                        onValueChange = { edited = edited + (o.param to it) },
                        label = { Text(stringResource(o.label) + if (o.required) "" else " " + stringResource(R.string.optional)) },
                        supportingText = { Text(stringResource(R.string.option_sent_only, provider.label, "?${o.param}=…")) },
                        singleLine = true,
                    )
                }
                provider.signUp?.let { Text(stringResource(R.string.get_one_at, it), style = MaterialTheme.typography.bodySmall) }
                provider.coverage?.let { Text(stringResource(R.string.coverage, stringResource(it)), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(edited.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() })
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
