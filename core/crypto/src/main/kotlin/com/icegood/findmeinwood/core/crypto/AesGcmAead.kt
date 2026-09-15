package com.icegood.findmeinwood.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface Aead {
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(key: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
}

object AesGcmAead : Aead {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size > IV_LEN) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_LEN),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_LEN, ciphertext.size - IV_LEN)
    }
}
