package app.findmeinwood.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {
    private const val HASH_LEN = 32

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen <= 255 * HASH_LEN) { "HKDF output too long" }
        val out = ByteArray(outLen)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < outLen) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(HASH_LEN, outLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray =
        expand(extract(salt, ikm), info, outLen)
}
