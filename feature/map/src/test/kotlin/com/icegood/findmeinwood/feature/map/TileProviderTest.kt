package com.icegood.findmeinwood.feature.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TileProviderTest {
    private val ctx: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
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
        assertEquals(emptyList(), params[TileProvider.OPENFREEMAP])
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
        assertEquals(emptyList(), TileProvider.OPENFREEMAP.missing(emptyMap()))
    }

    @Test
    fun `each provider sends its own option and nothing else`() {
        // One map holding every service's parameter: each provider picks only its own.
        val all = mapOf("key" to "K", "token" to "T", "access_token" to "A")
        fun url(p: TileProvider) = tileUrl(p, all)
        assertEquals("https://basemaps.cartocdn.com/rastertiles/dark_all/{z}/{x}/{y}.png?key=K", url(TileProvider.CARTO_DARK))
        assertEquals("https://api.maptiler.com/maps/satellite/256/{z}/{x}/{y}.jpg?key=K", url(TileProvider.MAPTILER_SAT))
        assertEquals("https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.jpg90?access_token=A", url(TileProvider.MAPBOX_SAT))
        assertTrue(url(TileProvider.ESRI_SAT)!!.endsWith("/World_Imagery/MapServer/tile/{z}/{y}/{x}?token=T"))
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", url(TileProvider.MAPNIK))
        assertTrue(url(TileProvider.USGS_SAT)!!.endsWith("/MapServer/tile/{z}/{y}/{x}"))
        // Vector data comes through OpenFreeMap's own style, never a tile template.
        assertNull(url(TileProvider.OPENFREEMAP))
    }

    @Test
    fun `blank values send nothing and values are url-encoded`() {
        TileProvider.entries.forEach { p ->
            val values = p.options.associate { it.param to "   " }
            assertFalse(tileUrl(p, values).orEmpty().contains("?"), p.id)
        }
        assertEquals("?access_token=a%2Bb%26c", optionQuery(TileProvider.MAPBOX_SAT, mapOf("access_token" to " a+b&c ")))
    }

    @Test
    fun `raster style enlarges saved tiles offline and fetches deeper ones online`() {
        fun source(offline: Boolean) = org.json.JSONObject(
            rasterStyleJson(TileProvider.MAPNIK, emptyMap(), offline, isDark = false),
        ).getJSONObject("sources").getJSONObject("tiles")
        assertEquals(16, source(offline = true).getInt("maxzoom"))
        assertEquals(19, source(offline = false).getInt("maxzoom"))
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", source(true).getJSONArray("tiles").getString(0))
        assertEquals(256, source(true).getInt("tileSize"))
    }

    @Test
    fun `openfreemap colours follow the theme`() {
        assertTrue(vectorStyleUrl(isDark = true).endsWith("/dark"))
        assertTrue(vectorStyleUrl(isDark = false).endsWith("/liberty"))
    }

    @Test
    fun `chooser texts say coverage and what is missing`() {
        assertEquals("satellite · USA only", providerHint(ctx, TileProvider.USGS_SAT, emptyMap()))
        assertEquals("satellite · needs Access token", providerHint(ctx, TileProvider.MAPBOX_SAT, emptyMap()))
        assertEquals("satellite · ArcGIS token set", providerHint(ctx, TileProvider.ESRI_SAT, mapOf("token" to "T")))
        assertEquals("streets", providerHint(ctx, TileProvider.OPENFREEMAP, emptyMap()))
        assertEquals("Offline (OpenFreeMap)", layerLabel(ctx, MapLayer.DEFAULT))
        assertEquals("Esri World Imagery", layerLabel(ctx, MapLayer(TileProvider.ESRI_SAT, offline = false)))
    }

    @Test
    fun `scale bar picks a round distance that fits`() {
        assertEquals(500 to 100f, scaleBar(metersPerPx = 5.0, maxPx = 100f))
        assertEquals(200, scaleBar(metersPerPx = 3.0, maxPx = 100f)!!.first)
        assertNull(scaleBar(metersPerPx = 0.0, maxPx = 100f))
        assertNull(scaleBar(metersPerPx = 0.001, maxPx = 100f))
    }
}
