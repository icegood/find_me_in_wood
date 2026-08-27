package app.findmeinwood.core.auth

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri

class AuthContentProvider : ContentProvider() {

    private lateinit var store: SQLiteAuthStore

    override fun onCreate(): Boolean {
        store = SQLiteAuthStore(context!!)
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = when (matcher.match(uri)) {
        USERS -> {
            val db = store.readableDatabase
            db.query(SQLiteAuthStore.TABLE, projection, selection, selectionArgs, null, null, sortOrder)
        }
        USER_ID -> {
            val id = uri.lastPathSegment!!
            val db = store.readableDatabase
            db.query(
                SQLiteAuthStore.TABLE, projection,
                "${SQLiteAuthStore.COL_ID}=?", arrayOf(id),
                null, null, sortOrder,
            )
        }
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        require(matcher.match(uri) == USERS) { "Invalid URI for insert: $uri" }
        val id = store.insertUser(
            values!!.getAsString(SQLiteAuthStore.COL_EMAIL),
            values.getAsString(SQLiteAuthStore.COL_PASSWORD_HASH),
            values.getAsString(SQLiteAuthStore.COL_DISPLAY_NAME),
        )
        return ContentUris.withAppendedId(AUTH_URI, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        return when (matcher.match(uri)) {
            USER_ID -> {
                val id = uri.lastPathSegment!!
                if (values!!.containsKey(SQLiteAuthStore.COL_GOOGLE_ID)) {
                    store.updateGoogleId(id, values.getAsString(SQLiteAuthStore.COL_GOOGLE_ID))
                } else 0
            }
            else -> 0
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        USERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.users"
        USER_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.users"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    companion object {
        const val AUTHORITY = "app.findmeinwood.auth"
        val AUTH_URI: Uri = Uri.parse("content://$AUTHORITY")
        private const val USERS = 1
        private const val USER_ID = 2
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "users", USERS)
            addURI(AUTHORITY, "users/#", USER_ID)
        }
    }
}
