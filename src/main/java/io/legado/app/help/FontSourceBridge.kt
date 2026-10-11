package io.legado.app.help

import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.isAbsUrl
import java.security.MessageDigest

/** FU font helpers share the existing authenticated HTTP and private file bridges. */
object FontSourceBridge {
    private val fonts = object : LinkedHashMap<Pair<String, String>, QueryTTF>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, QueryTTF>?) = size > 4
    }

    fun query(bridge: JsExtensions, data: Any?, useCache: Boolean): QueryTTF? {
        if (data !is String && data !is ByteArray) return null
        val namespace = bridge.getSource()?.getStorageNamespace() ?: "default"
        val input = if (data is ByteArray) data else (data as String).toByteArray(Charsets.UTF_8)
        val key = namespace to MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (useCache) synchronized(fonts) { fonts[key] }?.let { return it }

        val bytes = when (data) {
            is ByteArray -> data
            is String -> when {
                data.isBlank() -> return null
                data.isAbsUrl() -> AnalyzeUrl(data, source = bridge.getSource()).getByteArray()
                data.startsWith("data:", true) -> bridge.base64DecodeToByteArray(data.substringAfter(','))
                // getFile enforces user/source ownership and rejects cache traversal.
                runCatching { bridge.getFile(data).isFile }.getOrDefault(false) -> bridge.readFile(data)
                else -> bridge.base64DecodeToByteArray(data)
            }
            else -> null
        } ?: return null
        val font = QueryTTF(bytes)
        if (useCache) synchronized(fonts) { fonts[key] = font }
        return font
    }

    fun replace(text: String, errorFont: QueryTTF?, correctFont: QueryTTF?, filter: Boolean): String {
        if (errorFont == null || correctFont == null) return text
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val oldCode = text.codePointAt(index)
            index += Character.charCount(oldCode)
            if (errorFont.isBlankUnicode(oldCode)) {
                result.appendCodePoint(oldCode)
                continue
            }
            val glyph = if (errorFont.getGlyfIdByUnicode(oldCode) == 0) null else errorFont.getGlyfByUnicode(oldCode)
            if (filter && glyph == null) continue
            val newCode = correctFont.getUnicodeByGlyf(glyph)
            result.appendCodePoint(if (newCode == 0) oldCode else newCode)
        }
        return result.toString()
    }
}
