package com.icegood.findmeinwood.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Software key with the Keystore box's format: there is no AndroidKeyStore on the JVM. */
private class SoftBox : SecretBox() {
    private val k: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun key(): SecretKey = k
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureDbTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val previous = SecureStorage.box

    @BeforeTest fun setUp() { SecureStorage.box = SoftBox() }
    @AfterTest fun tearDown() { SecureStorage.box = previous }

    @Test
    fun `passphrase is random, 256-bit, stable and stored only sealed`() {
        val a = SecureDb.passphrase(ctx, "t1.db")
        assertEquals(32, a.size)
        assertContentEquals(a, SecureDb.passphrase(ctx, "t1.db"))
        assertFalse(SecureDb.passphrase(ctx, "t2.db").contentEquals(a), "each database has its own key")
        val stored = File(ctx.noBackupFilesDir, "t1.db.key").readBytes()
        assertTrue(SecretBox.isSealed(stored))
    }

    @Test
    fun `database without a usable key is discarded`() {
        // A plain database left by an older build: no key file exists for it.
        val db = ctx.getDatabasePath("plain.db").apply { parentFile?.mkdirs(); writeText("plain sqlite") }
        SecureDb.passphrase(ctx, "plain.db")
        assertFalse(db.exists(), "unreadable database must be deleted, not opened")

        // Key sealed under another device's Keystore key: also unusable.
        val first = SecureDb.passphrase(ctx, "moved.db")
        SecureStorage.box = SoftBox()
        assertFalse(SecureDb.passphrase(ctx, "moved.db").contentEquals(first))
    }

    @Test
    fun `sealed files round trip and reject other keys`() {
        val f = File(ctx.filesDir, "photo.bin")
        SealedFiles.write(f, byteArrayOf(1, 2, 3))
        assertFalse(f.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), SealedFiles.read(f))
        SecureStorage.box = SoftBox()
        assertNull(SealedFiles.read(f))
        f.writeBytes(byteArrayOf(9, 9))
        assertNull(SealedFiles.read(f), "plain files are not accepted")
    }
}
