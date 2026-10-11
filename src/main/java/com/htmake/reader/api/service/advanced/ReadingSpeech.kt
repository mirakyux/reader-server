package com.htmake.reader.api.service.advanced

import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class ReadingSpeech(private val store: AdvancedStore, private val http: AdvancedHttp) {
    private val locks = ConcurrentHashMap<Path, Mutex>()
    fun audio(user: String, id: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "音频标识无效" }
        return store.directory(user).resolve("audio").resolve("$id.mp3")
    }
    suspend fun synthesize(user: String, request: JsonObject, fallback: String = ""): JsonObject {
        val engine = store.provider(user, request.string("engineId"), true)
        val speed = request.get("speed")?.asDouble ?: 1.0
        require(speed.isFinite() && speed in 0.25..4.0) { "语速须在 0.25 至 4 之间" }
        val segments = mutableListOf<JsonObject>()
        val roles = engine.getAsJsonObject("roleVoices") ?: JsonObject()
        if (request.has("segments")) {
            require(request.array("segments").size() in 1..40) { "段落数量须在 1 至 40 之间" }
            request.array("segments").forEach { value ->
                val segment = value.asJsonObject
                val role = segment.string("role", "旁白").take(40)
                segments.add(jsonObject("text" to segment.string("text"), "role" to role,
                    "voice" to segment.string("voice", roles.string(role, engine.string("voice")))))
            }
        } else {
            val text = request.string("text", fallback)
            require(text.length in 1..20000) { "听书正文须在 1 至 20000 字之间" }
            for (line in text.lines().filter { it.isNotBlank() }) {
                val speaker = Regex("^([^：:]{1,40})[：:](.+)$").matchEntire(line.trim())
                val role = speaker?.groupValues?.get(1)?.takeIf { roles.has(it) } ?: "旁白"
                val sentence = if (role == "旁白") line.trim() else speaker!!.groupValues[2].trim()
                sentence.chunked(800).forEach { segment -> segments.add(jsonObject("text" to segment, "role" to role, "voice" to roles.string(role, engine.string("voice")))) }
            }
        }
        require(segments.size in 1..40 && segments.sumOf { it.string("text").length } <= 20000) { "听书内容或分段过多" }
        for (segment in segments) {
            require(segment.string("text").length in 1..4000 && segment.string("voice").length in 1..200) { "听书段落或声线错误" }
            currentCoroutineContext().ensureActive()
            val id = digest(advancedJson.toJson(jsonObject("engine" to engine.string("id"), "base" to engine.string("baseUrl"),
                "model" to engine.string("model"), "voice" to segment.string("voice"), "text" to segment.string("text"), "speed" to speed)))
            val path = audio(user, id)
            val cached = locks.computeIfAbsent(path) { Mutex() }.withLock {
                if (Files.isRegularFile(path) && Files.size(path) > 0) true else {
                    val bytes = http.speech(engine, jsonObject("model" to engine.string("model"), "voice" to segment.string("voice"),
                        "input" to segment.string("text"), "speed" to speed, "response_format" to "mp3"))
                    currentCoroutineContext().ensureActive()
                    AdvancedStore.atomicWrite(path, bytes)
                    false
                }
            }
            segment.addProperty("id", id); segment.addProperty("audioUrl", "/reader3/tts/audio/$id"); segment.addProperty("cached", cached)
        }
        return jsonObject("engineId" to engine.string("id"), "segments" to segments)
    }
}
