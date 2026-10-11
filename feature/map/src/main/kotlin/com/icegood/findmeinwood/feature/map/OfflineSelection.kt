package com.icegood.findmeinwood.feature.map

import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** Hard ceiling on a single offline area, so a stray drag cannot request millions of tiles. */
const val MAX_TILES_PER_AREA = 20_000L

/** Rough bytes per cached tile, used only for the pre-download size estimate. */
const val ESTIMATED_TILE_BYTES = 18_000L

/**
 * Geographic bounds spanned by a drag from [a] to [b], normalised so the result is
 * well-formed regardless of the drag direction. Returns null when the drag covers
 * less than a couple of metres, which is almost certainly an accidental tap.
 */
fun dragToBoundingBox(a: IGeoPoint, b: IGeoPoint, minSpanDegrees: Double = 0.0005): BoundingBox? {
    val latNorth = maxOf(a.latitude, b.latitude)
    val latSouth = minOf(a.latitude, b.latitude)
    var lonWest = a.longitude
    var lonEast = b.longitude
    if (lonWest > lonEast) {
        val t = lonWest
        lonWest = lonEast
        lonEast = t
    }
    if (latNorth - latSouth < minSpanDegrees || lonEast - lonWest < minSpanDegrees) return null
    return BoundingBox(latNorth, lonEast, latSouth, lonWest)
}

/** Rough on-screen size of the drag in degrees, for the "too small" hint. */
fun dragSpanDegrees(a: IGeoPoint, b: IGeoPoint): Pair<Double, Double> =
    (maxOf(a.latitude, b.latitude) - minOf(a.latitude, b.latitude)) to
        (maxOf(a.longitude, b.longitude) - minOf(a.longitude, b.longitude))

/**
 * Number of slippy-map tiles covering [bbox] at [zoom].
 *
 * This is computed analytically instead of via `CacheManager.getTilesCoverage`, which
 * materialises every tile index in a list — fine for a viewport, but a deep zoom range
 * over a wide area would allocate millions of entries just to show a count.
 *
 * Tiles that merely *intersect* the box are counted (floor on the low edge, ceil on the
 * high edge), matching osmdroid's own tile-rect behaviour: a drag smaller than one tile
 * still needs the single tile that covers it.
 */
fun estimateTilesAtZoom(bbox: BoundingBox, zoom: Int): Long {
    val n = 1L shl zoom.coerceIn(0, 24)
    val lonWest = minOf(bbox.lonWest, bbox.lonEast)
    val lonEast = maxOf(bbox.lonWest, bbox.lonEast)
    val latNorth = maxOf(bbox.latNorth, bbox.latSouth)
    val latSouth = minOf(bbox.latNorth, bbox.latSouth)
    val x0 = floorToLong((lonWest + 180.0) / 360.0 * n)
    val x1 = ceilToLong((lonEast + 180.0) / 360.0 * n)
    // y grows southward, so the north edge is the low one.
    val y0 = floorToLong(mercatorFraction(latNorth) * n)
    val y1 = ceilToLong(mercatorFraction(latSouth) * n)
    val w = (x1 - x0).coerceAtLeast(0L)
    val h = (y1 - y0).coerceAtLeast(0L)
    return w * h
}

private fun floorToLong(v: Double): Long = kotlin.math.floor(v).toLong()
private fun ceilToLong(v: Double): Long = kotlin.math.ceil(v).toLong()

/** Fraction of the world (0 = north edge, 1 = south edge) for a latitude, in [0, 1]. */
private fun mercatorFraction(lat: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val rad = Math.toRadians(clamped)
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

/**
 * Total tiles for [bbox] over the inclusive zoom range [zoomMin]..[zoomMax].
 *
 * Stops summing once the total exceeds [MAX_TILES_PER_AREA], but still returns a value
 * *above* the cap: returning the cap itself made `estimateTiles(..) > cap` checks
 * impossible, so oversized requests were never narrowed.
 */
fun estimateTiles(bbox: BoundingBox, zoomMin: Int, zoomMax: Int): Long {
    var total = 0L
    for (z in zoomMin..zoomMax) {
        total += estimateTilesAtZoom(bbox, z)
        if (total > MAX_TILES_PER_AREA) return total
    }
    return total
}

/**
 * Zoom window to actually request: never above [providerMaxZoom], never below [floorZoom],
 * and narrowed from the deepest end until the tile count fits [maxTiles] so a wide area at
 * a deep zoom degrades gracefully instead of silently downloading nothing.
 */
fun clampZoomRange(
    providerMaxZoom: Int,
    requestedMin: Int,
    requestedMax: Int,
    bbox: BoundingBox,
    maxTiles: Long = MAX_TILES_PER_AREA,
    floorZoom: Int = 8,
): IntRange {
    val top = requestedMax.coerceIn(floorZoom, providerMaxZoom)
    val bottom = requestedMin.coerceIn(floorZoom, top)
    var min = bottom
    var max = top
    while (min < max && estimateTiles(bbox, min, max) > maxTiles) max--
    if (estimateTiles(bbox, min, max) > maxTiles) min = max
    // Deliberately not capped at the zoom currently on screen: an offline area is picked
    // zoomed out (to frame a big chunk) but used zoomed in, on foot. The tile budget
    // above is what keeps the request sane.
    return min..max
}

/** Human-readable estimate for the confirmation dialog. */
fun estimateLabel(tiles: Long): String = when {
    tiles > MAX_TILES_PER_AREA -> ">$MAX_TILES_PER_AREA tiles (will be reduced)"
    tiles >= 1000 -> "%.1f k tiles · ~%.0f MB".format(tiles / 1000.0, tiles * ESTIMATED_TILE_BYTES / 1048576.0)
    else -> "$tiles tiles · ~%.1f MB".format(tiles * ESTIMATED_TILE_BYTES / 1048576.0)
}

/** Convenience: a [GeoPoint] usable with osmdroid APIs from a drag corner. */
fun IGeoPoint.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
