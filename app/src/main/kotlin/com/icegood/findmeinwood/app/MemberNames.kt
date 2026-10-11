package com.icegood.findmeinwood.app

import android.content.Context
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.session.PeerState
import com.icegood.findmeinwood.core.storage.SealedFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Pure: a member's own announced name, overridden by the label given on this phone. */
internal object MemberNaming {
    fun key(id: MemberId): String = id.bytes.joinToString("") { "%02x".format(it) }

    fun apply(peers: Map<MemberId, PeerState>, labels: Map<String, String>): Map<MemberId, PeerState> =
        if (labels.isEmpty()) peers else peers.mapValues { (id, p) -> labels[key(id)]?.let { p.copy(nickname = it) } ?: p }

    /** New label map after renaming [id] to [name]; blank removes the label. */
    fun rename(labels: Map<String, String>, id: MemberId, name: String?): Map<String, String> {
        val n = name?.trim()?.take(32)?.ifBlank { null }
        return if (n == null) labels - key(id) else labels + (key(id) to n)
    }
}

/**
 * Labels this phone gives to members ("Mom", "Ira's phone"), like names in a contact list.
 * Local only, never sent; sealed because it is personal data.
 */
object MemberNames {
    private const val FILE = "member_names.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> = _labels
    @Volatile private var loaded = false

    fun load(ctx: Context) = synchronized(this) {
        if (loaded) return
        loaded = true
        val bytes = SealedFiles.read(File(ctx.filesDir, FILE)) ?: return
        _labels.value = runCatching { json.decodeFromString(serializer, bytes.decodeToString()) }.getOrDefault(emptyMap())
    }

    fun rename(ctx: Context, id: MemberId, name: String?) = synchronized(this) {
        load(ctx)
        _labels.value = MemberNaming.rename(_labels.value, id, name)
        runCatching {
            SealedFiles.write(File(ctx.filesDir, FILE), json.encodeToString(serializer, _labels.value).toByteArray())
        }
    }
}
