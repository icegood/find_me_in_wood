package com.icegood.findmeinwood.core.auth

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

internal class SQLiteAuthStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_EMAIL TEXT UNIQUE NOT NULL,
                $COL_PASSWORD_HASH TEXT NOT NULL,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_GOOGLE_ID TEXT,
                $COL_PHOTO_URL TEXT,
                $COL_CREATED_AT INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insertUser(email: String, passwordHash: String, displayName: String): Long {
        val cv = ContentValues().apply {
            put(COL_EMAIL, email.lowercase().trim())
            put(COL_PASSWORD_HASH, passwordHash)
            put(COL_DISPLAY_NAME, displayName)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun queryByEmail(email: String): UserProfile? {
        val c = readableDatabase.query(
            TABLE, null, "$COL_EMAIL=?", arrayOf(email.lowercase().trim()),
            null, null, null,
        )
        return c.use {
            if (it.moveToFirst()) cursorToProfile(it) else null
        }
    }

    fun queryById(uid: String): UserProfile? {
        val c = readableDatabase.query(
            TABLE, null, "$COL_ID=?", arrayOf(uid),
            null, null, null,
        )
        return c.use {
            if (it.moveToFirst()) cursorToProfile(it) else null
        }
    }

    fun queryByGoogleId(googleId: String): UserProfile? {
        val c = readableDatabase.query(
            TABLE, null, "$COL_GOOGLE_ID=?", arrayOf(googleId),
            null, null, null,
        )
        return c.use {
            if (it.moveToFirst()) cursorToProfile(it) else null
        }
    }

    fun updateGoogleId(uid: String, googleId: String): Int {
        val cv = ContentValues().apply { put(COL_GOOGLE_ID, googleId) }
        return writableDatabase.update(TABLE, cv, "$COL_ID=?", arrayOf(uid))
    }

    fun verifyPassword(email: String, password: String): UserProfile? {
        val hash = hashPassword(password)
        val c = readableDatabase.query(
            TABLE, null, "$COL_EMAIL=? AND $COL_PASSWORD_HASH=?",
            arrayOf(email.lowercase().trim(), hash),
            null, null, null,
        )
        return c.use {
            if (it.moveToFirst()) cursorToProfile(it) else null
        }
    }

    private fun cursorToProfile(c: Cursor): UserProfile = UserProfile(
        uid = c.getLong(c.getColumnIndexOrThrow(COL_ID)).toString(),
        displayName = c.getString(c.getColumnIndexOrThrow(COL_DISPLAY_NAME)),
        email = c.getString(c.getColumnIndexOrThrow(COL_EMAIL)),
        photoUrl = c.getString(c.getColumnIndexOrThrow(COL_PHOTO_URL)),
        googleId = c.getString(c.getColumnIndexOrThrow(COL_GOOGLE_ID)),
        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)),
    )

    companion object {
        private const val DB_NAME = "auth.db"
        private const val DB_VERSION = 1
        const val TABLE = "users"
        const val COL_ID = "_id"
        const val COL_EMAIL = "email"
        const val COL_PASSWORD_HASH = "password_hash"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_GOOGLE_ID = "google_id"
        const val COL_PHOTO_URL = "photo_url"
        const val COL_CREATED_AT = "created_at"

        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val salted = "fmiw_$password-salt".toByteArray()
            return digest.digest(salted).joinToString("") { "%02x".format(it) }
        }
    }
}
