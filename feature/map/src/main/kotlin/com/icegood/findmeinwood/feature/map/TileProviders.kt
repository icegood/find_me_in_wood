package com.icegood.findmeinwood.feature.map

import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * What the map shows: one online [provider], or ([offline]) only the tiles of that provider
 * already on the phone (saved areas and places viewed before).
 */
data class MapLayer(val provider: TileProvider, val offline: Boolean) {
    companion object {
        val DEFAULT = MapLayer(TileProvider.MAPNIK, offline = true)
    }
}

/**
 * One setting a provider takes (API key, access token…), sent as `?<param>=<value>`
 * on that provider's tile requests only.
 */
data class TileOption(val param: String, val label: String, val required: Boolean)

/**
 * A concrete tile provider with its own [options]. Values are stored per provider, so
 * a key for one service never reaches another.
 *
 * [id] is also the `provider` column value in osmdroid's cache.db, so offline
 * regions stay bound to the exact provider that downloaded them.
 */
enum class TileProvider(
    val id: String,
    val label: String,
    val satellite: Boolean,
    /** Where the imagery exists, when not worldwide. */
    val coverage: String?,
    val attribution: String,
    val options: List<TileOption>,
    /** Where to get the key or token, shown in the provider's options. */
    val signUp: String?,
    /**
     * The deepest zoom an offline area gets (trail detail): every area is saved from the
     * overview down to it, and deeper zooms enlarge it while offline.
     */
    val detailZoom: Int = 17,
    /** Most tiles one offline area may fetch from this service. */
    val downloadTiles: Long = MAX_TILES_PER_AREA,
) {
    MAPNIK(
        "Mapnik", "OpenStreetMap", satellite = false, coverage = null,
        attribution = "© OpenStreetMap contributors", options = emptyList(), signUp = null,
        // The OSM tile policy allows modest look-ahead, not bulk download: a forest of a few
        // km across with its paths (zoom 16), nothing more.
        detailZoom = 16, downloadTiles = 300,
    ),
    CARTO_DARK(
        "CartoDB-DarkMatter", "CARTO dark", satellite = false, coverage = null,
        attribution = "© OpenStreetMap contributors © CARTO",
        options = listOf(TileOption("key", "API key", required = true)),
        signUp = "carto.com/basemaps/apikey",
    ),
    // The National Map basemaps are public; no token is accepted or required.
    USGS_SAT(
        "USGS_SAT", "USGS", satellite = true, coverage = "USA only",
        attribution = "USGS The National Map", options = emptyList(), signUp = null,
    ),
    ESRI_SAT(
        "Esri-WorldImagery", "Esri World Imagery", satellite = true, coverage = null,
        attribution = "Esri, Maxar, Earthstar Geographics",
        options = listOf(TileOption("token", "ArcGIS token", required = false)),
        signUp = "developers.arcgis.com",
    ),
    MAPTILER_SAT(
        "MapTiler-Satellite", "MapTiler", satellite = true, coverage = null,
        attribution = "© MapTiler © OpenStreetMap contributors",
        options = listOf(TileOption("key", "API key", required = true)),
        signUp = "cloud.maptiler.com",
    ),
    MAPBOX_SAT(
        "Mapbox-Satellite", "Mapbox", satellite = true, coverage = null,
        attribution = "© Mapbox © Maxar",
        options = listOf(TileOption("access_token", "Access token", required = true)),
        signUp = "account.mapbox.com",
    ),
    ;

    /** The required options [values] leaves blank: the provider cannot serve tiles yet. */
    fun missing(values: Map<String, String>): List<TileOption> =
        options.filter { it.required && values[it.param].isNullOrBlank() }

    companion object {
        fun byId(id: String): TileProvider? = entries.firstOrNull { it.id == id }
    }
}

private val MAPNIK_URL = arrayOf("https://tile.openstreetmap.org/")
private val CARTO_DARK_URL = arrayOf("https://basemaps.cartocdn.com/rastertiles/dark_all/")
private val USGS_SAT_URL = arrayOf(
    "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/",
)
private val ESRI_SAT_URL = arrayOf(
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/",
)
private val MAPTILER_SAT_URL = arrayOf("https://api.maptiler.com/maps/satellite/256/")
private val MAPBOX_SAT_URL = arrayOf("https://api.mapbox.com/v4/mapbox.satellite/")

/** `?a=1&b=2` from the provider's own options; blank values and foreign keys are left out. */
internal fun optionQuery(provider: TileProvider, values: Map<String, String>): String =
    provider.options
        .mapNotNull { o -> values[o.param]?.trim()?.takeIf { it.isNotEmpty() }?.let { o.param to it } }
        .joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        .let { if (it.isEmpty()) "" else "?$it" }

/**
 * Build the osmdroid source for [provider] with its own option [values] appended to every
 * tile URL. The values are part of the URL only, never of the source name, so changing a
 * key keeps the cached tiles.
 */
internal fun buildTileSource(provider: TileProvider, values: Map<String, String> = emptyMap()): XYTileSource {
    val q = optionQuery(provider, values)
    return when (provider) {
        TileProvider.MAPNIK -> XYTileSource(provider.id, 0, 19, 256, ".png$q", MAPNIK_URL)
        TileProvider.CARTO_DARK -> XYTileSource(provider.id, 0, 19, 256, ".png$q", CARTO_DARK_URL)
        TileProvider.USGS_SAT -> ArcGisTileSource(provider.id, q, USGS_SAT_URL)
        TileProvider.ESRI_SAT -> ArcGisTileSource(provider.id, q, ESRI_SAT_URL)
        TileProvider.MAPTILER_SAT -> XYTileSource(provider.id, 0, 19, 256, ".jpg$q", MAPTILER_SAT_URL)
        TileProvider.MAPBOX_SAT -> XYTileSource(provider.id, 0, 19, 256, ".jpg90$q", MAPBOX_SAT_URL)
    }
}

/**
 * ArcGIS MapServer tiles are addressed `tile/{z}/{y}/{x}` with no file extension, unlike
 * the slippy-map `{z}/{x}/{y}.png` that [XYTileSource] builds (same as osmdroid's own
 * `TileSourceFactory.USGS_SAT`).
 */
private class ArcGisTileSource(name: String, suffix: String, urls: Array<String>) :
    XYTileSource(name, 0, 19, 256, suffix, urls) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) + imageFilenameEnding()
}

/** Rebuild a source from its cached provider id so offline cleanup matches the cache rows. */
internal fun tileSourceById(id: String): XYTileSource =
    buildTileSource(TileProvider.byId(id) ?: TileProvider.MAPNIK)
