package com.icegood.findmeinwood.feature.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** A point on the map. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** A rectangle on the map. */
data class GeoBox(val latNorth: Double, val lonEast: Double, val latSouth: Double, val lonWest: Double) {
    val centerLatitude: Double get() = (latNorth + latSouth) / 2
    val centerLongitude: Double get() = (lonEast + lonWest) / 2
}

/** Hard ceiling on a single offline area, so a stray drag cannot request millions of tiles. */
const val MAX_TILES_PER_AREA = 20_000L

/** Rough bytes per cached tile, used only for the pre-download size estimate. */
const val ESTIMATED_TILE_BYTES = 18_000L

/**
 * Geographic bounds spanned by a drag from [a] to [b], normalised so the result is
 * well-formed regardless of the drag direction. Returns null when the drag covers
 * less than a couple of metres, which is almost certainly an accidental tap.
 */
fun dragToGeoBox(a: GeoPoint, b: GeoPoint, minSpanDegrees: Double = 0.0005): GeoBox? {
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
    return GeoBox(latNorth, lonEast, latSouth, lonWest)
}

/** Rough on-screen size of the drag in degrees, for the "too small" hint. */
fun dragSpanDegrees(a: GeoPoint, b: GeoPoint): Pair<Double, Double> =
    (maxOf(a.latitude, b.latitude) - minOf(a.latitude, b.latitude)) to
        (maxOf(a.longitude, b.longitude) - minOf(a.longitude, b.longitude))

/**
 * Number of slippy-map tiles covering [bbox] at [zoom], computed rather than listed so a
 * wide area at a deep zoom costs nothing to count.
 *
 * Tiles that merely *intersect* the box are counted (floor on the low edge, ceil on the
 * high edge): a drag smaller than one tile still needs the single tile that covers it.
 */
fun estimateTilesAtZoom(bbox: GeoBox, zoom: Int): Long {
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
fun estimateTiles(bbox: GeoBox, zoomMin: Int, zoomMax: Int): Long {
    var total = 0L
    for (z in zoomMin..zoomMax) {
        total += estimateTilesAtZoom(bbox, z)
        if (total > MAX_TILES_PER_AREA) return total
    }
    return total
}

/** Lowest zoom an offline area gets: the overview around it costs a handful of tiles. */
const val OFFLINE_MIN_ZOOM = 8

/** Zooms every offline area of [provider] gets: overview down to its trail detail. */
fun offlineZooms(provider: TileProvider): IntRange = OFFLINE_MIN_ZOOM..provider.detailZoom

/** Tiles [bbox] needs from [provider], or null when that is more than the provider allows. */
fun offlineTiles(bbox: GeoBox, provider: TileProvider): Long? {
    val z = offlineZooms(provider)
    return estimateTiles(bbox, z.first, z.last).takeIf { it <= provider.downloadTiles }
}

/** Human-readable estimate for the confirmation dialog. */
fun estimateLabel(ctx: android.content.Context, tiles: Long, tileBytes: Long = ESTIMATED_TILE_BYTES): String = when {
    tiles >= 1000 -> ctx.getString(R.string.estimate_k_tiles, "%.1f".format(tiles / 1000.0), "%.0f".format(tiles * tileBytes / 1048576.0))
    else -> ctx.getString(R.string.estimate_tiles, tiles.toString(), "%.1f".format(tiles * tileBytes / 1048576.0))
}

