package com.icegood.findmeinwood.core.chat

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChatStoreTest {

    private lateinit var store: ChatStore

    @Before
    fun setup() {
        // SQLCipher's native library does not load on the JVM: plain SQLite for unit tests.
        com.icegood.findmeinwood.core.storage.SecureDb.factoryOverride =
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        store = ChatStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `insert and retrieve message`() {
        val msg = ChatMessage(
            id = "msg1", channelId = "ch1", senderId = "peer1",
            senderName = "Alice", text = "Hello", timestamp = 1000L,
        )
        assertTrue(store.insert(msg))
        val messages = store.getMessages("ch1")
        assertEquals(1, messages.size)
        assertEquals("Hello", messages[0].text)
        assertEquals("Alice", messages[0].senderName)
    }

    @Test
    fun `messages ordered by timestamp ascending`() {
        store.insert(ChatMessage("m1", "ch1", "p1", "A", "first", timestamp = 2000L))
        store.insert(ChatMessage("m2", "ch1", "p1", "A", "second", timestamp = 1000L))
        store.insert(ChatMessage("m3", "ch1", "p1", "A", "third", timestamp = 3000L))
        val messages = store.getMessages("ch1")
        assertEquals(3, messages.size)
        assertEquals("second", messages[0].text)
        assertEquals("first", messages[1].text)
        assertEquals("third", messages[2].text)
    }

    @Test
    fun `duplicate message id is ignored`() {
        store.insert(ChatMessage("dup", "ch1", "p1", "A", "msg1", timestamp = 1000L))
        store.insert(ChatMessage("dup", "ch1", "p1", "A", "msg2", timestamp = 2000L))
        val messages = store.getMessages("ch1")
        assertEquals(1, messages.size)
        assertEquals("msg1", messages[0].text)
    }

    @Test
    fun `getChannelIds returns distinct channels`() {
        store.insert(ChatMessage("m1", "ch1", "p1", "A", "hi", timestamp = 1000L))
        store.insert(ChatMessage("m2", "ch2", "p2", "B", "hey", timestamp = 2000L))
        store.insert(ChatMessage("m3", "ch1", "p1", "A", "yo", timestamp = 3000L))
        val channels = store.getChannelIds()
        assertEquals(2, channels.size)
        assertTrue(channels.contains("ch1"))
        assertTrue(channels.contains("ch2"))
    }

    @Test
    fun `getLatestMessage returns most recent for channel`() {
        store.insert(ChatMessage("m1", "ch1", "p1", "A", "old", timestamp = 1000L))
        store.insert(ChatMessage("m2", "ch1", "p2", "B", "new", timestamp = 2000L))
        val latest = store.getLatestMessage("ch1")
        assertNotNull(latest)
        assertEquals("new", latest.text)
    }

    @Test
    fun `observe emits current messages on change`() = runTest {
        store.insert(ChatMessage("m1", "ch1", "p1", "A", "hello", timestamp = 1000L))
        val messages = store.observe("ch1").first()
        assertEquals(1, messages.size)
        assertEquals("hello", messages[0].text)
    }

    @Test
    fun `own messages flagged correctly`() {
        store.insert(ChatMessage("m1", "ch1", "p1", "A", "own", timestamp = 1000L, isOwn = true))
        store.insert(ChatMessage("m2", "ch1", "p2", "B", "other", timestamp = 2000L, isOwn = false))
        val messages = store.getMessages("ch1")
        assertTrue(messages[0].isOwn)
        assertTrue(!messages[1].isOwn)
    }

    @Test
    fun `photo reference stored correctly`() {
        store.insert(ChatMessage("m1", "ch1", "p1", "A", "", photoRef = "content://photo/1", timestamp = 1000L))
        val messages = store.getMessages("ch1")
        assertEquals("content://photo/1", messages[0].photoRef)
    }

    @Test
    fun `each network sees only its own messages, and deleting one drops only its history`() {
        store.insert(ChatMessage("a1", "group", "p1", "A", "old net", photoRef = "file:///x/in_a1.jpg", timestamp = 1000L), "aa")
        store.insert(ChatMessage("b1", "group", "p1", "A", "new net", timestamp = 2000L), "bb")
        assertEquals(listOf("new net"), store.getMessages("group", "bb").map { it.text })
        assertEquals(listOf("old net"), store.getMessages("group", "aa").map { it.text })
        assertEquals("new net", store.getLatestMessage("group", "bb")?.text)
        assertEquals(listOf("group"), store.getChannelIds("bb"))
        assertTrue(store.getChannelIds("cc").isEmpty())

        assertEquals(listOf("file:///x/in_a1.jpg"), store.deleteNetwork("aa"))
        assertTrue(store.getMessages("group", "aa").isEmpty())
        assertEquals(1, store.getMessages("group", "bb").size)
        assertTrue(store.deleteNetwork("aa").isEmpty())
    }

    @Test
    fun `a v1 database keeps its rows, outside every network`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("chat.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("chat.db"), null).use { db ->
            db.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, channel TEXT NOT NULL, sender_id TEXT NOT NULL, " +
                    "sender_name TEXT NOT NULL, text TEXT, photo_ref TEXT, timestamp INTEGER NOT NULL, is_own INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("INSERT INTO messages VALUES ('old', 'group', 'p', 'A', 'stale', NULL, 1, 0)")
            db.version = 1
        }
        val upgraded = ChatStore(ctx)
        assertTrue(upgraded.getMessages("group", "bb").isEmpty(), "an old message shows in no network")
        assertEquals(listOf("stale"), upgraded.getMessages("group").map { it.text }, "but is kept")
        assertTrue(upgraded.insert(ChatMessage("n", "group", "p", "A", "fresh", timestamp = 2L), "bb"))
        assertEquals(listOf("fresh"), upgraded.getMessages("group", "bb").map { it.text })
    }
}
