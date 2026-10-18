package com.icegood.findmeinwood.core.crypto

import java.security.KeyPair
import java.security.SecureRandom

/**
 * Network keys per epoch. Epoch 0 is the key every member derives from the password;
 * each later epoch re-salts it: HKDF(base key, salt). Only members the salt was sealed to
 * ([seal], to their box key) can follow, so removing a member locks it out of every radio
 * — the Bluetooth members code and the Wi-Fi Direct group come from the traffic key too.
 */
object EpochKeys {
    private val INFO = "fmiw-epoch-aead".toByteArray()
    private val WRAP = "fmiw-rekey".toByteArray()
    const val SALT_LEN = 32

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }

    /** The traffic key of an epoch; no salt = epoch 0, the base key itself. */
    fun trafficKey(base: ByteArray, salt: ByteArray?): ByteArray =
        if (salt == null) base else Hkdf.derive(base, salt, INFO, 32)

    /** A box key pair (EC P-256: every device can agree on it). */
    fun boxKeyPair(): KeyPair = JoinCrypto.ephemeralKeyPair()

    /** Seals [salt] to [memberBox] with the master's one-off [eph]; [aad] binds network, epoch, member. */
    fun seal(salt: ByteArray, eph: KeyPair, memberBox: ByteArray, aad: ByteArray): ByteArray =
        AesGcmAead.seal(wrapKey(JoinCrypto.sharedSecret(eph, memberBox)), salt, aad)

    /** Opens a salt sealed to [box]; null when it was not sealed to this key. */
    fun open(ct: ByteArray, box: KeyPair, eph: ByteArray, aad: ByteArray): ByteArray? = runCatching {
        AesGcmAead.open(wrapKey(JoinCrypto.sharedSecret(box, eph)), ct, aad)
    }.getOrNull()?.takeIf { it.size == SALT_LEN }

    private fun wrapKey(shared: ByteArray) = Hkdf.derive(shared, ByteArray(32), WRAP, 32)
}
