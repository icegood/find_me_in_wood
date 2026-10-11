package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.storage.SecretBox
import com.icegood.findmeinwood.core.storage.SecureStorage
import android.content.Context
import com.icegood.findmeinwood.core.model.ThemeMode
import java.util.Base64

object Prefs {
    private const val FILE = "prefs"
    private const val KEY_LORA_NODE = "lora_node"
    private const val KEY_CURRENT_UID = "current_uid"
    private const val KEY_CURRENT_NAME = "current_display_name"
    private const val KEY_MY_NAME = "my_display_name"
    private const val KEY_INTERVAL_MS = "beacon_interval_ms"
    private const val KEY_SECRET_PREFIX = "secret_"
    private const val KEY_UNVERIFIED_PREFIX = "join_code_"
    private const val KEY_TILE_SOURCE = "tile_source"
    private const val KEY_TILE_API_KEY_PREFIX = "tile_api_key_"
    private const val KEY_P2P_OWNER = "p2p_group_owner"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MY_PHONE = "my_phone_number"

    /** FR-4.2/FR-9.2: beacon + share cadence, 5–300 s, default 60 s. */
    const val MIN_INTERVAL_MS = 5_000L
    const val MAX_INTERVAL_MS = 300_000L
    const val DEFAULT_INTERVAL_MS = 60_000L

    fun intervalMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    fun setIntervalMs(ctx: Context, ms: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_INTERVAL_MS, ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)).apply()
    }

    fun loraNode(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LORA_NODE, null)

    fun setLoraNode(ctx: Context, address: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (address == null) remove(KEY_LORA_NODE) else putString(KEY_LORA_NODE, address)
        }.apply()
    }

    /**
     * FR-8.3: the owner needs the network secret to seal it for approved joiners. Kept in
     * app-private storage so a fresh session can answer join requests without the user
     * re-entering the code, sealed by [SecureStorage.box] (Android Keystore).
     */
    fun secretFor(ctx: Context, networkIdHex: String): String? = sealedString(ctx, KEY_SECRET_PREFIX + networkIdHex)

    fun setSecretFor(ctx: Context, networkIdHex: String, secret: String?) =
        putSealedString(ctx, KEY_SECRET_PREFIX + networkIdHex, secret)

    /**
     * Secrets in prefs are stored only sealed by the Android Keystore key (Base64 of the
     * [SecretBox] blob). Anything unsealed or undecryptable reads as absent.
     */
    private fun sealedString(ctx: Context, key: String): String? {
        val stored = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val raw = runCatching { Base64.getDecoder().decode(stored) }.getOrNull() ?: return null
        if (!SecretBox.isSealed(raw)) return null
        return runCatching { SecureStorage.box.open(raw).decodeToString() }.getOrNull()
    }

    private fun putSealedString(ctx: Context, key: String, value: String?) {
        val sealed = value?.let { Base64.getEncoder().encodeToString(SecureStorage.box.seal(it.toByteArray())) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (sealed == null) remove(key) else putString(key, sealed)
        }.apply()
    }

    /** FR-8.4: member joined without a confirmed SAS comparison. */
    /** FR-8.4: the 6-digit code of a password-less join still to be compared; null = done. */
    fun unverifiedCode(ctx: Context, networkIdHex: String): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_UNVERIFIED_PREFIX + networkIdHex, null)

    fun setUnverifiedCode(ctx: Context, networkIdHex: String, code: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (code == null) remove(KEY_UNVERIFIED_PREFIX + networkIdHex) else putString(KEY_UNVERIFIED_PREFIX + networkIdHex, code)
        }.apply()
    }

    /** FR-6.5: OFFLINE (default, NFR-2) / OSM / SATELLITE. */
    fun tileSource(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TILE_SOURCE, "OFFLINE") ?: "OFFLINE"

    fun setTileSource(ctx: Context, source: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TILE_SOURCE, source).apply()
    }

    /**
     * Tile API key for one concrete tile provider, identified by its [TileProvider.id]
     * (e.g. `USGS_SAT`, `Mapnik`, `CartoDB-DarkMatter`) rather than by the coarser
     * `TileSource`. This matters because OSM resolves to a different provider per theme,
     * and each provider has its own credentials. The key is appended to that provider's
     * download requests as `?token=<key>`; providers that need none never receive one.
     */
    fun mapTileApiKey(ctx: Context, providerId: String): String =
        sealedString(ctx, KEY_TILE_API_KEY_PREFIX + providerId).orEmpty()

    /** Provider credentials are secrets too: sealed like the network secrets. */
    fun setMapTileApiKey(ctx: Context, providerId: String, key: String?) =
        putSealedString(ctx, KEY_TILE_API_KEY_PREFIX + providerId, key?.trim()?.ifBlank { null })

    /**
     * This phone's own number, offered to members as the LNP/2 SMS link. Android cannot
     * read it reliably, so the user types it once. Sealed (personal data).
     */
    fun myPhoneNumber(ctx: Context): String = sealedString(ctx, KEY_MY_PHONE).orEmpty()
    fun setMyPhoneNumber(ctx: Context, number: String) =
        putSealedString(ctx, KEY_MY_PHONE, SmsLogic.parseNumbers(number).firstOrNull())

    /** Wi-Fi Direct role: host the P2P group (server) instead of letting the system pick. */
    fun p2pGroupOwner(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_P2P_OWNER, false)

    fun setP2pGroupOwner(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_P2P_OWNER, value).apply()
    }

    /** Explicit light/dark selection; SYSTEM follows the device setting. */
    fun themeMode(ctx: Context): ThemeMode {
        val stored = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_THEME_MODE, null)
        return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun currentUid(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CURRENT_UID, null)

    fun setCurrentUid(ctx: Context, uid: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (uid == null) remove(KEY_CURRENT_UID) else putString(KEY_CURRENT_UID, uid)
        }.apply()
    }

    /**
     * The name members see: the one chosen in Settings, else the signed-in account's name.
     * Sent with link control (LNP/2), as the join nick and on chat messages.
     */
    fun currentDisplayName(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).let {
            it.getString(KEY_MY_NAME, null) ?: it.getString(KEY_CURRENT_NAME, null)
        }

    fun myName(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MY_NAME, null).orEmpty()

    /** Own name shown to members; blank falls back to the account name. */
    fun setMyName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            val n = name.trim().take(32)
            if (n.isEmpty()) remove(KEY_MY_NAME) else putString(KEY_MY_NAME, n)
        }.apply()
    }

    fun setCurrentDisplayName(ctx: Context, name: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (name.isNullOrBlank()) remove(KEY_CURRENT_NAME) else putString(KEY_CURRENT_NAME, name)
        }.apply()
    }
}
