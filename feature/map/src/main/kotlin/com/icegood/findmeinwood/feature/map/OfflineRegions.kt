package com.icegood.findmeinwood.feature.map

import android.content.Context
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.storage.FileSource
import java.io.File
import java.util.UUID
import org.maplibre.android.offline.OfflineRegion as MlRegion

/** Room for map data viewed outside saved areas: reused offline, oldest dropped first. */
const val VIEWED_CACHE_MAX_BYTES = 512L shl 20 // 512 MB

/** One saved offline area as the user sees it (one or more MapLibre regions). */
data class OfflineRegion(
    val group: String,
    val name: String,
    val providerId: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val complete: Boolean,
)

/** What a saved area records about itself, kept as each MapLibre region's metadata. */
internal data class AreaInfo(val group: String, val name: String, val providerId: String, val createdAt: Long) {
    fun encode(): ByteArray = JSONObject()
        .put("group", group).put("name", name).put("provider", providerId).put("created", createdAt)
        .toString().toByteArray()

    companion object {
        fun decode(bytes: ByteArray): AreaInfo? = runCatching {
            val o = JSONObject(String(bytes))
            AreaInfo(o.getString("group"), o.getString("name"), o.getString("provider"), o.getLong("created"))
        }.getOrNull()
    }
}

/** Progress of a running offline-area download. */
data class DownloadUi(
    val title: String,
    val done: Long = 0,
    val total: Long = 0,
    val errors: Int = 0,
    val finished: Boolean = false,
)

/** Saved offline areas, kept by MapLibre: saved areas are never dropped to make room. */
object OfflineRegions {
    private fun manager(ctx: Context): OfflineManager {
        initMapLibre(ctx)
        return OfflineManager.getInstance(ctx.applicationContext)
    }

    /** Viewed map data forms a bounded queue next to the saved areas. */
    fun configure(ctx: Context) {
        manager(ctx).setMaximumAmbientCacheSize(VIEWED_CACHE_MAX_BYTES, null)
    }

    /**
     * The styles an area of [provider] is saved for: both OpenFreeMap themes (they share the
     * map data, so the second costs only its colours), or the provider's raster style file.
     */
    private fun styleUrls(ctx: Context, provider: TileProvider, values: Map<String, String>): List<String> =
        if (provider.vector) {
            listOf(vectorStyleUrl(isDark = false), vectorStyleUrl(isDark = true))
        } else {
            val dir = File(ctx.filesDir, "map_styles").apply { mkdirs() }
            val f = File(dir, "${provider.id}.json")
            f.writeText(rasterStyleJson(provider, values, offline = false, isDark = false))
            listOf("file://${f.absolutePath}")
        }

    /** A started download; [cancel] stops it and drops what it saved. */
    class Download internal constructor(private val regions: MutableList<MlRegion>) {
        @Volatile internal var cancelled = false
        fun cancel() {
            cancelled = true
            regions.forEach { it.setDownloadState(MlRegion.STATE_INACTIVE); it.delete(noDelete) }
        }
    }

