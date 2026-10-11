package com.htmake.reader.api.service.advanced

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** OpenAI-compatible Chat Completions, Models and Speech; no SDK or real key is required. */
class AdvancedHttp {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    private fun request(provider: JsonObject, path: String, body: JsonObject?): Request {
        val builder = Request.Builder().url(provider.string("baseUrl").trimEnd('/') + path)
        val secret = provider.string("apiKey")
        if (secret.isNotBlank()) builder.header("Authorization", "Bearer $secret")
        if (body == null) builder.get() else builder.post(advancedJson.toJson(body).toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    private suspend fun <T> execute(provider: JsonObject, path: String, body: JsonObject?, consume: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request(provider, path, body))
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        require(response.isSuccessful) { "服务请求失败：HTTP ${response.code}" }
                        consume(response)
                    }
                } catch (error: Exception) {
                    // Cancelling a blocked read closes its socket; preserve cancellation rather than the resulting IOException.
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }
        } finally { cancellation.cancel() }
    }

    suspend fun models(provider: JsonObject): List<Map<String, String>> = execute(provider, "/models", null) { response ->
        val result = JsonParser.parseString(response.body.string()).asJsonObject
        require(!result.has("error") && result.has("data") && result.get("data").isJsonArray) { "模型服务返回错误或无效列表" }
        result.array("data").map { mapOf("id" to it.asJsonObject.string("id")) }.filter { it["id"]!!.isNotBlank() }
    }

    suspend fun completion(provider: JsonObject, body: JsonObject): String = execute(provider, "/chat/completions", body) { response ->
        val result = JsonParser.parseString(response.body.string()).asJsonObject
        require(!result.has("error")) { "AI 服务返回错误" }
        val text = result.array("choices").firstOrNull()?.asJsonObject?.getAsJsonObject("message")?.string("content").orEmpty()
        require(text.isNotBlank()) { "AI 返回内容为空" }
        require(text.length <= 200000) { "AI 返回内容过长" }
        text
    }

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class StreamResult(val content: String, val calls: List<ToolCall>)
    suspend fun stream(provider: JsonObject, body: JsonObject, onDelta: suspend (String) -> Unit): StreamResult =
        execute(provider, "/chat/completions", body) { response ->
            require(response.header("Content-Type").orEmpty().startsWith("text/event-stream")) { "AI 服务未返回事件流" }
            val content = StringBuilder()
            val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
            var finished = false
            val reader = response.body.charStream().buffered()
            val data = StringBuilder()
            suspend fun event() {
                val value = data.toString().trim(); data.clear()
                if (value.isEmpty()) return
                if (value == "[DONE]") { finished = true; return }
                val packet = JsonParser.parseString(value).asJsonObject
                require(!packet.has("error")) { "AI 服务返回错误" }
                val choice = packet.array("choices").firstOrNull()?.asJsonObject ?: return
                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull) finished = true
                val delta = choice.getAsJsonObject("delta") ?: return
                val text = delta.string("content")
                if (text.isNotEmpty()) { content.append(text); require(content.length <= 100000) { "助手回复过长" }; onDelta(text) }
                delta.array("tool_calls").forEach { part ->
                    val item = part.asJsonObject
                    val index = item.long("index").toInt()
                    require(index in 0..15) { "工具调用过多" }
                    val call = calls.getOrPut(index) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                    call.first.append(item.string("id"))
                    val function = item.getAsJsonObject("function")
                    if (function != null) { call.second.append(function.string("name")); call.third.append(function.string("arguments")) }
                    require(call.third.length <= 16000) { "工具参数过长" }
                }
            }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) event() else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                    require(data.length <= 200000) { "AI 事件过长" }
                }
                if (finished && calls.isEmpty() && content.isNotEmpty()) break
            }
            if (data.isNotEmpty()) event()
            require(finished) { "AI 事件流意外中断" }
            require(content.isNotEmpty() || calls.isNotEmpty()) { "AI 返回内容为空" }
            StreamResult(content.toString(), calls.values.map { ToolCall(it.first.toString(), it.second.toString(), it.third.toString()) })
        }

    suspend fun speech(provider: JsonObject, body: JsonObject): ByteArray = execute(provider, "/audio/speech", body) { response ->
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        require(contentType.startsWith("audio/mpeg") || contentType.startsWith("audio/mp3") || contentType.startsWith("application/octet-stream")) { "语音服务未返回 MP3 音频" }
        require(response.body.contentLength() <= 20L * 1024 * 1024) { "音频文件过大" }
        val bytes = response.body.byteStream().readNBytes(20 * 1024 * 1024 + 1)
        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "音频为空或过大" }
        require((bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
            (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0)) { "语音服务返回的数据不是 MP3" }
        bytes
    }
}
