package com.icegood.findmeinwood.core.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class ChatStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val changeFlow = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_CHANNEL TEXT NOT NULL,
                $COL_SENDER_ID TEXT NOT NULL,
                $COL_SENDER_NAME TEXT NOT NULL,
                $COL_TEXT TEXT,
                $COL_PHOTO_REF TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_OWN INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_channel ON $TABLE ($COL_CHANNEL, $COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(msg: ChatMessage): Boolean {
        val cv = ContentValues().apply {
            put(COL_ID, msg.id)
            put(COL_CHANNEL, msg.channelId)
            put(COL_SENDER_ID, msg.senderId)
            put(COL_SENDER_NAME, msg.senderName)
            put(COL_TEXT, msg.text)
            put(COL_PHOTO_REF, msg.photoRef)
            put(COL_TIMESTAMP, msg.timestamp)
            put(COL_IS_OWN, if (msg.isOwn) 1 else 0)
        }
        val result = writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (result != -1L) changeFlow.value = System.currentTimeMillis()
        return result != -1L
    }

    fun getMessages(channelId: String, limit: Int = 200): List<ChatMessage> {
        val c = readableDatabase.query(
            TABLE, null, "$COL_CHANNEL=?", arrayOf(channelId),
            null, null, "$COL_TIMESTAMP ASC", limit.toString(),
        )
        return c.use { cursor -> (0 until cursor.count).map { cursorToMessage(cursor.apply { moveToPosition(it) }) } }
    }

    fun observe(channelId: String): Flow<List<ChatMessage>> {
        return changeFlow.map { getMessages(channelId) }
    }

    fun observeChannelIds(): Flow<List<String>> {
        return changeFlow.map { getChannelIds() }
    }

    fun getChannelIds(): List<String> {
        val c = readableDatabase.rawQuery(
            "SELECT $COL_CHANNEL FROM $TABLE GROUP BY $COL_CHANNEL ORDER BY MAX($COL_TIMESTAMP) DESC", null,
        )
        return c.use { cur -> (0 until cur.count).map { cur.apply { moveToPosition(it) }.getString(0) } }
    }

    fun getLatestMessage(channelId: String): ChatMessage? {
        val c = readableDatabase.query(
            TABLE, null, "$COL_CHANNEL=?", arrayOf(channelId),
            null, null, "$COL_TIMESTAMP DESC", "1",
        )
        return c.use { if (it.moveToFirst()) cursorToMessage(it) else null }
    }

    private fun cursorToMessage(c: Cursor): ChatMessage = ChatMessage(
        id = c.getString(c.getColumnIndexOrThrow(COL_ID)),
        channelId = c.getString(c.getColumnIndexOrThrow(COL_CHANNEL)),
        senderId = c.getString(c.getColumnIndexOrThrow(COL_SENDER_ID)),
        senderName = c.getString(c.getColumnIndexOrThrow(COL_SENDER_NAME)),
        text = c.getString(c.getColumnIndexOrThrow(COL_TEXT)) ?: "",
        photoRef = c.getString(c.getColumnIndexOrThrow(COL_PHOTO_REF)),
        timestamp = c.getLong(c.getColumnIndexOrThrow(COL_TIMESTAMP)),
        isOwn = c.getInt(c.getColumnIndexOrThrow(COL_IS_OWN)) == 1,
    )

    companion object {
        private const val DB_NAME = "chat.db"
        private const val DB_VERSION = 1
        const val TABLE = "messages"
        const val COL_ID = "id"
        const val COL_CHANNEL = "channel"
        const val COL_SENDER_ID = "sender_id"
        const val COL_SENDER_NAME = "sender_name"
        const val COL_TEXT = "text"
        const val COL_PHOTO_REF = "photo_ref"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_OWN = "is_own"
    }
}
