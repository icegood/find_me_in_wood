package com.icegood.findmeinwood.feature.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TileProviderTest {
    private val index = org.osmdroid.util.MapTileIndex.getTileIndex(5, 17, 11)

    @Test
    fun `provider ids round-trip`() {
        TileProvider.entries.forEach { p ->
            assertSame(p, TileProvider.byId(p.id), "byId(${p.id})")
        }
    }

    @Test
    fun `every keyed service has an option named as that service expects`() {
        val params = TileProvider.entries.associateWith { p -> p.options.map { it.param } }
        assertEquals(listOf("key"), params[TileProvider.CARTO_DARK])
        assertEquals(listOf("token"), params[TileProvider.ESRI_SAT])
        assertEquals(listOf("key"), params[TileProvider.MAPTILER_SAT])
        assertEquals(listOf("access_token"), params[TileProvider.MAPBOX_SAT])
        assertEquals(emptyList(), params[TileProvider.MAPNIK])
        assertEquals(emptyList(), params[TileProvider.USGS_SAT])
        // Whoever needs something says where to get it.
        TileProvider.entries.filter { it.options.isNotEmpty() }.forEach { assertTrue(it.signUp != null, it.id) }
    }

    @Test
    fun `required options decide whether a provider can serve tiles`() {
        assertEquals(listOf("access_token"), TileProvider.MAPBOX_SAT.missing(emptyMap()).map { it.param })
        assertEquals(emptyList(), TileProvider.MAPBOX_SAT.missing(mapOf("access_token" to "pk.x")))
        assertEquals(1, TileProvider.CARTO_DARK.missing(mapOf("key" to "  ")).size)
        // Esri's token is optional: the public service works without it.
        assertEquals(emptyList(), TileProvider.ESRI_SAT.missing(emptyMap()))
        assertEquals(emptyList(), TileProvider.MAPNIK.missing(emptyMap()))
    }

    @Test
    fun `each provider sends its own option and nothing else`() {
        // One map holding every service's parameter: each provider picks only its own.
        val all = mapOf("key" to "K", "token" to "T", "access_token" to "A")
        fun url(p: TileProvider) = buildTileSource(p, all).getTileURLString(index)
        assertTrue(url(TileProvider.CARTO_DARK).endsWith("/rastertiles/dark_all/5/17/11.png?key=K"), url(TileProvider.CARTO_DARK))
        assertTrue(url(TileProvider.MAPTILER_SAT).endsWith("/maps/satellite/256/5/17/11.jpg?key=K"), url(TileProvider.MAPTILER_SAT))
        assertTrue(url(TileProvider.MAPBOX_SAT).endsWith("/mapbox.satellite/5/17/11.jpg90?access_token=A"), url(TileProvider.MAPBOX_SAT))
        assertTrue(url(TileProvider.ESRI_SAT).endsWith("/World_Imagery/MapServer/tile/5/11/17?token=T"), url(TileProvider.ESRI_SAT))
        assertTrue(url(TileProvider.MAPNIK).endsWith("tile.openstreetmap.org/5/17/11.png"), url(TileProvider.MAPNIK))
        assertTrue(url(TileProvider.USGS_SAT).endsWith("/MapServer/tile/5/11/17"), url(TileProvider.USGS_SAT))
    }

    @Test
    fun `blank values send nothing and values are url-encoded`() {
        TileProvider.entries.forEach { p ->
            val values = p.options.associate { it.param to "   " }
            assertFalse(buildTileSource(p, values).getTileURLString(index).contains("?"), p.id)
        }
        assertEquals("?access_token=a%2Bb%26c", optionQuery(TileProvider.MAPBOX_SAT, mapOf("access_token" to " a+b&c ")))
    }

    @Test
    fun `tile source name matches the provider id so cache rows stay bound`() {
        // osmdroid stores tiles under the tile source name; offline regions look the
        // provider up by that same id, so the two must not drift apart, keys or not.
        TileProvider.entries.forEach { p ->
            assertEquals(p.id, buildTileSource(p, mapOf("key" to "K")).name(), "${p.id} source name")
            assertEquals(p.id, tileSourceById(p.id).name(), "${p.id} lookup by id")
        }
    }

    @Test
    fun `unknown provider id falls back to mapnik rather than throwing`() {
        assertEquals(TileProvider.MAPNIK.id, tileSourceById("SomeRemovedProvider").name())
        assertEquals(null, TileProvider.byId("nope"))
    }

    @Test
    fun `chooser texts say coverage and what is missing`() {
        assertEquals("satellite · USA only", providerHint(TileProvider.USGS_SAT, emptyMap()))
        assertEquals("satellite · needs Access token", providerHint(TileProvider.MAPBOX_SAT, emptyMap()))
        assertEquals("satellite · ArcGIS token set", providerHint(TileProvider.ESRI_SAT, mapOf("token" to "T")))
        assertEquals("streets", providerHint(TileProvider.MAPNIK, emptyMap()))
        assertEquals("Offline (OpenStreetMap)", layerLabel(MapLayer.DEFAULT))
        assertEquals("Esri World Imagery", layerLabel(MapLayer(TileProvider.ESRI_SAT, offline = false)))
    }
}
