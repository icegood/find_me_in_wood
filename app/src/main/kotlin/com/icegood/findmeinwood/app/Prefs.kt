package com.icegood.findmeinwood.app

import android.content.Context

object Prefs {
    private const val FILE = "prefs"
    private const val KEY_LORA_NODE = "lora_node"
    private const val KEY_CURRENT_UID = "current_uid"
    private const val KEY_INTERVAL_MS = "beacon_interval_ms"
    private const val KEY_SECRET_PREFIX = "secret_"
    private const val KEY_UNVERIFIED_PREFIX = "unverified_"
    private const val KEY_TILE_SOURCE = "tile_source"
    private const val KEY_P2P_OWNER = "p2p_group_owner"

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
     * re-entering the code.
     */
    fun secretFor(ctx: Context, networkIdHex: String): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SECRET_PREFIX + networkIdHex, null)

    fun setSecretFor(ctx: Context, networkIdHex: String, secret: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (secret == null) remove(KEY_SECRET_PREFIX + networkIdHex) else putString(KEY_SECRET_PREFIX + networkIdHex, secret)
        }.apply()
    }

    /** FR-8.4: member joined without a confirmed SAS comparison. */
    fun unverified(ctx: Context, networkIdHex: String): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_UNVERIFIED_PREFIX + networkIdHex, false)

    fun setUnverified(ctx: Context, networkIdHex: String, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_UNVERIFIED_PREFIX + networkIdHex, value).apply()
    }

    /** FR-6.5: OFFLINE (default, NFR-2) / OSM / SATELLITE. */
    fun tileSource(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TILE_SOURCE, "OFFLINE") ?: "OFFLINE"

    fun setTileSource(ctx: Context, source: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TILE_SOURCE, source).apply()
    }

    /** Wi-Fi Direct role: host the P2P group (server) instead of letting the system pick. */
    fun p2pGroupOwner(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_P2P_OWNER, false)

    fun setP2pGroupOwner(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_P2P_OWNER, value).apply()
    }

    fun currentUid(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CURRENT_UID, null)

    fun setCurrentUid(ctx: Context, uid: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (uid == null) remove(KEY_CURRENT_UID) else putString(KEY_CURRENT_UID, uid)
        }.apply()
    }
}
