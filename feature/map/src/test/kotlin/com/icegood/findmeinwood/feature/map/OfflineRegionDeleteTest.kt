package com.icegood.findmeinwood.feature.map

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OfflineRegionDeleteTest {
    private fun region(id: String, west: Double, east: Double, source: String = "Mapnik") = OfflineRegion(
        id = id, name = id, latNorth = 50.02, lonEast = east, latSouth = 50.0, lonWest = west,
        zoomMin = 14, zoomMax = 15, tileCount = 0, sizeBytes = 0, createdAt = 0, sourceName = source,
    )

    private val a = region("a", 30.00, 30.04)
    private val b = region("b", 30.02, 30.06) // overlaps the east half of a

    @Test
    fun `tiles shared with another area are kept`() {
        val shared = OfflineRegions.cacheKeys(a) intersect OfflineRegions.cacheKeys(b)
        assertTrue(shared.isNotEmpty(), "fixture areas must overlap")
        val doomed = OfflineRegions.exclusiveKeys(a, listOf(a, b))
        assertTrue((doomed intersect shared).isEmpty(), "shared tiles must survive")
        assertEquals(OfflineRegions.cacheKeys(a) - shared, doomed)
    }

    @Test
    fun `areas of another provider do not protect tiles`() {
        val other = region("b", 30.02, 30.06, source = "USGS_SAT")
        assertEquals(OfflineRegions.cacheKeys(a), OfflineRegions.exclusiveKeys(a, listOf(other)))
    }

    @Test
    fun `deleteKeys removes only the given provider rows`() {
        val dir = Files.createTempDirectory("tiles").toFile()
        val dbFile = File(dir, "cache.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE tiles (key INTEGER, provider TEXT, tile BLOB, expires INTEGER, PRIMARY KEY (key, provider))")
            listOf(1L to "Mapnik", 2L to "Mapnik", 1L to "USGS_SAT").forEach { (k, p) ->
                db.execSQL("INSERT INTO tiles (key, provider, tile) VALUES (?, ?, x'00')", arrayOf<Any>(k, p))
            }
        }
        OfflineRegions.deleteKeys(dbFile, listOf(1L), "Mapnik")
        val left = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT key, provider FROM tiles ORDER BY provider, key", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
        }
        assertEquals(listOf(2L to "Mapnik", 1L to "USGS_SAT"), left)
        dir.deleteRecursively()
    }
}
