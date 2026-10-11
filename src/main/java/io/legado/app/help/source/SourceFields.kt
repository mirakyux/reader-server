package io.legado.app.help.source

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.google.gson.JsonObject
import io.legado.app.utils.GSON

/** Preserve future source fields while keeping the server's typed rules editable. */
open class SourceFields {
    @field:JsonIgnore
    private var _extensions: MutableMap<String, Any?>? = linkedMapOf()

    private fun fields(): MutableMap<String, Any?> = _extensions ?: linkedMapOf<String, Any?>().also { _extensions = it }

    @JsonAnyGetter
    fun extraFields(): Map<String, Any?> = _extensions.orEmpty()

    @JsonAnySetter
    fun extraField(name: String, value: Any?) { fields()[name] = value }

    fun preserveFields(json: JsonObject) {
        val known = javaClass.declaredFields.map { it.name }.toSet()
        json.get("_extensions")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.filter { it.key !in known }?.forEach { (name, value) ->
            fields()[name] = GSON.fromJson(value, Any::class.java)
        }
        json.entrySet().filter { it.key !in known && it.key != "_extensions" }.forEach { (name, value) ->
            fields()[name] = GSON.fromJson(value, Any::class.java)
        }
    }
}
