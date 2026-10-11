package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

class SourceInfoMap(private val source: BookSource) : LinkedHashMap<String, String>() {
    init { source.getScriptCache().get("exploreInfoMap")?.let { GSON.fromJsonObject<Map<String, String>>(it).getOrNull()?.let(::putAll) } }
    @JvmOverloads fun save(time: Int = 0, need: Boolean = true) {
        if (need) source.getScriptCache().put("exploreInfoMap", GSON.toJson(this), time)
    }
    fun saveNow() = save()
    fun get(): Map<String, String> = this
    fun set(values: Map<String, String>) { clear(); putAll(values) }
}
