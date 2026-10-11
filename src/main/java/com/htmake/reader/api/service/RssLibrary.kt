package com.htmake.reader.api.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.htmake.reader.api.service.advanced.*

class RssLibrary(private val files: UserDataFiles) {
    private fun identity(article: JsonObject): String {
        require(article.string("origin").length in 1..4096 && article.string("link").length in 1..4096) { "文章来源或链接无效" }
        return article.string("origin") + "\u0000" + article.string("link")
    }
    fun list(user: String, kind: String, group: String = ""): JsonArray {
        val name = filename(kind)
        val rows = files.read(user, name).asJsonArray.filter { it.isJsonObject && (group.isBlank() || it.asJsonObject.string("group", "默认分组") == group) }
        return advancedJson.toJsonTree(rows.sortedByDescending { it.asJsonObject.long(if (kind == "favorites") "starTime" else "readTime", it.asJsonObject.long("cachedAt")) }).asJsonArray
    }
    fun save(user: String, kind: String, input: JsonObject): JsonObject {
        val key = identity(input)
        require(input.string("content").length <= 24 * 1024 * 1024) { "文章正文过大" }
        val article = input.deepCopy().also {
            it.addProperty("group", input.string("group", "默认分组").take(100))
            it.addProperty(if (kind == "favorites") "starTime" else if (kind == "history") "readTime" else "cachedAt", System.currentTimeMillis())
            it.addProperty("title", input.string("title").take(1000))
        }
        files.update(user, filename(kind)) { saved ->
            val rows = saved.asJsonArray.filter { identity(it.asJsonObject) != key }.toMutableList()
            rows.add(0, article)
            advancedJson.toJsonTree(rows.take(if (kind == "favorites") 5000 else 1000))
        }
        return article
    }
    fun remove(user: String, kind: String, input: JsonObject): Int {
        var count = 0
        files.update(user, filename(kind)) { saved ->
            advancedJson.toJsonTree(saved.asJsonArray.filter { entry ->
                val matches = input.flag("all") || identity(entry.asJsonObject) == identity(input)
                if (matches) count++
                !matches
            })
        }
        return count
    }
    fun cached(user: String, input: JsonObject): JsonObject? {
        val key = identity(input)
        return files.read(user, "rssCache.json").asJsonArray.firstOrNull { identity(it.asJsonObject) == key }?.asJsonObject
    }
    private fun filename(kind: String): String = when (kind) {
        "favorites" -> "rssStar.json"
        "history" -> "rssHistory.json"
        "offline" -> "rssCache.json"
        else -> throw IllegalArgumentException("文章列表类型无效")
    }
}
