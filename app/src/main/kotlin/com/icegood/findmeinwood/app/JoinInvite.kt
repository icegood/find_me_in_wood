package com.icegood.findmeinwood.app

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * FR-2.6: what an invite QR code holds, a `fmiw://join?n=<name>&p=<password>` link. The
 * phone's own camera app offers to open it in this app; the app's scanner reads it directly.
 */
data class JoinInvite(val name: String, val password: String) {
    fun toUri(): String = "$PREFIX?n=${enc(name)}&p=${enc(password)}"

    companion object {
        const val SCHEME = "fmiw"
        const val HOST = "join"
        private const val PREFIX = "$SCHEME://$HOST"

        /** The invite in [text], or null when it is something else (another QR code). */
        fun parse(text: String?): JoinInvite? {
            val t = text?.trim() ?: return null
            if (!t.startsWith("$PREFIX?")) return null
            val q = t.removePrefix("$PREFIX?").split('&').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to runCatching { dec(kv.substring(i + 1)) }.getOrNull()
            }.toMap()
            val password = q["p"]?.takeIf { it.isNotEmpty() } ?: return null
            return JoinInvite(q["n"]?.trim().orEmpty().ifEmpty { "network" }, password)
        }

        // %20 rather than "+" for spaces: some camera apps decode only percent-escapes.
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
