package com.icegood.findmeinwood.app

import android.content.Context
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
import com.icegood.findmeinwood.core.crypto.Identity
import com.icegood.findmeinwood.core.model.JoinPolicy
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.NetworkProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredProfile(
    val name: String,
    val networkIdHex: String,
    val trafficKeyHex: String,
    val vtagHex: String,
    val myMemberIdHex: String,
    val ownerMemberIdHex: String,
    val policy: JoinPolicy,
)

sealed interface CreateResult {
    data class Ok(val profile: StoredProfile, val joinCode: String) : CreateResult
    data class Error(val message: String) : CreateResult
}

sealed interface JoinResult {
    data class Ok(val profile: StoredProfile) : JoinResult
    data class Error(val message: String) : JoinResult
}

/** JSON-file persistence (v0.1; T3.5 Room migration tracked in tasks). */
object ProfileStore {
    private val json = Json { prettyPrint = true }
    private fun file(ctx: Context) = dir(ctx).resolve("profiles.json")
    internal fun dir(ctx: Context) = ctx.filesDir.apply { mkdirs() }

    // Dir-based core ops (pure JVM-testable); ctx overloads are thin delegates.
    internal fun load(dir: File): List<StoredProfile> = try {
        json.decodeFromString(
            ListSerializer(StoredProfile.serializer()), dir.resolve("profiles.json").readText(),
        )
    } catch (_: Exception) {
        emptyList()
    }

    internal fun save(dir: File, profiles: List<StoredProfile>) {
        dir.resolve("profiles.json").writeText(
            json.encodeToString(ListSerializer(StoredProfile.serializer()), profiles),
        )
    }

    internal fun add(dir: File, p: StoredProfile) =
        save(dir, load(dir).filterNot { it.networkIdHex == p.networkIdHex } + p)

    internal fun remove(dir: File, networkIdHex: String) =
        save(dir, load(dir).filterNot { it.networkIdHex == networkIdHex })

    fun load(ctx: Context): List<StoredProfile> = load(dir(ctx))
    fun save(ctx: Context, profiles: List<StoredProfile>) = save(dir(ctx), profiles)
    fun add(ctx: Context, p: StoredProfile) = add(dir(ctx), p)
    fun remove(ctx: Context, networkIdHex: String) = remove(dir(ctx), networkIdHex)

    fun toProfile(p: StoredProfile): NetworkProfile = NetworkProfile(
        name = p.name,
        networkId = NetworkId(p.networkIdHex.hex()),
        trafficKey = p.trafficKeyHex.hex(),
        myMemberId = MemberId(p.myMemberIdHex.hex()),
        ownerMemberId = MemberId(p.ownerMemberIdHex.hex()),
        policy = p.policy,
    )

    /** FR-1.1: derive keys from secret, generate per-network identity. */
    fun create(name: String, passphrase: String?): CreateResult {
        if (name.length !in 1..64) {
            return CreateResult.Error("Name must be 1–64 characters")
        }
        val supplied = passphrase?.takeIf { it.isNotEmpty() }
        // FR-2.3: user passphrases must be strong; generated ones are >=128-bit.
        if (supplied != null && !strongEnough(supplied)) {
            return CreateResult.Error("Passphrase too weak: use 16+ characters or 6+ words")
        }
        val secret = supplied ?: generateSecret()
        return CreateResult.Ok(storedFor(name, secret), "$name#$secret#${verifyTagOf(secret).hex()}")
    }

    /**
     * FR-2.1/FR-2.3: join from a `name#secret#vtag` string. The verification tag is
     * checked offline, so a wrong secret fails before any radio traffic.
     */
    fun join(code: String): JoinResult {
        val parts = code.trim().split("#")
        if (parts.size != 3) return JoinResult.Error("Join code must look like name#secret#vtag")
        val (name, secret, vtag) = parts
        if (name.length !in 1..64) return JoinResult.Error("Name must be 1–64 characters")
        if (vtag.length != 8 || vtag.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return JoinResult.Error("Join code has a malformed verification tag")
        }
        if (!verifyTagOf(secret).hex().equals(vtag, ignoreCase = true)) {
            return JoinResult.Error("Wrong secret for this network (verification tag mismatch)")
        }
        return JoinResult.Ok(storedFor(name, secret))
    }

    /** Rebuilds the join code for a secret learned through the on-radio handshake. */
    fun joinCodeOf(name: String, secret: String): String = "$name#$secret#${verifyTagOf(secret).hex()}"

    /** FR-2.3: >=6 words or >=16 characters. */
    internal fun strongEnough(secret: String): Boolean {
        val words = secret.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.size >= 6 || secret.length >= 16
    }

    private fun verifyTagOf(secret: String) = NetworkKeysFactory.derive(secret.toByteArray()).verifyTag

    private fun storedFor(name: String, secret: String): StoredProfile {
        val keys = NetworkKeysFactory.derive(secret.toByteArray())
        val memberId = Identity.memberIdOf(Identity.generate())
        return StoredProfile(
            name = name,
            networkIdHex = keys.networkId.bytes.hex(),
            trafficKeyHex = keys.trafficKey.hex(),
            vtagHex = keys.verifyTag.hex(),
            myMemberIdHex = memberId.hex(),
            // Owner is advertised in HELLO (US-8); until then the joiner is its own owner.
            ownerMemberIdHex = memberId.hex(),
            policy = JoinPolicy.PRIVATE,
        )
    }

    private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = java.security.SecureRandom()

    /** 26 chars from a 32-symbol alphabet = 130 bits (FR-2.3 requires >=128). */
    internal fun generateSecret(): String =
        (0 until 26).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
