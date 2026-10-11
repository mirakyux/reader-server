package com.htmake.reader.api.service

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.htmake.reader.api.service.advanced.AdvancedStore
import com.htmake.reader.api.service.advanced.advancedJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Native FU entity arrays, stored in the existing authenticated user's data directory. */
class UserDataFiles(private val root: Path) {
    fun directory(user: String): Path {
        require(user.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "用户空间格式错误" }
        return root.resolve(user)
    }
    fun path(user: String, name: String): Path {
        require(name.matches(Regex("[A-Za-z0-9_-]+\\.json")))
        return directory(user).resolve(name)
    }
    fun read(user: String, name: String): JsonElement = synchronized(lock(user, name)) {
        val path = path(user, name)
        if (Files.exists(path)) JsonParser.parseString(Files.readString(path)) else JsonArray()
    }
    fun write(user: String, name: String, value: JsonElement) = synchronized(lock(user, name)) {
        AdvancedStore.atomicWrite(path(user, name), advancedJson.toJson(value).toByteArray())
    }
    fun update(user: String, name: String, change: (JsonElement) -> JsonElement): JsonElement = synchronized(lock(user, name)) {
        change(read(user, name)).also { write(user, name, it) }
    }
    fun users(): List<String> = if (!Files.isDirectory(root)) emptyList() else Files.list(root).use { paths ->
        paths.filter { Files.isDirectory(it) && it.fileName.toString().matches(Regex("[A-Za-z0-9_-]{1,100}")) }.map { it.fileName.toString() }.toList()
    }
    private fun lock(user: String, name: String): Any = lockFor(path(user, name))
    companion object {
        private val locks = ConcurrentHashMap<Path, Any>()
        /** Shared with the legacy storage API so all writers participate in the same transaction. */
        fun lockFor(path: Path): Any = locks.computeIfAbsent(path.toFile().canonicalFile.toPath()) { Any() }
    }
}
