package app.findmeinwood.core.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri

class ChatContentProvider : ContentProvider() {

    private lateinit var store: ChatStore

    override fun onCreate(): Boolean {
        store = ChatStore(context!!)
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val db = store.readableDatabase
        return db.query(ChatStore.TABLE, projection, selection, selectionArgs, null, null, sortOrder)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = values!!.getAsString(ChatStore.COL_ID)
        val msg = ChatMessage(
            id = id,
            channelId = values.getAsString(ChatStore.COL_CHANNEL),
            senderId = values.getAsString(ChatStore.COL_SENDER_ID),
            senderName = values.getAsString(ChatStore.COL_SENDER_NAME),
            text = values.getAsString(ChatStore.COL_TEXT),
            photoRef = values.getAsString(ChatStore.COL_PHOTO_REF),
            timestamp = values.getAsLong(ChatStore.COL_TIMESTAMP) ?: System.currentTimeMillis(),
            isOwn = (values.getAsInteger(ChatStore.COL_IS_OWN) ?: 0) == 1,
        )
        store.insert(msg)
        return Uri.withAppendedPath(AUTH_URI, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.messages"

    companion object {
        const val AUTHORITY = "app.findmeinwood.chat"
        val AUTH_URI: Uri = Uri.parse("content://$AUTHORITY")
    }
}
