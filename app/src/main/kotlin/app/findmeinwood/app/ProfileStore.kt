package app.findmeinwood.app

import android.content.Context
import app.findmeinwood.core.crypto.NetworkKeysFactory
import app.findmeinwood.core.crypto.Identity
import app.findmeinwood.core.model.JoinPolicy
import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkId
import app.findmeinwood.core.model.NetworkProfile
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
    fun create(name: String, passphrase: String?): Pair<StoredProfile, String> {
        val secret: String = passphrase?.takeIf { it.isNotEmpty() }
            ?: (0 until 4).joinToString("") { randomWord() }
        val keys = NetworkKeysFactory.derive(secret.toByteArray())
        val memberId = Identity.memberIdOf(Identity.generate())
        val sp = StoredProfile(
            name = name,
            networkIdHex = keys.networkId.bytes.hex(),
            trafficKeyHex = keys.trafficKey.hex(),
            vtagHex = keys.verifyTag.hex(),
            myMemberIdHex = memberId.hex(),
            ownerMemberIdHex = memberId.hex(),
            policy = JoinPolicy.PRIVATE,
        )
        return sp to "$name#${secret}#${keys.verifyTag.hex()}"
    }

    private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    private fun randomWord(): String = (1..5).map { alphabet.random() }.joinToString("")

    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
