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

/**
 * Chat history, encrypted at rest with SQLCipher (see [SecureDb]). One database for the
 * app; every message belongs to one network ([COL_NETWORK], the network id in hex) and is
 * only ever read back within it, so a deleted or re-created network's history never shows
 * in another one.
 */
internal class ChatStore(context: Context) {

    private val changeFlow = MutableStateFlow(0L)

    private val helper: SupportSQLiteOpenHelper = SecureDb.open(
        context, DB_NAME,
        object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
            override fun onCreate(db: SupportSQLiteDatabase) = create(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // v1 had no network: its messages cannot be told apart, so they stay out of
                // every network (network '') instead of showing up in the wrong one.
                if (oldVersion < 2) {
                    db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_NETWORK TEXT NOT NULL DEFAULT ''")
                    db.execSQL("CREATE INDEX IF NOT EXISTS $NETWORK_INDEX ON $TABLE ($COL_NETWORK, $COL_CHANNEL, $COL_TIMESTAMP)")
                }
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
                $COL_IS_OWN INTEGER NOT NULL DEFAULT 0,
                $COL_NETWORK TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE INDEX idx_channel ON $TABLE ($COL_CHANNEL, $COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX $NETWORK_INDEX ON $TABLE ($COL_NETWORK, $COL_CHANNEL, $COL_TIMESTAMP)")
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

    fun insert(msg: ChatMessage, network: String = ""): Boolean {
        val cv = ContentValues().apply {
            put(COL_NETWORK, network)
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

    fun getMessages(channelId: String, network: String = "", limit: Int = 200): List<ChatMessage> {
        val c = db.query(
            "SELECT * FROM $TABLE WHERE $COL_NETWORK=? AND $COL_CHANNEL=? ORDER BY $COL_TIMESTAMP ASC LIMIT ?",
            arrayOf<Any?>(network, channelId, limit),
        )
        return c.use { cursor -> (0 until cursor.count).map { cursorToMessage(cursor.apply { moveToPosition(it) }) } }
    }

    fun observe(channelId: String, network: String = ""): Flow<List<ChatMessage>> {
        return changeFlow.map { getMessages(channelId, network) }
    }

    fun observeChannelIds(network: String = ""): Flow<List<String>> {
        return changeFlow.map { getChannelIds(network) }
    }

    fun getChannelIds(network: String = ""): List<String> {
        val c = db.query(
            "SELECT $COL_CHANNEL FROM $TABLE WHERE $COL_NETWORK=? GROUP BY $COL_CHANNEL ORDER BY MAX($COL_TIMESTAMP) DESC",
            arrayOf<Any?>(network),
        )
        return c.use { cur -> (0 until cur.count).map { cur.apply { moveToPosition(it) }.getString(0) } }
    }

    fun getLatestMessage(channelId: String, network: String = ""): ChatMessage? {
        val c = db.query(
            "SELECT * FROM $TABLE WHERE $COL_NETWORK=? AND $COL_CHANNEL=? ORDER BY $COL_TIMESTAMP DESC LIMIT 1",
            arrayOf<Any?>(network, channelId),
        )
        return c.use { if (it.moveToFirst()) cursorToMessage(it) else null }
    }

    /** The network is gone: its history goes with it. Returns the photo refs it held. */
    fun deleteNetwork(network: String): List<String> {
        val photos = db.query(
            "SELECT $COL_PHOTO_REF FROM $TABLE WHERE $COL_NETWORK=? AND $COL_PHOTO_REF IS NOT NULL",
            arrayOf<Any?>(network),
        ).use { c -> (0 until c.count).map { c.apply { moveToPosition(it) }.getString(0) } }
        val n = db.delete(TABLE, "$COL_NETWORK=?", arrayOf(network))
        if (n > 0) changeFlow.value = System.currentTimeMillis()
        return photos
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
        private const val DB_VERSION = 2
        private const val NETWORK_INDEX = "idx_network"
        const val TABLE = "messages"
        const val COL_ID = "id"
        const val COL_CHANNEL = "channel"
        const val COL_SENDER_ID = "sender_id"
        const val COL_SENDER_NAME = "sender_name"
        const val COL_TEXT = "text"
        const val COL_PHOTO_REF = "photo_ref"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_OWN = "is_own"
        const val COL_NETWORK = "network"
    }
}
