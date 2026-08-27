package app.findmeinwood.core.auth

import android.content.ContentValues
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], instrumentedPackages = ["androidx.sqlite"])
class AuthContentProviderTest {

    private lateinit var provider: AuthContentProvider
    private val authority = "app.findmeinwood.auth"
    private val baseUri = Uri.parse("content://$authority")

    @Before
    fun setup() {
        provider = AuthContentProvider()
        provider.attachInfo(ApplicationProvider.getApplicationContext(), null)
    }

    @Test
    fun `insert user returns content uri with id`() {
        val cv = ContentValues().apply {
            put(SQLiteAuthStore.COL_EMAIL, "provider@test.com")
            put(SQLiteAuthStore.COL_PASSWORD_HASH, SQLiteAuthStore.hashPassword("pass123"))
            put(SQLiteAuthStore.COL_DISPLAY_NAME, "Provider User")
        }
        val uri = provider.insert(baseUri.buildUpon().appendPath("users").build(), cv)
        assertNotNull(uri)
        assertTrue(uri.lastPathSegment!!.toLong() > 0)
    }

    @Test
    fun `query users returns cursor`() {
        val cv = ContentValues().apply {
            put(SQLiteAuthStore.COL_EMAIL, "query@test.com")
            put(SQLiteAuthStore.COL_PASSWORD_HASH, SQLiteAuthStore.hashPassword("pass123"))
            put(SQLiteAuthStore.COL_DISPLAY_NAME, "Query User")
        }
        provider.insert(baseUri.buildUpon().appendPath("users").build(), cv)
        val cursor = provider.query(
            baseUri.buildUpon().appendPath("users").build(),
            null, null, null, null,
        )
        assertNotNull(cursor)
        assertTrue(cursor.count >= 1)
        cursor.close()
    }

    @Test
    fun `query user by id returns single row`() {
        val cv = ContentValues().apply {
            put(SQLiteAuthStore.COL_EMAIL, "byid@test.com")
            put(SQLiteAuthStore.COL_PASSWORD_HASH, SQLiteAuthStore.hashPassword("pass123"))
            put(SQLiteAuthStore.COL_DISPLAY_NAME, "By ID User")
        }
        val uri = provider.insert(baseUri.buildUpon().appendPath("users").build(), cv)
        val id = uri!!.lastPathSegment
        val cursor = provider.query(
            baseUri.buildUpon().appendPath("users").appendPath(id).build(),
            null, null, null, null,
        )
        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        cursor.close()
    }

    @Test
    fun `update google id modifies record`() {
        val cv = ContentValues().apply {
            put(SQLiteAuthStore.COL_EMAIL, "update@test.com")
            put(SQLiteAuthStore.COL_PASSWORD_HASH, SQLiteAuthStore.hashPassword("pass123"))
            put(SQLiteAuthStore.COL_DISPLAY_NAME, "Update User")
        }
        val uri = provider.insert(baseUri.buildUpon().appendPath("users").build(), cv)
        val id = uri!!.lastPathSegment
        val updateCv = ContentValues().apply {
            put(SQLiteAuthStore.COL_GOOGLE_ID, "google-abc")
        }
        val updated = provider.update(
            baseUri.buildUpon().appendPath("users").appendPath(id).build(),
            updateCv, null, null,
        )
        assertEquals(1, updated)
    }

    @Test
    fun `getType returns correct vnd type`() {
        val type = provider.getType(baseUri.buildUpon().appendPath("users").build())
        assertEquals("vnd.android.cursor.dir/vnd.$authority.users", type)
    }

    @Test
    fun `getType for single user returns item type`() {
        val type = provider.getType(baseUri.buildUpon().appendPath("users").appendPath("1").build())
        assertEquals("vnd.android.cursor.item/vnd.$authority.users", type)
    }

    @Test
    fun `delete returns zero`() {
        val deleted = provider.delete(baseUri.buildUpon().appendPath("users").build(), null, null)
        assertEquals(0, deleted)
    }
}
