package app.findmeinwood.core.crypto

import app.findmeinwood.core.model.NetworkId
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object Identity {
    // X.509 SPKI header for raw Ed25519 public keys (12 bytes) + 32-byte key
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun memberIdOf(pair: KeyPair): ByteArray {
        val encoded = pair.public.encoded
        require(encoded.size == 44) { "unexpected Ed25519 SPKI length ${encoded.size}" }
        return encoded.copyOfRange(12, 44)
    }

    fun sign(pair: KeyPair, data: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519")
        s.initSign(pair.private)
        s.update(data)
        return s.sign()
    }

    fun verify(memberId: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        require(memberId.size == 32) { "memberId must be 32 bytes" }
        val spki = ED25519_SPKI_PREFIX + memberId
        val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(pub)
        verifier.update(data)
        verifier.verify(signature)
    } catch (_: Exception) {
        false
    }
}

data class NetworkKeys(
    val networkId: NetworkId,
    val trafficKey: ByteArray,
    val verifyTag: ByteArray,
)

object NetworkKeysFactory {
    private val NET_ID = "net-id".toByteArray()
    private val AEAD = "aead".toByteArray()
    private val VERIFY = "verify".toByteArray()

    fun derive(secret: ByteArray): NetworkKeys = NetworkKeys(
        networkId = NetworkId(Hkdf.derive(secret, ByteArray(32), NET_ID, 8)),
        trafficKey = Hkdf.derive(secret, ByteArray(32), AEAD, 32),
        verifyTag = Hkdf.derive(secret, ByteArray(32), VERIFY, 4),
    )
}
