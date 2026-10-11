package com.htmake.reader.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.htmake.reader.api.service.advanced.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdvancedCapabilityTests {
    @TempDir lateinit var directory: Path

    @Test
    fun `provider secrets persist without echo or crossing users and Skills cannot expand whitelist`() {
        val store = AdvancedStore(directory)
        val config = store.saveConfig("alice", aiConfig("http://127.0.0.1:12345/v1", "secret=alice"))
        assertFalse(config.toString().contains("secret=alice"))
        assertTrue(config.array("providers")[0].asJsonObject.flag("apiKeyConfigured"))
        val replacement = aiConfig("http://127.0.0.1:12345/v1", null)
        store.saveConfig("alice", replacement)
        assertEquals("secret=alice", store.provider("alice").string("apiKey"))
        assertEquals(0, store.config("bob").array("providers").size())
        assertThrows(IllegalArgumentException::class.java) { store.provider("bob", "fixture") }
        store.saveConfig("alice", aiConfig("http://127.0.0.1:12345/v1", ""))
        assertFalse(store.config("alice").array("providers")[0].asJsonObject.flag("apiKeyConfigured"))
        assertThrows(IllegalArgumentException::class.java) { store.saveConfig("alice", aiConfig("http://secret@localhost/v1", "")) }
        assertThrows(IllegalArgumentException::class.java) { store.file("alice", "../bob.json") }
        assertThrows(IllegalArgumentException::class.java) { jsonObject("index" to 4294967296L).integer("index") }
        assertThrows(IllegalArgumentException::class.java) { jsonObject("index" to 1.5).integer("index") }
        val assistant = ReadingAssistant(store, AdvancedHttp(), FakeTools())
        val skill = jsonObject("version" to 1, "skills" to listOf(mapOf("id" to "fixture", "instructions" to "说明阅读统计", "tools" to listOf("reading_stats"))))
        assertEquals(1, assistant.saveSkills("alice", skill).array("skills").size())
        assertEquals(0, assistant.skills("bob").array("skills").size())
        val invalid = jsonObject("skills" to listOf(mapOf("id" to "escape", "instructions" to "执行", "tools" to listOf("execute_shell"))))
        assertThrows(IllegalArgumentException::class.java) { assistant.saveSkills("alice", invalid) }
    }

    @Test
    fun `AI models cleanup cache raw preservation and failed responses use local compatible service`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        MockProvider { path, body ->
            if (path == "/v1/models") Response("application/json", jsonObject("data" to listOf(mapOf("id" to "fixture-model"))).toString())
            else {
                calls.incrementAndGet()
                assertEquals("fixture-model", body.string("model"))
                assertEquals("原文广告\n正文", body.array("messages")[1].asJsonObject.string("content"))
                Response("application/json", jsonObject("choices" to listOf(mapOf("message" to mapOf("content" to "正文")))).toString())
            }
        }.use { server ->
            val store = AdvancedStore(directory)
            store.saveConfig("alice", aiConfig(server.base))
            val http = AdvancedHttp()
            assertEquals("fixture-model", http.models(store.provider("alice")).single()["id"])
            val assistant = ReadingAssistant(store, http, FakeTools())
            val input = jsonObject("bookUrl" to "fixture-book", "index" to 0)
            val first = assistant.clean("alice", input, "原文广告\n正文")
            assertEquals("正文", first.string("content")); assertEquals("原文广告\n正文", first.string("rawContent")); assertFalse(first.flag("cached"))
            assertTrue(assistant.clean("alice", input, "原文广告\n正文").flag("cached"))
            assertEquals(1, calls.get())
        }
        MockProvider { _, _ -> Response("application/json", "{\"error\":{\"message\":\"DO_NOT_EXPOSE_SECRET\"}}", 503) }.use { server ->
            val store = AdvancedStore(directory.resolve("failure"))
            store.saveConfig("alice", aiConfig(server.base))
            val assistant = ReadingAssistant(store, AdvancedHttp(), FakeTools())
            val error = try { assistant.clean("alice", jsonObject("bookUrl" to "failure", "index" to 1), "原文保留"); fail<Exception>("Expected upstream failure") }
                catch (e: IllegalArgumentException) { e }
            assertFalse(error.message.orEmpty().contains("DO_NOT_EXPOSE_SECRET"))
            Files.list(store.directory("alice")).use { files ->
                val snapshot = files.filter { it.fileName.toString().startsWith("clean-") }.findFirst().orElseThrow()
                val raw = JsonParser.parseString(Files.readString(snapshot)).asJsonObject
                assertEquals("原文保留", raw.string("rawContent")); assertFalse(raw.has("content"))
            }
        }
    }

    @Test
    fun `assistant streams tool rounds persists history and rejects unapproved model tools`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val tools = FakeTools()
        MockProvider { _, body ->
            val turn = calls.incrementAndGet()
            assertTrue(body.flag("stream"))
            assertTrue(body.array("messages")[0].asJsonObject.string("content").contains("章节上下文"))
            if (turn == 1) Response("text/event-stream", toolStream("reading_stats")) else {
                assertTrue(body.array("messages").any { it.asJsonObject.string("role") == "tool" })
                Response("text/event-stream", textStream("阅读", "完成"))
            }
        }.use { server ->
            val store = AdvancedStore(directory)
            store.saveConfig("alice", aiConfig(server.base))
            val assistant = ReadingAssistant(store, AdvancedHttp(), tools)
            val events = mutableListOf<Pair<String, JsonObject>>()
            val result = assistant.chat("alice", jsonObject("messages" to listOf(mapOf("role" to "user", "content" to "总结")),
                "context" to mapOf("bookUrl" to "fixture", "index" to 0), "allowedTools" to listOf("reading_stats"))) { event, data -> events.add(event to data) }
            assertEquals("阅读完成", result.string("content"))
            assertEquals(listOf("tool", "delta", "delta"), events.map { it.first })
            val session = assistant.session("alice", result.string("sessionId"))
            assertEquals("complete", session.string("status")); assertEquals(1, session.array("toolResults").size())
            assertTrue(session.array("messages").any { it.asJsonObject.string("content") == "阅读完成" })
            assertEquals(1, assistant.sessions("alice").size); assertEquals(0, assistant.sessions("bob").size)
            assertThrows(IllegalArgumentException::class.java) { assistant.session("bob", result.string("sessionId")) }
            assertEquals(listOf("alice", "alice"), tools.users)
        }
        MockProvider { _, _ -> Response("text/event-stream", toolStream("execute_shell")) }.use { server ->
            val store = AdvancedStore(directory.resolve("denied")); store.saveConfig("alice", aiConfig(server.base))
            val assistant = ReadingAssistant(store, AdvancedHttp(), FakeTools())
            try { assistant.chat("alice", jsonObject("messages" to listOf(mapOf("role" to "user", "content" to "hello")))) { _, _ -> }; fail("Expected denied tool") }
            catch (_: IllegalArgumentException) { }
            assertEquals("failed", assistant.sessions("alice").single().string("status"))
        }
    }

    @Test
    fun `TTS splits roles sends voices and reuses only own cached audio`() = runBlocking<Unit> {
        val voices = mutableListOf<String>()
        MockProvider { _, body ->
            voices.add(body.string("voice")); assertEquals("mp3", body.string("response_format"))
            Response("audio/mpeg", "ID3-fixture-audio")
        }.use { server ->
            val store = AdvancedStore(directory)
            val config = jsonObject("engines" to listOf(mapOf("id" to "voice", "name" to "本地语音", "baseUrl" to server.base,
                "model" to "fixture-tts", "voice" to "narrator", "apiKey" to "fixture-secret", "roleVoices" to mapOf("甲" to "actor"))), "defaultEngineId" to "voice")
            assertFalse(store.saveConfig("alice", config, true).toString().contains("fixture-secret"))
            val speech = ReadingSpeech(store, AdvancedHttp())
            val input = jsonObject("text" to "旁白文字\n甲：角色对白", "speed" to 1.2)
            val first = speech.synthesize("alice", input)
            assertEquals(listOf("narrator", "actor"), voices)
            assertEquals(2, first.array("segments").size())
            val id = first.array("segments")[0].asJsonObject.string("id")
            assertTrue(Files.isRegularFile(speech.audio("alice", id))); assertFalse(Files.exists(speech.audio("bob", id)))
            assertTrue(speech.synthesize("alice", input).array("segments").all { it.asJsonObject.flag("cached") })
            assertEquals(2, voices.size)
            store.saveConfig("bob", config, true); speech.synthesize("bob", input); assertEquals(4, voices.size)
            assertThrows(IllegalArgumentException::class.java) { speech.audio("alice", "../private") }
        }
    }

    @Test
    fun `assistant cancellation closes a stalled upstream and persists cancelled partial history`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val firstDelta = CompletableDeferred<Unit>()
        val packet = jsonObject("choices" to listOf(mapOf("delta" to mapOf("content" to "部分回复"))))
        MockProvider { _, _ -> Response("text/event-stream", "data: $packet\n\n", hold = release) }.use { server ->
            val store = AdvancedStore(directory); store.saveConfig("alice", aiConfig(server.base))
            val assistant = ReadingAssistant(store, AdvancedHttp(), FakeTools())
            try {
                val job = launch { assistant.chat("alice", jsonObject("messages" to listOf(mapOf("role" to "user", "content" to "hello")))) { _, _ -> firstDelta.complete(Unit) } }
                withTimeout(3000) { firstDelta.await(); job.cancelAndJoin() }
                val summary = assistant.sessions("alice").single()
                assertEquals("cancelled", summary.string("status"))
                assertTrue(assistant.session("alice", summary.string("id")).array("messages").any { it.asJsonObject.flag("partial") })
            } finally { release.countDown() }
        }
    }

    @Test
    fun `truncated AI stream and failed TTS never become successful cached outputs`() = runBlocking<Unit> {
        val partial = jsonObject("choices" to listOf(mapOf("delta" to mapOf("content" to "不完整回复"))))
        MockProvider { path, _ -> if (path.endsWith("speech")) Response("application/json", "{\"error\":true}", 503)
            else Response("text/event-stream", "data: $partial\n\n") }.use { server ->
            val store = AdvancedStore(directory); store.saveConfig("alice", aiConfig(server.base))
            val assistant = ReadingAssistant(store, AdvancedHttp(), FakeTools())
            try { assistant.chat("alice", jsonObject("messages" to listOf(mapOf("role" to "user", "content" to "hello")))) { _, _ -> }; fail("Expected stream failure") }
            catch (_: IllegalArgumentException) { }
            assertEquals("failed", assistant.sessions("alice").single().string("status"))
            store.saveConfig("alice", jsonObject("engines" to listOf(mapOf("id" to "fixture", "baseUrl" to server.base, "model" to "tts", "voice" to "narrator", "apiKey" to "fixture-secret"))), true)
            try { ReadingSpeech(store, AdvancedHttp()).synthesize("alice", jsonObject("text" to "语音失败")); fail("Expected speech failure") }
            catch (_: IllegalArgumentException) { }
            assertFalse(Files.exists(store.directory("alice").resolve("audio")))
        }
    }

    @Test
    fun `reading records are idempotent isolated and divide books across midnight`() {
        val stats = ReadingStatistics(AdvancedStore(directory))
        val input = jsonObject("id" to "session-1", "bookUrl" to "fixture", "startTime" to Instant.parse("2026-10-01T23:59:00Z").toEpochMilli(),
            "endTime" to Instant.parse("2026-10-02T00:01:00Z").toEpochMilli(), "seconds" to 120, "characters" to 200, "chapterIndex" to 1,
            "bookName" to "统计书籍", "author" to "作者")
        stats.save("alice", input); stats.save("alice", input)
        val summary = stats.summary("alice", "UTC")
        assertEquals(120, summary.long("totalSeconds")); assertEquals(200, summary.long("totalCharacters")); assertEquals(1, summary.long("totalSessions"))
        assertEquals(2, summary.array("days").size()); assertTrue(summary.array("days").all { it.asJsonObject.long("seconds") == 60L })
        assertEquals("fixture", summary.array("books")[0].asJsonObject.string("bookUrl"))
        assertEquals("统计书籍", summary.array("books")[0].asJsonObject.string("bookName")); assertEquals("作者", summary.array("books")[0].asJsonObject.string("author"))
        assertEquals(2, summary.array("books")[0].asJsonObject.array("days").size())
        assertEquals(0, stats.summary("bob", "UTC").long("totalSessions"))
        assertThrows(IllegalArgumentException::class.java) { stats.save("alice", input.deepCopy().also { it.addProperty("bookUrl", "other") }) }
        assertThrows(IllegalArgumentException::class.java) { stats.save("alice", input.deepCopy().also { it.addProperty("seconds", 121) }) }
    }

    @Test
    fun `MCP discovery execution errors and notifications conform to JSON RPC`() = runBlocking<Unit> {
        val tools = FakeTools(); val rpc = AdvancedMcp(tools)
        fun request(method: String, params: JsonObject = JsonObject()) = jsonObject("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)
        val initialization = jsonObject("protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "fixture", "version" to "1.0"))
        assertEquals("2025-11-25", rpc.handle("alice", request("initialize", initialization))!!.getAsJsonObject("result").string("protocolVersion"))
        assertEquals("2025-03-26", rpc.handle("alice", request("initialize", initialization.deepCopy().also { it.addProperty("protocolVersion", "2025-03-26") }))!!.getAsJsonObject("result").string("protocolVersion"))
        assertEquals(-32602, rpc.handle("alice", request("initialize"))!!.getAsJsonObject("error").long("code"))
        assertEquals(2, rpc.handle("alice", request("tools/list"))!!.getAsJsonObject("result").array("tools").size())
        val result = rpc.handle("alice", request("tools/call", jsonObject("name" to "reading_stats", "arguments" to emptyMap<String, Any>())))!!
        assertFalse(result.getAsJsonObject("result").flag("isError")); assertEquals(listOf("alice"), tools.users)
        assertEquals(-32602, rpc.handle("alice", request("tools/call", jsonObject("name" to "execute_shell")))!!.getAsJsonObject("error").long("code"))
        assertEquals(-32601, rpc.handle("alice", request("unknown"))!!.getAsJsonObject("error").long("code"))
        assertNull(rpc.handle("alice", jsonObject("jsonrpc" to "2.0", "method" to "notifications/initialized")))
        val invalid = rpc.handle("alice", jsonObject("method" to "initialize"))!!
        assertEquals(-32600, invalid.getAsJsonObject("error").long("code")); assertTrue(invalid.get("id").isJsonNull)
        assertEquals(-32600, rpc.handle("alice", jsonObject("jsonrpc" to emptyMap<String, Any>(), "id" to 1, "method" to "initialize"))!!.getAsJsonObject("error").long("code"))
        assertEquals(-32600, rpc.handle("alice", request("ping").also { it.add("id", JsonArray()) })!!.getAsJsonObject("error").long("code"))
        assertEquals(-32602, rpc.handle("alice", request("tools/list").also { it.addProperty("params", "invalid") })!!.getAsJsonObject("error").long("code"))
    }

    @Test
    fun `successful HTTP error objects and fake audio bodies are rejected`() = runBlocking<Unit> {
        MockProvider { path, _ -> if (path.endsWith("models")) Response("application/json", "{\"error\":{\"message\":\"failed\"}}")
            else Response("audio/mpeg", "{\"error\":\"not audio\"}") }.use { server ->
            val store = AdvancedStore(directory); store.saveConfig("alice", aiConfig(server.base))
            val http = AdvancedHttp()
            try { http.models(store.provider("alice")); fail("Expected invalid model list") } catch (_: IllegalArgumentException) { }
            store.saveConfig("alice", jsonObject("engines" to listOf(mapOf("id" to "fixture", "baseUrl" to server.base, "model" to "tts", "voice" to "narrator", "apiKey" to "fixture-secret"))), true)
            try { ReadingSpeech(store, http).synthesize("alice", jsonObject("text" to "伪音频")); fail("Expected invalid MP3") } catch (_: IllegalArgumentException) { }
            assertFalse(Files.exists(store.directory("alice").resolve("audio")))
        }
    }

    private fun aiConfig(base: String, secret: String? = "fixture-secret"): JsonObject {
        val provider = jsonObject("id" to "fixture", "name" to "本地测试", "baseUrl" to base, "model" to "fixture-model")
        if (secret != null) provider.addProperty("apiKey", secret)
        return jsonObject("providers" to listOf(provider), "defaultProviderId" to "fixture")
    }
    private fun textStream(vararg values: String): String = values.joinToString("") { text ->
        "data: ${jsonObject("choices" to listOf(mapOf("delta" to mapOf("content" to text))))}\n\n"
    } + "data: [DONE]\n\n"
    private fun toolStream(name: String): String {
        val call = mapOf("index" to 0, "id" to "call-1", "function" to mapOf("name" to name, "arguments" to "{}"))
        val packet = jsonObject("choices" to listOf(mapOf("delta" to mapOf("tool_calls" to listOf(call)))))
        return "data: $packet\n\ndata: [DONE]\n\n"
    }

    private class FakeTools: AdvancedTools {
        val users = mutableListOf<String>()
        override fun definitions() = listOf("reading_stats", "book_content").map { name -> jsonObject("name" to name, "description" to name,
            "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())) }
        override suspend fun execute(user: String, name: String, arguments: JsonObject): JsonObject {
            users.add(user)
            return if (name == "book_content") jsonObject("content" to "章节上下文") else jsonObject("totalSeconds" to 120)
        }
    }
    private data class Response(val type: String, val content: String, val status: Int = 200, val hold: CountDownLatch? = null)
    private class MockProvider(private val handler: (String, JsonObject) -> Response): AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}/v1"
        init {
            server.createContext("/v1") { exchange ->
                try {
                    assertEquals("Bearer fixture-secret", exchange.requestHeaders.getFirst("Authorization"))
                    val input = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val body = if (input.isBlank()) JsonObject() else JsonParser.parseString(input).asJsonObject
                    val response = handler(exchange.requestURI.path, body)
                    val bytes = response.content.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", response.type)
                    exchange.sendResponseHeaders(response.status, if (response.hold == null) bytes.size.toLong() else 0)
                    exchange.responseBody.use { output ->
                        output.write(bytes); output.flush()
                        response.hold?.await(5, TimeUnit.SECONDS)
                    }
                } finally { exchange.close() }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }
}
