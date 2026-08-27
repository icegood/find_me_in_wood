package app.findmeinwood.core.auth

import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], instrumentedPackages = ["androidx.sqlite"])
class AuthManagerTest {

    private lateinit var manager: AuthManager

    @Before
    fun setup() {
        manager = AuthManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `register creates user and returns success`() = runTest {
        val result = manager.register("test@example.com", "password123", "Test User")
        assertTrue(result is AuthResult.Success)
        val profile = (result as AuthResult.Success).profile
        assertEquals("test@example.com", profile.email)
        assertEquals("Test User", profile.displayName)
        assertNotNull(profile.uid)
    }

    @Test
    fun `login with valid credentials succeeds`() = runTest {
        manager.register("login@example.com", "password123", "Login User")
        val result = manager.login("login@example.com", "password123")
        assertTrue(result is AuthResult.Success)
        assertEquals("login@example.com", (result as AuthResult.Success).profile.email)
    }

    @Test
    fun `login with wrong password fails`() = runTest {
        manager.register("wrong@example.com", "password123", "Wrong User")
        val result = manager.login("wrong@example.com", "wrongpassword")
        assertTrue(result is AuthResult.Error)
    }

    @Test
    fun `register duplicate email fails`() = runTest {
        manager.register("dup@example.com", "password123", "Dup User")
        val result = manager.register("dup@example.com", "password123", "Dup User 2")
        assertTrue(result is AuthResult.Error)
    }

    @Test
    fun `register with short password fails`() = runTest {
        val result = manager.register("short@example.com", "123", "Short")
        assertTrue(result is AuthResult.Error)
    }

    @Test
    fun `get profile by uid returns user`() = runTest {
        val result = manager.register("profile@example.com", "password123", "Profile User")
        val uid = (result as AuthResult.Success).profile.uid
        val profile = manager.getProfile(uid)
        assertNotNull(profile)
        assertEquals("profile@example.com", profile.email)
    }

    @Test
    fun `get profile for nonexistent uid returns null`() = runTest {
        val profile = manager.getProfile("99999")
        assertNull(profile)
    }

    @Test
    fun `link google adds google id to profile`() = runTest {
        val result = manager.register("google@example.com", "password123", "Google User")
        val uid = (result as AuthResult.Success).profile.uid
        val linkResult = manager.linkGoogle(uid, "google-id-123")
        assertTrue(linkResult is AuthResult.Success)
        assertEquals("google-id-123", (linkResult as AuthResult.Success).profile.googleId)
    }

    @Test
    fun `link google on already linked account returns AlreadyLinked`() = runTest {
        val result = manager.register("linked@example.com", "password123", "Linked User")
        val uid = (result as AuthResult.Success).profile.uid
        manager.linkGoogle(uid, "google-id-1")
        val linkResult = manager.linkGoogle(uid, "google-id-2")
        assertTrue(linkResult is AuthResult.AlreadyLinked)
    }

    @Test
    fun `set and get current user`() = runTest {
        val result = manager.register("current@example.com", "password123", "Current User")
        val uid = (result as AuthResult.Success).profile.uid
        manager.setCurrentUserId(uid)
        val current = manager.getCurrentUser()
        assertNotNull(current)
        assertEquals("current@example.com", current.email)
    }

    @Test
    fun `password is stored as hash not plaintext`() = runTest {
        val hash1 = SQLiteAuthStore.hashPassword("password123")
        val hash2 = SQLiteAuthStore.hashPassword("password123")
        val hash3 = SQLiteAuthStore.hashPassword("different")
        assertEquals(hash1, hash2)
        assertTrue(hash1 != hash3)
        assertTrue(!hash1.contains("password"))
    }

    @Test
    fun `register with blank email fails`() = runTest {
        val result = manager.register("", "password123", "Blank")
        assertTrue(result is AuthResult.Error)
    }

    @Test
    fun `login nonexistent user fails`() = runTest {
        val result = manager.login("nope@example.com", "password123")
        assertTrue(result is AuthResult.Error)
    }

    @Test
    fun `link google for nonexistent user fails`() = runTest {
        val result = manager.linkGoogle("99999", "google-id")
        assertTrue(result is AuthResult.Error)
    }

    @Test
    fun `getCurrentUser returns null when not logged in`() = runTest {
        manager.setCurrentUserId(null)
        assertNull(manager.getCurrentUser())
    }
}
