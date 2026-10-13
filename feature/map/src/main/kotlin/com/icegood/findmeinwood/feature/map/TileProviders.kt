package com.icegood.findmeinwood.feature.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the map shows: one online [provider], or ([offline]) only what of that provider is
 * already on the phone (saved areas and places viewed before).
 */
data class MapLayer(val provider: TileProvider, val offline: Boolean) {
    companion object {
        val DEFAULT = MapLayer(TileProvider.OPENFREEMAP, offline = true)
    }
}

/**
 * One setting a provider takes (API key, access token…), sent as `?<param>=<value>`
 * on that provider's tile requests only.
 */
data class TileOption(val param: String, val label: String, val required: Boolean)

/**
 * A map provider with its own [options]. Values are stored per provider, so a key for one
 * service never reaches another. [id] names the provider in saved areas.
 *
 * Vector providers are drawn on the phone from map data (sharp at any zoom, tiny downloads);
 * raster providers serve finished pictures, [tileSize] px wide.
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
     * Deepest zoom of this provider's own tiles an offline area gets (trail detail): areas are
     * saved from the overview down to it, and deeper zooms enlarge it while offline.
     */
    val detailZoom: Int = 17,
    /** Most tiles one offline area may fetch from this service. */
    val downloadTiles: Long = MAX_TILES_PER_AREA,
    val vector: Boolean = false,
    val tileSize: Int = 256,
) {
    // Free, no key, no request limits; vector data down to zoom 14, drawn sharp beyond it.
    OPENFREEMAP(
        "OpenFreeMap", "OpenFreeMap", satellite = false, coverage = null,
        attribution = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
        options = emptyList(), signUp = null,
        detailZoom = 14, vector = true, tileSize = 512,
    ),
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

    /** Map zoom at which the map shows tiles of [tileZoom] (a 256 px tile is half a 512 px one). */
    fun mapZoom(tileZoom: Int): Int = if (tileSize == 256) tileZoom - 1 else tileZoom

    companion object {
        fun byId(id: String): TileProvider? = entries.firstOrNull { it.id == id }
    }
}

/** Rough bytes per tile, only for the estimate shown before a download. */
internal fun TileProvider.tileBytes(): Long = if (vector) 40_000L else ESTIMATED_TILE_BYTES

/** `?a=1&b=2` from the provider's own options; blank values and foreign keys are left out. */
internal fun optionQuery(provider: TileProvider, values: Map<String, String>): String =
    provider.options
        .mapNotNull { o -> values[o.param]?.trim()?.takeIf { it.isNotEmpty() }?.let { o.param to it } }
        .joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        .let { if (it.isEmpty()) "" else "?$it" }

/** Tile URL template of a raster [provider] with its own option [values]; null for vector ones. */
internal fun tileUrl(provider: TileProvider, values: Map<String, String> = emptyMap()): String? {
    val q = optionQuery(provider, values)
    return when (provider) {
        TileProvider.OPENFREEMAP -> null
        TileProvider.MAPNIK -> "https://tile.openstreetmap.org/{z}/{x}/{y}.png$q"
        TileProvider.CARTO_DARK -> "https://basemaps.cartocdn.com/rastertiles/dark_all/{z}/{x}/{y}.png$q"
        // ArcGIS MapServer tiles are addressed tile/{z}/{y}/{x}, row before column.
        TileProvider.USGS_SAT ->
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}$q"
        TileProvider.ESRI_SAT ->
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}$q"
        TileProvider.MAPTILER_SAT -> "https://api.maptiler.com/maps/satellite/256/{z}/{x}/{y}.jpg$q"
        TileProvider.MAPBOX_SAT -> "https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.jpg90$q"
    }
}

/** OpenFreeMap's own styles: the same map data, coloured for the theme. */
internal fun vectorStyleUrl(isDark: Boolean): String =
    "https://tiles.openfreemap.org/styles/" + if (isDark) "dark" else "liberty"

/** Deepest zoom a raster provider serves online. */
private const val RASTER_MAX_ZOOM = 19

/**
 * A MapLibre style showing one raster [provider]. [offline] stops at the provider's detail
 * zoom, so deeper zooms enlarge the saved tiles instead of asking for ones never saved.
 */
internal fun rasterStyleJson(
    provider: TileProvider,
    values: Map<String, String>,
    offline: Boolean,
    isDark: Boolean,
): String {
    val url = requireNotNull(tileUrl(provider, values)) { "${provider.id} is not a raster provider" }
    val source = JSONObject()
        .put("type", "raster")
        .put("tiles", JSONArray().put(url))
        .put("tileSize", provider.tileSize)
        .put("maxzoom", if (offline) provider.detailZoom else RASTER_MAX_ZOOM)
    return JSONObject()
        .put("version", 8)
        .put("sources", JSONObject().put("tiles", source))
        .put(
            "layers",
            JSONArray()
                .put(
                    JSONObject().put("id", "background").put("type", "background")
                        .put("paint", JSONObject().put("background-color", if (isDark) "#1a1a1a" else "#f2efe9")),
                )
                .put(JSONObject().put("id", "tiles").put("type", "raster").put("source", "tiles")),
        )
        .toString()
}
