package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.storage.SecureStorage
import com.icegood.findmeinwood.core.storage.SealedFiles
import android.content.Context
import com.icegood.findmeinwood.core.crypto.EpochKeys
import com.icegood.findmeinwood.core.crypto.Identity
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Device identity used to sign beacons and management frames (NFR-3).
 *
 * Persisted in app-private storage, sealed by [SecureStorage.box] (Android Keystore): peers
 * pin this key on first sight (TOFU), so a key regenerated on every process start made
 * running peers reject all of our beacons as "key changed" until they restarted too.
 * A plain file from an older build is read once and rewritten sealed.
 */
object IdentityHolder {
    private const val FILE = "device_identity.key"

    private const val BOX_FILE = "device_box.key"

    @Volatile private var cached: KeyPair? = null
    @Volatile private var cachedBox: KeyPair? = null

    fun keyPair(ctx: Context): KeyPair =
        cached ?: synchronized(this) {
            cached ?: loadOrCreate(File(ctx.filesDir, FILE)).also { cached = it }
        }

    /**
     * Key-agreement pair (EC P-256) published on this phone's member cards: a new network
     * key after a removal is sealed to it. Kept like the identity, so a sealed key still
     * opens after a restart.
     */
    fun boxPair(ctx: Context): KeyPair =
        cachedBox ?: synchronized(this) {
            cachedBox ?: loadOrCreate(File(ctx.filesDir, BOX_FILE), EpochKeys::boxKeyPair) { true }.also { cachedBox = it }
        }

    /** Reads the key pair from [file], or generates and stores a new one if it is missing or unreadable. */
    internal fun loadOrCreate(
        file: File,
        generate: () -> KeyPair = Identity::generate,
        // A stored key this platform cannot sign with is useless: peers drop unsigned frames.
        usable: (KeyPair) -> Boolean = Identity::canSign,
    ): KeyPair {
        runCatching { read(file) }.getOrNull()?.takeIf(usable)?.let { return it }
        val pair = generate()
        runCatching { write(file, pair) }
        return pair
    }

    /** The stored pair; only Keystore-sealed files are accepted. */
    private fun read(file: File): KeyPair {
        val text = SecureStorage.box.open(file.readBytes()).decodeToString()
        val (algorithm, priv, pub) = text.lines().also { require(it.size >= 3) }
        val dec = Base64.getDecoder()
        val kf = KeyFactory.getInstance(algorithm)
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(dec.decode(pub))),
            kf.generatePrivate(PKCS8EncodedKeySpec(dec.decode(priv))),
        )
    }

    private fun write(file: File, pair: KeyPair) {
        val enc = Base64.getEncoder()
        val text = listOf(
            pair.private.algorithm,
            enc.encodeToString(pair.private.encoded),
            enc.encodeToString(pair.public.encoded),
        ).joinToString("\n")
        SealedFiles.write(file, text.toByteArray())
    }
}
