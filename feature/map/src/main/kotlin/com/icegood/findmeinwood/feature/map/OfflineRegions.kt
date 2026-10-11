package com.icegood.findmeinwood.feature.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import org.osmdroid.config.Configuration
import org.osmdroid.config.IConfigurationProvider
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

/** Upper bound for the tile cache (cache.db) before osmdroid starts trimming it. */
private const val TILE_CACHE_MAX_BYTES = 4L shl 30 // 4 GB
private const val TILE_CACHE_TRIM_BYTES = 3L shl 30

/**
 * One place for osmdroid's global configuration (paths, user agent, cache size).
 *
 * osmdroid's defaults (600 MB, trimmed to 500 MB, oldest tiles first) silently evict saved
 * offline areas once a few big ones exist, so the cache is allowed to grow much larger;
 * osmdroid still caps it by the free space on the device.
 */
internal fun configureOsmdroid(ctx: Context): IConfigurationProvider = Configuration.getInstance().apply {
    userAgentValue = ctx.packageName
    osmdroidBasePath = File(ctx.filesDir, "osmdroid")
    osmdroidTileCache = File(osmdroidBasePath, "tiles")
    tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
    tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
}

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
        val keys = cacheKeys(region).toList()
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
     * cache.db keys of every tile [region] covers. CacheManager.getTilesCoverage yields
     * MapTileIndex values, but cache.db is keyed by SqlTileWriter.getIndex(x, y, z) —
     * different formulas, so convert.
     */
    internal fun cacheKeys(region: OfflineRegion): Set<Long> = buildSet {
        for (z in region.zoomMin..region.zoomMax) {
            for (k in CacheManager.getTilesCoverage(region.boundingBox(), z)) {
                add(
                    SqlTileWriter.getIndex(
                        MapTileIndex.getX(k).toLong(),
                        MapTileIndex.getY(k).toLong(),
                        MapTileIndex.getZoom(k).toLong(),
                    ),
                )
            }
        }
    }

    /**
     * Tiles of [region] that no other saved area of the same provider still covers.
     * Overlapping areas share cache rows, so deleting the whole coverage would punch
     * holes into the areas the user kept.
     */
    internal fun exclusiveKeys(region: OfflineRegion, others: List<OfflineRegion>): Set<Long> {
        val keep = others
            .filter { it.id != region.id && it.sourceName == region.sourceName }
            .flatMapTo(HashSet()) { cacheKeys(it) }
        return cacheKeys(region) - keep
    }

    /**
     * Remove the tiles only [region] uses from the cache and drop its metadata.
     *
     * The delete runs on a worker thread; [onFinished] is invoked on the main thread once
     * the rows are gone, so the caller can refresh the statistics only after the cache
     * has actually changed.
     */
    fun deleteTiles(
        ctx: Context,
        region: OfflineRegion,
        onFinished: (Boolean) -> Unit = { },
    ) {
        remove(ctx, region.id)
        val others = load(ctx)
        val dbFile = File(cacheDir(ctx), "cache.db")
        Thread {
            val ok = runCatching {
                if (dbFile.exists()) deleteKeys(dbFile, exclusiveKeys(region, others).toList(), region.sourceName)
                true
            }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post { onFinished(ok) }
        }.apply { isDaemon = true }.start()
    }

    /** Deletes [provider]'s rows for [keys], chunked like [queryChunked]. */
    internal fun deleteKeys(dbFile: File, keys: List<Long>, provider: String) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            try {
                keys.chunked(400).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    val args: Array<Any> = (listOf<Any>(provider) + chunk.map { it.toString() }).toTypedArray()
                    db.execSQL("DELETE FROM tiles WHERE provider=? AND key IN ($placeholders)", args)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
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

    private fun cacheDir(ctx: Context): File = configureOsmdroid(ctx).osmdroidTileCache

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
