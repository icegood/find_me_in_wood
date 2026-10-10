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
    @Test
    fun `OSM resolves to a different provider per theme`() {
        assertEquals(TileProvider.MAPNIK, TileProvider.forSource(TileSource.OSM, isDarkTheme = false))
        assertEquals(TileProvider.CARTO_DARK, TileProvider.forSource(TileSource.OSM, isDarkTheme = true))
    }

    @Test
    fun `satellite always resolves to USGS regardless of theme`() {
        assertEquals(TileProvider.USGS_SAT, TileProvider.forSource(TileSource.SATELLITE, isDarkTheme = false))
        assertEquals(TileProvider.USGS_SAT, TileProvider.forSource(TileSource.SATELLITE, isDarkTheme = true))
    }

    @Test
    fun `provider ids round-trip`() {
        TileProvider.entries.forEach { p ->
            assertSame(p, TileProvider.byId(p.id), "byId(${p.id})")
        }
    }

    @Test
    fun `a stored key never reaches a keyless provider`() {
        // The bug this guards: a single "OSM" key used to be appended to both Mapnik and
        // CartoDB, breaking both. USGS is public too, so none of the current providers
        // may carry a token.
        TileProvider.entries.forEach { p ->
            assertFalse(p.needsApiKey, "${p.id} is a public service")
            assertFalse(buildTileSource(p, "SECRET").imageFilenameEnding().contains("token"), p.id)
        }
        assertEquals("", buildTileSource(TileProvider.USGS_SAT, "SECRET").imageFilenameEnding())
    }

    @Test
    fun `providers that need no key stay key-free even when a key is stored`() {
        val noKey = TileProvider.entries.filter { !it.needsApiKey }
        assertTrue(noKey.isNotEmpty(), "some providers should need no key")
        noKey.forEach { p ->
            assertFalse(buildTileSource(p, "SECRET").imageFilenameEnding().contains("SECRET"), "${p.id} must ignore keys")
        }
    }

    @Test
    fun `blank key produces no token query`() {
        TileProvider.entries.forEach { p ->
            assertFalse(buildTileSource(p, "   ").imageFilenameEnding().contains("token"), "${p.id} with blank key")
        }
    }

    @Test
    fun `usgs tiles use arcgis z-y-x addressing`() {
        val index = org.osmdroid.util.MapTileIndex.getTileIndex(5, 17, 11)
        val url = buildTileSource(TileProvider.USGS_SAT, "").getTileURLString(index)
        assertTrue(url.endsWith("/MapServer/tile/5/11/17"), url)
        val osm = buildTileSource(TileProvider.MAPNIK, "").getTileURLString(index)
        assertTrue(osm.endsWith("/5/17/11.png"), osm)
    }

    @Test
    fun `tile source name matches the provider id so cache rows stay bound`() {
        // osmdroid stores tiles under the tile source name; offline regions look the
        // provider up by that same id, so the two must not drift apart.
        TileProvider.entries.forEach { p ->
            assertEquals(p.id, buildTileSource(p, "").name(), "${p.id} source name")
            assertEquals(p.id, tileSourceById(p.id).name(), "${p.id} lookup by id")
        }
    }

    @Test
    fun `generated tile urls never carry a stored key`() {
        TileProvider.entries.forEach { p ->
            val url = buildTileSource(p, "SECRET").getTileURLString(0L)
            assertFalse(url.contains("token"), "${p.id} url must not carry a key: $url")
        }
    }

    @Test
    fun `unknown provider id falls back to mapnik rather than throwing`() {
        assertEquals(TileProvider.MAPNIK.id, tileSourceById("SomeRemovedProvider").name())
        assertEquals(null, TileProvider.byId("nope"))
    }

    @Test
    fun `only providers that need a key get a settings field`() {
        assertTrue(TileProvider.keyed.all { it.needsApiKey })
        assertEquals(TileProvider.entries.filter { it.needsApiKey }, TileProvider.keyed)
    }
}
