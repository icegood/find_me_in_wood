package com.icegood.findmeinwood.feature.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.util.UUID

/** One saved offline map area (Google-Maps-style "offline map"). */
data class OfflineRegion(
    val id: String,
    val name: String,
    val latNorth: Double,
    val lonEast: Double,
    val latSouth: Double,
    val lonWest: Double,
    val zoomMin: Int,
    val zoomMax: Int,
    val tileCount: Int,
    val sizeBytes: Long,
    val createdAt: Long,
    val sourceName: String,
) {
    fun boundingBox(): BoundingBox = BoundingBox(latNorth, lonEast, latSouth, lonWest)
}

/** Cached tile count and bytes for one provider, read straight from osmdroid's cache.db. */
data class ProviderStat(
    val providerId: String,
    val tileCount: Int,
    val sizeBytes: Long,
)

/** Persistent store + cache helpers for the offline map areas. */
object OfflineRegions {
    private const val PREFS = "offline_regions"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<OfflineRegion> = buildList {
        prefs(ctx).all.values.forEach { raw ->
            (raw as? String)?.let { runCatching { add(decode(it)) } }
        }
    }.sortedByDescending { it.createdAt }

    fun add(ctx: Context, region: OfflineRegion) {
        val p = prefs(ctx)
        p.edit().putString(region.id, encode(region)).apply()
    }

    fun remove(ctx: Context, id: String) {
        prefs(ctx).edit().remove(id).apply()
    }

    fun cacheSizeBytes(ctx: Context): Long = cacheDir(ctx).walkTopDown()
        .filter { it.isFile }.sumOf { it.length() }

    fun clear(ctx: Context) {
        cacheDir(ctx).deleteRecursively()
        cacheDir(ctx).mkdirs()
        prefs(ctx).edit().clear().apply()
    }

    fun regionSizeBytes(ctx: Context, region: OfflineRegion): Long {
        val dbFile = File(cacheDir(ctx), "cache.db")
        if (!dbFile.exists()) return 0L
        // CacheManager.getTilesCoverage yields MapTileIndex values, but cache.db is keyed by
        // SqlTileWriter.getIndex(x, y, z) — different formulas, so convert before querying.
        val keys = mutableListOf<Long>()
        for (z in region.zoomMin..region.zoomMax) {
            for (k in CacheManager.getTilesCoverage(region.boundingBox(), z)) {
                keys += SqlTileWriter.getIndex(
                    MapTileIndex.getX(k).toLong(),
                    MapTileIndex.getY(k).toLong(),
                    MapTileIndex.getZoom(k).toLong(),
                )
            }
        }
        if (keys.isEmpty()) return 0L
        return runCatching { queryChunked(dbFile, keys, region.sourceName, "SUM(length(tile))") }
            .getOrDefault(0L)
    }

    /**
     * Sums [expr] over the cached tiles of [provider] whose key is in [keys], chunked to stay under
     * SQLite's per-statement host-parameter limit. Tiles are keyed by (key, provider), so the
     * provider filter keeps same-zoom tiles from other providers out of the total.
     */
    private fun queryChunked(dbFile: File, keys: List<Long>, provider: String, expr: String): Long {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        var total = 0L
        try {
            keys.chunked(400).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                // The keys are Longs derived from zoom/x/y above, never user input, so binding them
                // as literals keeps the statement under SQLite's host-parameter cap.
                val args: Array<String> = arrayOf(provider) + chunk.map { it.toString() }
                db.rawQuery(
                    "SELECT $expr FROM tiles WHERE provider=? AND key IN ($placeholders)",
                    args,
                ).use { c -> if (c.moveToFirst() && !c.isNull(0)) total += c.getLong(0) }
            }
        } finally {
            db.close()
        }
        return total
    }

    /**
     * Remove the tiles covered by [region] from the cache and drop its metadata.
     *
     * The delete runs on a worker thread without osmdroid's own progress dialog (the async variants
     * block on a native window); [onFinished] is invoked on the main thread once the rows are gone,
     * so the caller can refresh the statistics only after the cache has actually changed.
     */
    fun deleteTiles(
        ctx: Context,
        region: OfflineRegion,
        onFinished: (Boolean) -> Unit = { },
    ) {
        remove(ctx, region.id)
        Thread {
            val ok = runCatching {
                val src = tileSourceById(region.sourceName)
                val cm = CacheManager(src, SqlTileWriter(), region.zoomMin, region.zoomMax)
                val task = CacheManager.CacheManagerTask(
                    cm, cm.cleaningAction, region.boundingBox(), region.zoomMin, region.zoomMax,
                )
                cm.execute(task)
                task.get()
                true
            }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post { onFinished(ok) }
        }.apply { isDaemon = true }.start()
    }

    /** Per-provider tile counts and bytes currently held in the osmdroid cache. */
    fun providerStats(ctx: Context): List<ProviderStat> {
        val dbFile = File(cacheDir(ctx), "cache.db")
        if (!dbFile.exists()) return emptyList()
        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT provider, COUNT(*), SUM(length(tile)) FROM tiles GROUP BY provider", null)
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                add(
                                    ProviderStat(
                                        providerId = c.getString(0) ?: "",
                                        tileCount = c.getInt(1),
                                        sizeBytes = if (c.isNull(2)) 0L else c.getLong(2),
                                    ),
                                )
                            }
                        }
                    }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cacheDir(ctx: Context): File {
        val cfg = Configuration.getInstance()
        cfg.osmdroidBasePath = File(ctx.filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")
        return cfg.osmdroidTileCache
    }

    // '|' is the field separator: a user-typed name containing it made the record
    // undecodable, and load() then silently dropped the area.
    internal fun encode(r: OfflineRegion): String = listOf(
        r.id, r.name.replace('|', '/'), r.latNorth, r.lonEast, r.latSouth, r.lonWest, r.zoomMin, r.zoomMax,
        r.tileCount, r.sizeBytes, r.createdAt, r.sourceName,
    ).joinToString("|")

    internal fun decode(s: String): OfflineRegion {
        val p = s.split("|")
        fun d(i: Int) = p[i].toDouble()
        fun l(i: Int) = p[i].toLong()
        fun int(i: Int) = p[i].toInt()
        return OfflineRegion(
            id = p[0], name = p[1], latNorth = d(2), lonEast = d(3), latSouth = d(4),
            lonWest = d(5), zoomMin = int(6), zoomMax = int(7), tileCount = int(8),
            sizeBytes = l(9), createdAt = l(10), sourceName = p[11],
        )
    }

    fun newId(): String = UUID.randomUUID().toString()
}
