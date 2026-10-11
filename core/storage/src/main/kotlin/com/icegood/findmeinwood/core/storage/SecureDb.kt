package com.icegood.findmeinwood.core.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/**
 * Opens app databases encrypted with SQLCipher. Each database has its own random 256-bit
 * passphrase, stored only sealed by [SecureStorage.box] (Android Keystore) in
 * noBackupFilesDir, so a copied database file is unreadable without this device.
 */
object SecureDb {
    private const val KEY_BYTES = 32

    /**
     * Unit tests only: SQLCipher's native library does not load on the host JVM, so tests
     * plug in androidx `FrameworkSQLiteOpenHelperFactory` here.
     */
    @Volatile var factoryOverride: SupportSQLiteOpenHelper.Factory? = null

    private val nativeLoaded by lazy { System.loadLibrary("sqlcipher") }
    private val random = SecureRandom()

    fun open(context: Context, name: String, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper {
        val ctx = context.applicationContext ?: context
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(callback).build()
        factoryOverride?.let { return it.create(config) }
        nativeLoaded
        return SupportOpenHelperFactory(passphrase(ctx, name)).create(config)
    }

    /**
     * The passphrase for database [name]. Without a readable key any existing database file
     * is either plain (older build) or undecryptable, so it is deleted and a new key made.
     */
    @Synchronized
    internal fun passphrase(ctx: Context, name: String): ByteArray {
        val keyFile = File(ctx.noBackupFilesDir, "$name.key")
        runCatching { SecureStorage.box.open(keyFile.readBytes()) }.getOrNull()
            ?.takeIf { it.size == KEY_BYTES }
            ?.let { return it }
        ctx.deleteDatabase(name)
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        SealedFiles.write(keyFile, key)
        return key
    }
}

/** Whole files sealed by [SecureStorage.box] (Android Keystore AES-GCM). */
object SealedFiles {
    /** Writes [plain] sealed, via a temp file so a crash never leaves a torn file. */
    fun write(file: File, plain: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(SecureStorage.box.seal(plain))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("cannot write ${file.name}")
        }
    }

    /** The plain contents, or null when the file is missing, unsealed or undecryptable. */
    fun read(file: File): ByteArray? = runCatching { SecureStorage.box.open(file.readBytes()) }.getOrNull()
}
