package com.icegood.findmeinwood.feature.map

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * The map layer the user picks in [TileSource] (OFFLINE / OSM / SATELLITE).
 *
 * OSM is a *family*: the concrete provider depends on the active theme, so it is
 * resolved through [TileProvider.forSource] before any api key is looked up.
 */
enum class TileSource { OFFLINE, OSM, SATELLITE }

/**
 * A concrete tile provider. Api keys are stored per provider (not per [TileSource]),
 * because OSM resolves to [MAPNIK] in light theme and [CARTO_DARK] in dark theme,
 * and those are different services with different credentials.
 *
 * [id] is also the `provider` column value in osmdroid's cache.db, so offline
 * regions stay bound to the exact provider that downloaded them.
 */
enum class TileProvider(
    val id: String,
    val label: String,
    val needsApiKey: Boolean,
    val attribution: String,
) {
    MAPNIK("Mapnik", "OpenStreetMap (street)", needsApiKey = false, attribution = "© OpenStreetMap contributors"),
    CARTO_DARK("CartoDB-DarkMatter", "CARTO dark (street)", needsApiKey = false, attribution = "© OpenStreetMap contributors © CARTO"),
    USGS_SAT("USGS_SAT", "USGS satellite imagery", needsApiKey = true, attribution = "Esri, Maxar, Earthstar Geographics");

    companion object {
        /** Every provider that can appear in Settings (OFFLINE has no online key). */
        val online: List<TileProvider> get() = entries.toList()

        /** Resolve the concrete provider for a [source] under [isDarkTheme]. */
        fun forSource(source: TileSource, isDarkTheme: Boolean): TileProvider = when (source) {
            TileSource.SATELLITE -> USGS_SAT
            TileSource.OSM -> if (isDarkTheme) CARTO_DARK else MAPNIK
            // OFFLINE is never fetched; the caller substitutes the last online source.
            TileSource.OFFLINE -> MAPNIK
        }

        fun byId(id: String): TileProvider? = entries.firstOrNull { it.id == id }
    }
}

internal val CARTO_DARK_URLS = arrayOf(
    "https://a.basemaps.cartocdn.com/dark_all/",
    "https://b.basemaps.cartocdn.com/dark_all/",
    "https://c.basemaps.cartocdn.com/dark_all/",
    "https://d.basemaps.cartocdn.com/dark_all/",
)
private val USGS_SAT_URL = arrayOf(
    "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/",
)
private val MAPNIK_URL = arrayOf("https://tile.openstreetmap.org/")

/**
 * Build the osmdroid source for [provider], appending its own api key as `?token=<key>`
 * when one is configured. Providers that need no key never receive one, so a stray key
 * for one provider can never break the others.
 */
internal fun buildTileSource(provider: TileProvider, apiKey: String): XYTileSource {
    val useKey = provider.needsApiKey && apiKey.isNotBlank()
    val ext = if (useKey) ".png?token=$apiKey" else ".png"
    val urls = when (provider) {
        TileProvider.MAPNIK -> MAPNIK_URL
        TileProvider.CARTO_DARK -> CARTO_DARK_URLS
        TileProvider.USGS_SAT -> USGS_SAT_URL
    }
    return XYTileSource(provider.id, 0, 19, 256, ext, urls)
}

/** Rebuild a source from its cached provider id so offline cleanup matches the cache rows. */
internal fun tileSourceById(id: String): XYTileSource =
    buildTileSource(TileProvider.byId(id) ?: TileProvider.MAPNIK, "")