    /**
     * Saves [area] of [provider] from the overview down to its trail detail. Progress and the
     * end come on the main thread.
     */
    fun download(
        ctx: Context,
        area: GeoBox,
        name: String,
        provider: TileProvider,
        values: Map<String, String>,
        onProgress: (DownloadUi) -> Unit,
        onDone: (DownloadUi) -> Unit,
    ): Download {
        val info = AreaInfo(UUID.randomUUID().toString(), name, provider.id, System.currentTimeMillis())
        val bounds = LatLngBounds.from(area.latNorth, area.lonEast, area.latSouth, area.lonWest)
        val zooms = offlineZooms(provider)
        val urls = styleUrls(ctx, provider, values)
        val regions = mutableListOf<MlRegion>()
        val download = Download(regions)
        val statuses = HashMap<Long, OfflineRegionStatus>()
        var errors = 0
        var finished = false
        val title = "Downloading \"$name\""
        onProgress(DownloadUi(title))

        fun report() {
            if (finished || download.cancelled) return
            val done = statuses.values.sumOf { it.completedResourceCount }
            val total = statuses.values.sumOf { it.requiredResourceCount }
            if (statuses.size == urls.size && statuses.values.all { it.isComplete }) {
                finished = true
                onDone(DownloadUi("Saved \"$name\" for offline use", done, total, errors, finished = true))
            } else {
                onProgress(DownloadUi(title, done, total, errors))
            }
        }

        urls.forEach { url ->
            val definition = OfflineTilePyramidRegionDefinition(
                url, bounds,
                provider.mapZoom(zooms.first).coerceAtLeast(0).toDouble(),
                provider.mapZoom(zooms.last).toDouble(),
                ctx.resources.displayMetrics.density,
            )
            manager(ctx).createOfflineRegion(
                definition, info.encode(),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: MlRegion) {
                        val region = offlineRegion
                        regions += region
                        if (download.cancelled) { region.delete(noDelete); return }
                        region.setObserver(object : MlRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                statuses[region.id] = status
                                if (status.isComplete) region.setDownloadState(MlRegion.STATE_INACTIVE)
                                report()
                            }
                            // MapLibre keeps retrying by itself; count it so the user sees trouble.
                            override fun onError(error: OfflineRegionError) {
                                errors++
                                report()
                            }
                            override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
                        })
                        region.setDownloadState(MlRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        if (finished) return
                        finished = true
                        onDone(DownloadUi("Download error: $error", errors = errors + 1, finished = true))
                    }
                },
            )
        }
        return download
    }

    /** Every saved area, with its size, on the main thread. */
    fun load(ctx: Context, onResult: (List<OfflineRegion>) -> Unit) {
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<MlRegion>?) {
                val all = offlineRegions.orEmpty().toList()
                if (all.isEmpty()) { onResult(emptyList()); return }
                val statuses = HashMap<Long, OfflineRegionStatus?>()
                fun got(r: MlRegion, s: OfflineRegionStatus?) {
                    statuses[r.id] = s
                    if (statuses.size == all.size) onResult(group(all, statuses))
                }
                all.forEach { r ->
                    r.getStatus(object : MlRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) = got(r, status)
                        override fun onError(error: String?) = got(r, null)
                    })
                }
            }

            override fun onError(error: String) = onResult(emptyList())
        })
    }

    private fun group(all: List<MlRegion>, statuses: Map<Long, OfflineRegionStatus?>): List<OfflineRegion> =
        all.mapNotNull { r -> AreaInfo.decode(r.metadata)?.let { it to r } }
            .groupBy({ it.first.group }, { it })
            .map { (_, parts) ->
                val info = parts.first().first
                val st = parts.map { statuses[it.second.id] }
                OfflineRegion(
                    group = info.group, name = info.name, providerId = info.providerId,
                    createdAt = info.createdAt,
                    sizeBytes = st.sumOf { it?.completedResourceSize ?: 0L },
                    complete = st.all { it?.isComplete == true },
                )
            }
            .sortedByDescending { it.createdAt }

    /** Deletes the saved [area]; what it shares with other areas stays for them. */
    fun delete(ctx: Context, area: OfflineRegion, onDone: () -> Unit) {
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<MlRegion>?) {
                val mine = offlineRegions.orEmpty().filter { AreaInfo.decode(it.metadata)?.group == area.group }
                if (mine.isEmpty()) { onDone(); return }
                var left = mine.size
                val each = object : MlRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { if (--left == 0) onDone() }
                    override fun onError(error: String) { if (--left == 0) onDone() }
                }
                mine.forEach { it.delete(each) }
            }

            override fun onError(error: String) = onDone()
        })
    }

    /** Deletes every saved area and everything viewed. */
    fun clear(ctx: Context, onDone: () -> Unit) {
        manager(ctx).resetDatabase(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = onDone()
            override fun onError(message: String) = onDone()
        })
    }

    /** Bytes of map data on the phone: saved areas plus what was viewed. */
    fun cacheSizeBytes(ctx: Context): Long =
        File(FileSource.getResourcesCachePath(ctx), "mbgl-offline.db").length()

    private val noDelete = object : MlRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit
        override fun onError(error: String) = Unit
    }
}
