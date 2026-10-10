package com.icegood.findmeinwood.core.auth

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

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
        val c = readableDatabase.query(
            TABLE, null, "$COL_EMAIL=?", arrayOf(email.lowercase().trim()),
            null, null, null,
        )
        val (profile, stored) = c.use {
            if (!it.moveToFirst()) return null
            cursorToProfile(it) to it.getString(it.getColumnIndexOrThrow(COL_PASSWORD_HASH))
        }
        if (!matches(password, stored)) return null
        // Upgrade rows written by the old unsalted SHA-256 scheme on successful login.
        if (!stored.startsWith(PBKDF2_PREFIX)) {
            val cv = ContentValues().apply { put(COL_PASSWORD_HASH, hashPassword(password)) }
            writableDatabase.update(TABLE, cv, "$COL_ID=?", arrayOf(profile.uid))
        }
        return profile
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

        private const val PBKDF2_PREFIX = "pbkdf2$"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private val random = SecureRandom()

        /** Salted PBKDF2-HMAC-SHA256, stored as `pbkdf2$<iterations>$<saltHex>$<hashHex>`. */
        fun hashPassword(password: String): String {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            return encode(PBKDF2_ITERATIONS, salt, pbkdf2(password, salt, PBKDF2_ITERATIONS))
        }

        /** Constant-time check against a stored hash (current or legacy format). */
        fun matches(password: String, stored: String): Boolean {
            if (!stored.startsWith(PBKDF2_PREFIX)) {
                return MessageDigest.isEqual(legacyHash(password).toByteArray(), stored.toByteArray())
            }
            val parts = stored.removePrefix(PBKDF2_PREFIX).split("$")
            if (parts.size != 3) return false
            val iterations = parts[0].toIntOrNull() ?: return false
            val salt = runCatching { parts[1].unhex() }.getOrNull() ?: return false
            val expected = runCatching { parts[2].unhex() }.getOrNull() ?: return false
            return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected)
        }

        /** Unusable random password for accounts that only sign in through Google. */
        fun randomPasswordHash(): String =
            hashPassword(ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) })

        /** Pre-PBKDF2 scheme (static salt, single SHA-256); kept only to verify old rows. */
        internal fun legacyHash(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val salted = "fmiw_$password-salt".toByteArray()
            return digest.digest(salted).joinToString("") { "%02x".format(it) }
        }

        private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun encode(iterations: Int, salt: ByteArray, hash: ByteArray): String =
            PBKDF2_PREFIX + iterations + "$" + salt.hex() + "$" + hash.hex()

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
