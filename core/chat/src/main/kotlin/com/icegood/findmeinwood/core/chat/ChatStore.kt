package com.icegood.findmeinwood.core.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import com.icegood.findmeinwood.core.storage.SecureDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Chat history, encrypted at rest with SQLCipher (see [SecureDb]). */
internal class ChatStore(context: Context) {

    private val changeFlow = MutableStateFlow(0L)

    private val helper: SupportSQLiteOpenHelper = SecureDb.open(
        context, DB_NAME,
        object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
            override fun onCreate(db: SupportSQLiteDatabase) = create(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE")
                create(db)
            }
        },
    )
    private val db: SupportSQLiteDatabase get() = helper.writableDatabase

    private fun create(db: SupportSQLiteDatabase) {
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

    /** Raw query for the content provider. */
    fun query(projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        db.query(
            SupportSQLiteQueryBuilder.builder(TABLE)
                .columns(projection?.let { arrayOf(*it) })
                .selection(selection, selectionArgs)
                .orderBy(sortOrder)
                .create(),
        )

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
        val result = db.insert(TABLE, SQLiteDatabase.CONFLICT_IGNORE, cv)
        if (result != -1L) changeFlow.value = System.currentTimeMillis()
        return result != -1L
    }

    fun getMessages(channelId: String, limit: Int = 200): List<ChatMessage> {
        val c = db.query(
            "SELECT * FROM $TABLE WHERE $COL_CHANNEL=? ORDER BY $COL_TIMESTAMP ASC LIMIT ?",
            arrayOf<Any?>(channelId, limit),
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
        val c = db.query(
            "SELECT $COL_CHANNEL FROM $TABLE GROUP BY $COL_CHANNEL ORDER BY MAX($COL_TIMESTAMP) DESC",
        )
        return c.use { cur -> (0 until cur.count).map { cur.apply { moveToPosition(it) }.getString(0) } }
    }

    fun getLatestMessage(channelId: String): ChatMessage? {
        val c = db.query(
            "SELECT * FROM $TABLE WHERE $COL_CHANNEL=? ORDER BY $COL_TIMESTAMP DESC LIMIT 1",
            arrayOf<Any?>(channelId),
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
