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
 * Persisted in app-private storage: peers pin this key on first sight (TOFU), so a key
 * regenerated on every process start made running peers reject all of our beacons as
 * "key changed" until they restarted too. (T3.5 will move per-network identities to Room.)
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
        runCatching { read(file) }.getOrNull()?.let { return it }
        val pair = Identity.generate()
        runCatching { write(file, pair) }
        return pair
    }

    private fun read(file: File): KeyPair {
        val (algorithm, priv, pub) = file.readLines().also { require(it.size >= 3) }
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
        tmp.writeText(
            listOf(
                pair.private.algorithm,
                enc.encodeToString(pair.private.encoded),
                enc.encodeToString(pair.public.encoded),
            ).joinToString("\n"),
        )
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("cannot store identity")
        }
    }
}
