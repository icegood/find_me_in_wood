package app.findmeinwood.app

import android.content.Context

object Prefs {
    private const val FILE = "prefs"
    private const val KEY_LORA_NODE = "lora_node"

    fun loraNode(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LORA_NODE, null)

    fun setLoraNode(ctx: Context, address: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (address == null) remove(KEY_LORA_NODE) else putString(KEY_LORA_NODE, address)
        }.apply()
    }
}
