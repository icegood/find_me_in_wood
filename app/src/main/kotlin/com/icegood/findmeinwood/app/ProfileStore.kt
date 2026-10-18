package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.storage.SecretBox
import com.icegood.findmeinwood.core.storage.SecureStorage
import android.content.Context
import com.icegood.findmeinwood.core.crypto.NetworkKeysFactory
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
    val policy: JoinPolicy,
    /**
     * Who manages the member list: [MASTER_ME] when this phone created the network, the
     * master's member id (hex) when let in by it, empty until learned (joined by password).
     */
    val masterIdHex: String = "",
)

const val MASTER_ME = "me"

sealed interface CreateResult {
    data class Ok(val profile: StoredProfile, val password: String) : CreateResult
    /** [message] is a string resource; [arg] fills its placeholder. */
    data class Error(@androidx.annotation.StringRes val message: Int, val arg: String? = null) : CreateResult {
        fun text(ctx: android.content.Context): String = ctx.getString(message, arg)
    }
}

sealed interface JoinResult {
    data class Ok(val profile: StoredProfile) : JoinResult
    data class Error(@androidx.annotation.StringRes val message: Int, val arg: String? = null) : JoinResult {
        fun text(ctx: android.content.Context): String = ctx.getString(message, arg)
    }
}

/**
 * JSON-file persistence (v0.1; T3.5 Room migration tracked in tasks). The file holds every
 * network's traffic key, so it is sealed by [SecureStorage.box] (Android Keystore); an
 * unsealed or undecryptable file reads as empty.
 */
object ProfileStore {
    // Unknown keys: profiles written by older builds (with per-network member ids) still load.
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private fun file(ctx: Context) = dir(ctx).resolve("profiles.json")
    internal fun dir(ctx: Context) = ctx.filesDir.apply { mkdirs() }

    // Dir-based core ops (pure JVM-testable); ctx overloads are thin delegates.
    internal fun load(dir: File): List<StoredProfile> = try {
        val text = SecureStorage.box.open(dir.resolve("profiles.json").readBytes()).decodeToString()
        json.decodeFromString(ListSerializer(StoredProfile.serializer()), text)
    } catch (_: Exception) {
        emptyList()
    }

    internal fun save(dir: File, profiles: List<StoredProfile>) {
        val text = json.encodeToString(ListSerializer(StoredProfile.serializer()), profiles)
        dir.resolve("profiles.json").writeBytes(SecureStorage.box.seal(text.toByteArray()))
    }

    internal fun add(dir: File, p: StoredProfile) =
        save(dir, load(dir).filterNot { it.networkIdHex == p.networkIdHex } + p)

    internal fun remove(dir: File, networkIdHex: String) =
        save(dir, load(dir).filterNot { it.networkIdHex == networkIdHex })

    fun load(ctx: Context): List<StoredProfile> = load(dir(ctx))
    fun save(ctx: Context, profiles: List<StoredProfile>) = save(dir(ctx), profiles)
    fun add(ctx: Context, p: StoredProfile) = add(dir(ctx), p)
    fun remove(ctx: Context, networkIdHex: String) = remove(dir(ctx), networkIdHex)

    /** Records the master of a network once known. */
    internal fun setMaster(dir: File, networkIdHex: String, masterIdHex: String) =
        save(dir, load(dir).map { if (it.networkIdHex == networkIdHex) it.copy(masterIdHex = masterIdHex) else it })

    fun setMaster(ctx: Context, networkIdHex: String, masterIdHex: String) = setMaster(dir(ctx), networkIdHex, masterIdHex)

    /** [me]: this phone's member id (its device identity); [master]: null while unknown. */
    fun toProfile(p: StoredProfile, me: MemberId, master: MemberId?): NetworkProfile = NetworkProfile(
        name = p.name,
        networkId = NetworkId(p.networkIdHex.hex()),
        trafficKey = p.trafficKeyHex.hex(),
        myMemberId = me,
        ownerMemberId = master ?: MemberId(ByteArray(32)),
        policy = p.policy,
    )

    /** FR-1.1: derive keys from the password, generate a per-network identity. */
    fun create(name: String, password: String?): CreateResult {
        if (name.length !in 1..64) {
            return CreateResult.Error(R.string.err_name_length)
        }
        val supplied = password?.trim()?.takeIf { it.isNotEmpty() }
        // FR-2.3: user passwords must be strong; generated ones are >=128-bit.
        if (supplied != null && !strongEnough(supplied)) {
            return CreateResult.Error(R.string.err_password_weak)
        }
        val secret = supplied ?: generateSecret()
        return CreateResult.Ok(storedFor(name, secret).copy(masterIdHex = MASTER_ME), secret)
    }

    /**
     * FR-2.1: join with just the network password. The network id is derived from the
     * password, so when the network was heard on the radio ([expected]) a wrong password
     * is caught right here, before any traffic; [name] is only a local label.
     */
    fun join(name: String, password: String, expected: NetworkId? = null): JoinResult {
        if (name.length !in 1..64) return JoinResult.Error(R.string.err_name_length)
        if (password.isEmpty()) return JoinResult.Error(R.string.err_enter_password)
        if (expected != null && networkIdOf(password) != expected) {
            return JoinResult.Error(R.string.err_wrong_password, name)
        }
        return JoinResult.Ok(storedFor(name, password))
    }

    /** The network a password belongs to (the id is derived from it). */
    fun networkIdOf(password: String): NetworkId = NetworkKeysFactory.derive(password.toByteArray()).networkId

    /** FR-2.3: >=6 words or >=16 characters. */
    internal fun strongEnough(secret: String): Boolean {
        val words = secret.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.size >= 6 || secret.length >= 16
    }

    private fun storedFor(name: String, secret: String): StoredProfile {
        val keys = NetworkKeysFactory.derive(secret.toByteArray())
        return StoredProfile(
            name = name,
            networkIdHex = keys.networkId.bytes.hex(),
            trafficKeyHex = keys.trafficKey.hex(),
            vtagHex = keys.verifyTag.hex(),
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
