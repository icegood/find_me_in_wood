package com.icegood.findmeinwood.feature.map

import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OfflineSelectionTest {
    private fun p(lat: Double, lon: Double) = GeoPoint(lat, lon)

    @Test
    fun `drag bounds are normalised regardless of drag direction`() {
        val a = dragToBoundingBox(p(50.0, 30.0), p(51.0, 31.0))
        val b = dragToBoundingBox(p(51.0, 31.0), p(50.0, 30.0))
        assertNotNull(a)
        assertNotNull(b)
        for (bb in listOf(a, b)) {
            assertEquals(51.0, bb.latNorth, 1e-9)
            assertEquals(50.0, bb.latSouth, 1e-9)
            assertEquals(31.0, bb.lonEast, 1e-9)
            assertEquals(30.0, bb.lonWest, 1e-9)
        }
    }

    @Test
    fun `a tap is too small to be an area`() {
        assertNull(dragToBoundingBox(p(50.0, 30.0), p(50.00001, 30.00001)))
    }

    @Test
    fun `a realistic drag is accepted`() {
        assertNotNull(dragToBoundingBox(p(50.0, 30.0), p(50.01, 30.02)))
    }

    @Test
    fun `tile count grows about fourfold per zoom level`() {
        // A large equatorial box, where a degree of longitude and latitude cover a similar
        // number of tiles, so the ~4x-per-level law holds once rounding is negligible.
        val bb = BoundingBox(15.0, 15.0, -15.0, -15.0)
        val z8 = estimateTilesAtZoom(bb, 8)
        val z9 = estimateTilesAtZoom(bb, 9)
        val z10 = estimateTilesAtZoom(bb, 10)
        assertTrue(z8 > 100, "expected many tiles at z8, got $z8")
        listOf(z9 to z8, z10 to z9).forEach { (b, a) ->
            val ratio = b.toDouble() / a
            assertTrue(ratio in 3.9..4.1, "expected ~4x, got $b/$a = $ratio")
        }
    }

    @Test
    fun `counting always increases as zoom deepens`() {
        val bb = BoundingBox(51.0, 31.0, 50.0, 30.0)
        var prev = 0L
        for (z in 8..18) {
            val n = estimateTilesAtZoom(bb, z)
            assertTrue(n >= prev, "zoom $z gave $n after $prev")
            prev = n
        }
    }

    @Test
    fun `a drag smaller than one tile still counts the covering tile`() {
        val tiny = BoundingBox(50.001, 30.001, 50.0, 30.0)
        assertTrue(estimateTilesAtZoom(tiny, 10) >= 1, "a sub-tile drag still needs its covering tile")
    }

    @Test
    fun `tile count for a range is the sum of its levels`() {
        val bb = BoundingBox(50.02, 30.02, 50.0, 30.0)
        val expected = estimateTilesAtZoom(bb, 12) + estimateTilesAtZoom(bb, 13) + estimateTilesAtZoom(bb, 14)
        assertEquals(expected, estimateTiles(bb, 12, 14))
    }

    @Test
    fun `total is capped so a huge request cannot be silently accepted`() {
        val wide = BoundingBox(85.0, 179.9, -85.0, -179.9)
        assertTrue(estimateTiles(wide, 0, 19) > MAX_TILES_PER_AREA)
    }

    @Test
    fun `every area gets the overview down to the provider's trail detail`() {
        assertEquals(8..16, offlineZooms(TileProvider.MAPNIK))
        assertEquals(8..17, offlineZooms(TileProvider.ESRI_SAT))
    }

    @Test
    fun `openstreetmap takes a forest of a few km but not a region`() {
        // ~4 x 4 km at 48 N with paths: a modest look-ahead, allowed.
        val forest = BoundingBox(48.32, 24.43, 48.285, 24.38)
        val tiles = assertNotNull(offlineTiles(forest, TileProvider.MAPNIK))
        assertTrue(tiles <= 300, "got $tiles")
        // ~15 x 15 km: bulk, refused rather than saved without detail.
        val region = BoundingBox(48.37, 24.5, 48.23, 24.3)
        assertNull(offlineTiles(region, TileProvider.MAPNIK))
        // Other providers keep the large budget.
        assertNotNull(offlineTiles(region, TileProvider.ESRI_SAT))
    }

    @Test
    fun `estimate label gives tiles and size`() {
        assertTrue(estimateLabel(500L).contains("500 tiles"), estimateLabel(500L))
        assertTrue(estimateLabel(2500L).contains("2.5 k tiles"), estimateLabel(2500L))
    }

    @Test
    fun `viewed tiles are a queue that never touches saved areas`() {
        val q = listOf(
            CachedTile("Mapnik", 1, 100), // oldest, saved area
            CachedTile("Mapnik", 2, 100), // oldest viewed
            CachedTile("USGS_SAT", 1, 100), // same key, other provider: viewed
            CachedTile("Mapnik", 3, 100),
        )
        val saved = mapOf("Mapnik" to setOf(1L))
        assertEquals(emptyList(), viewedToEvict(q, saved, maxBytes = 300))
        assertEquals(listOf(q[1]), viewedToEvict(q, saved, maxBytes = 250))
        assertEquals(listOf(q[1], q[2]), viewedToEvict(q, saved, maxBytes = 100))
        assertEquals(listOf(q[1], q[2], q[3]), viewedToEvict(q, saved, maxBytes = 0))
    }

    @Test
    fun `span reports the drag size`() {
        val (latSpan, lonSpan) = dragSpanDegrees(p(50.0, 30.0), p(50.5, 30.25))
        assertEquals(0.5, latSpan, 1e-9)
        assertEquals(0.25, lonSpan, 1e-9)
    }

    @Test
    fun `region names containing the separator still roundtrip`() {
        val r = OfflineRegion(
            id = "id1", name = "camp | lake", latNorth = 1.0, lonEast = 2.0, latSouth = 0.5,
            lonWest = 1.5, zoomMin = 8, zoomMax = 12, tileCount = 3, sizeBytes = 4L,
            createdAt = 5L, sourceName = "Mapnik",
        )
        val back = OfflineRegions.decode(OfflineRegions.encode(r))
        assertEquals("Mapnik", back.sourceName)
        assertEquals("camp / lake", back.name)
    }
}
