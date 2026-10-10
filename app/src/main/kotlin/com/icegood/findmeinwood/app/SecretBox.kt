package com.icegood.findmeinwood.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts app secrets at rest (device identity, network keys, owner secrets).
 *
 * Sealed layout: "FMS1" | iv(12) | AES-GCM ciphertext+tag. The magic lets readers tell
 * sealed data from the plain files older builds wrote, which are migrated on first read.
 */
abstract class SecretBox {
    protected abstract fun key(): SecretKey

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key()) // the provider picks a random IV
        return MAGIC + c.iv + c.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        require(isSealed(sealed)) { "not sealed" }
        val c = Cipher.getInstance(TRANSFORMATION)
        val iv = sealed.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return c.doFinal(sealed, MAGIC.size + IV_BYTES, sealed.size - MAGIC.size - IV_BYTES)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val MAGIC = "FMS1".toByteArray()

        fun isSealed(bytes: ByteArray): Boolean =
            bytes.size > MAGIC.size + IV_BYTES && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }
}

/**
 * The AES key lives in the Android Keystore (hardware-backed where the device has it) and
 * cannot be exported, so copied app files are useless without this device. No user
 * authentication is required: the session service must read them in the background.
 */
object KeystoreBox : SecretBox() {
    private const val ALIAS = "fmiw_storage"

    @Volatile private var cached: SecretKey? = null

    override fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}

/** Which [SecretBox] the stores use; unit tests swap in a software key (no Keystore on the JVM). */
object SecureStorage {
    @Volatile internal var box: SecretBox = KeystoreBox
}
