package com.icegood.findmeinwood.app

import android.content.Context
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

    @Volatile private var cached: KeyPair? = null

    fun keyPair(ctx: Context): KeyPair =
        cached ?: synchronized(this) {
            cached ?: loadOrCreate(File(ctx.filesDir, FILE)).also { cached = it }
        }

    /** Reads the key pair from [file], or generates and stores a new one if it is missing or unreadable. */
    internal fun loadOrCreate(file: File): KeyPair {
        // A stored key this platform cannot sign with is useless: peers drop unsigned frames.
        runCatching { read(file) }.getOrNull()?.takeIf { Identity.canSign(it) }?.let { return it }
        val pair = Identity.generate()
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
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = listOf(
            pair.private.algorithm,
            enc.encodeToString(pair.private.encoded),
            enc.encodeToString(pair.public.encoded),
        ).joinToString("\n")
        tmp.writeBytes(SecureStorage.box.seal(text.toByteArray()))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("cannot store identity")
        }
    }
}
