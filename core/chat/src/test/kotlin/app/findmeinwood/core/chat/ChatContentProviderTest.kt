package app.findmeinwood.core.chat

import android.content.ContentValues
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChatContentProviderTest {

    private lateinit var provider: ChatContentProvider

    @Before
    fun setup() {
        provider = ChatContentProvider()
        provider.attachInfo(ApplicationProvider.getApplicationContext(), null)
    }

    @Test
    fun `insert message returns content uri`() {
        val cv = ContentValues().apply {
            put(ChatStore.COL_ID, "cmsg-1")
            put(ChatStore.COL_CHANNEL, "group")
            put(ChatStore.COL_SENDER_ID, "from-hex")
            put(ChatStore.COL_SENDER_NAME, "Emily")
            put(ChatStore.COL_TEXT, "hello via provider")
            put(ChatStore.COL_TIMESTAMP, 1234L)
            put(ChatStore.COL_IS_OWN, 0)
        }
        val uri = provider.insert(ChatContentProvider.AUTH_URI, cv)
        assertNotNull(uri)
        assertEquals("cmsg-1", uri.lastPathSegment)
    }

    @Test
    fun `query messages returns cursor with rows`() {
        val cv = ContentValues().apply {
            put(ChatStore.COL_ID, "cmsg-2")
            put(ChatStore.COL_CHANNEL, "group")
            put(ChatStore.COL_SENDER_ID, "hex")
            put(ChatStore.COL_SENDER_NAME, "Fern")
            put(ChatStore.COL_TEXT, "queried")
            put(ChatStore.COL_TIMESTAMP, 5678L)
            put(ChatStore.COL_IS_OWN, 1)
        }
        provider.insert(ChatContentProvider.AUTH_URI, cv)
        val cursor = provider.query(
            ChatContentProvider.AUTH_URI, null, null, null, null,
        )
        assertNotNull(cursor)
        assertTrue(cursor.count >= 1)
        cursor.close()
    }

    @Test
    fun `getType returns message dir type`() {
        val type = provider.getType(ChatContentProvider.AUTH_URI)
        assertEquals("vnd.android.cursor.dir/vnd.app.findmeinwood.chat.messages", type)
    }

    @Test
    fun `update returns zero`() {
        val updated = provider.update(ChatContentProvider.AUTH_URI, ContentValues(), null, null)
        assertEquals(0, updated)
    }

    @Test
    fun `delete returns zero`() {
        val deleted = provider.delete(ChatContentProvider.AUTH_URI, null, null)
        assertEquals(0, deleted)
    }
}