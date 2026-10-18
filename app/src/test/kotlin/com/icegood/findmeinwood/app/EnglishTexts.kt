package com.icegood.findmeinwood.app

import android.content.Context
import org.mockito.Mockito
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A Context whose getString reads the app's English res/values/strings.xml, so text helpers
 * are tested on the plain JVM (coverage cannot see Robolectric runs) with their real wording.
 */
internal object EnglishTexts {
    private val byName: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to unescape(n.textContent)
        }
    }

    private val byId: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private fun unescape(s: String) =
        s.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"").replace("\\@", "@").replace("\\?", "?")

    fun text(id: Int, vararg args: Any?): String {
        val raw = byName.getValue(byId.getValue(id))
        return if (args.isEmpty()) raw else String.format(java.util.Locale.US, raw, *args)
    }

    val context: Context by lazy {
        Mockito.mock(Context::class.java) { inv ->
            if (inv.method.name == "getString") {
                val id = inv.arguments[0] as Int
                @Suppress("UNCHECKED_CAST")
                val rest = inv.arguments.drop(1).flatMap { a -> (a as? Array<Any?>)?.toList() ?: listOf(a) }
                text(id, *rest.toTypedArray())
            } else {
                Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
    }
}
