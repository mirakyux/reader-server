package com.htmake.reader.api.controller

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.htmake.reader.api.ReturnData
import com.htmake.reader.api.service.advanced.*
import com.htmake.reader.utils.getStoragePath
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.io.StringReader
import kotlin.coroutines.CoroutineContext

class AdvancedCapabilityController(coroutineContext: CoroutineContext, bookController: BookController): BaseController(coroutineContext) {
    private val store = AdvancedStore(Path.of(getStoragePath(), "advanced"))
    private val http = AdvancedHttp()
    private val statistics = ReadingStatistics(store, com.htmake.reader.api.service.UserDataFiles(Path.of(getStoragePath(), "data")))
    private val tools = ReadingTools(bookController, statistics)
    private val assistant = ReadingAssistant(store, http, tools)
    private val speech = ReadingSpeech(store, http)
    private val rpc = AdvancedMcp(tools)

    private fun body(context: RoutingContext): JsonObject = JsonParser.parseString(context.body().asString()).asJsonObject
    // API keys, private sessions and statistics always belong to the authenticated account.
    private fun owner(context: RoutingContext): String {
        if (!appConfig.secure) return "default"
        val username: String? = context.get("username") as String?
        return username?.takeIf { it.isNotBlank() } ?: error("请登录后使用")
    }
    private suspend fun operation(context: RoutingContext, block: suspend (String) -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setErrorMsg("请登录后使用")
        return try { ReturnData().setData(withContext(Dispatchers.IO) { block(owner(context)) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IllegalArgumentException) { ReturnData().setErrorMsg(error.message ?: "参数无效") }
        catch (_: Exception) { ReturnData().setErrorMsg("操作失败，请检查服务配置、书籍和请求参数") }
    }

    suspend fun getAiConfig(context: RoutingContext) = operation(context) { store.config(it) }
    suspend fun saveAiConfig(context: RoutingContext) = operation(context) { store.saveConfig(it, body(context)) }
    suspend fun getAiModels(context: RoutingContext) = operation(context) { http.models(store.provider(it, body(context).string("providerId"))) }
    suspend fun getSkills(context: RoutingContext) = operation(context) { assistant.skills(it) }
    suspend fun saveSkills(context: RoutingContext) = operation(context) { assistant.saveSkills(it, body(context)) }
    suspend fun getAiSessions(context: RoutingContext) = operation(context) { assistant.sessions(it) }
    suspend fun getAiSession(context: RoutingContext) = operation(context) { assistant.session(it, context.queryParam("id").firstOrNull().orEmpty()) }
    suspend fun deleteAiSession(context: RoutingContext) = operation(context) { assistant.deleteSession(it, body(context).string("id")); true }
    suspend fun getTtsConfig(context: RoutingContext) = operation(context) { store.config(it, true) }
    suspend fun saveTtsConfig(context: RoutingContext) = operation(context) { store.saveConfig(it, body(context), true) }
    suspend fun saveReadingSession(context: RoutingContext) = operation(context) { user ->
        val request = body(context)
        val identity = tools.bookIdentity(user, request.string("bookUrl"))
        request.addProperty("bookName", identity.string("bookName")); request.addProperty("author", identity.string("author"))
        statistics.save(user, request)
    }
    suspend fun getReadingStats(context: RoutingContext) = operation(context) { statistics.summary(it, context.queryParam("timezone").firstOrNull() ?: java.time.ZoneId.systemDefault().id) }
    suspend fun cleanContent(context: RoutingContext) = operation(context) { user ->
        val request = body(context)
        val raw = tools.chapter(user, request.string("bookUrl"), request.integer("index", -1)).string("content")
        assistant.clean(user, request, raw)
    }
    suspend fun synthesize(context: RoutingContext) = operation(context) { user ->
        val request = body(context)
        val raw = if (request.string("bookUrl").isNotBlank()) tools.chapter(user, request.string("bookUrl"), request.integer("index", -1)).string("content") else ""
        speech.synthesize(user, request, raw)
    }

    suspend fun audio(context: RoutingContext) {
        if (!checkAuth(context)) { context.response().setStatusCode(401).end(); return }
        val path = try { speech.audio(owner(context), context.pathParam("id")) }
            catch (_: IllegalArgumentException) { context.response().setStatusCode(400).end(); return }
        if (!Files.isRegularFile(path)) { context.response().setStatusCode(404).end(); return }
        context.response().putHeader("Content-Type", "audio/mpeg").putHeader("Cache-Control", "private, max-age=86400")
            .sendFile(path.toString()).coAwait()
    }

    suspend fun chatSSE(context: RoutingContext) {
        val response = context.response().putHeader("Content-Type", "text/event-stream").putHeader("Cache-Control", "no-cache").setChunked(true)
        suspend fun event(name: String, data: JsonObject) {
            if (!response.closed()) response.write("event: $name\ndata: ${advancedJson.toJson(data)}\n\n").coAwait()
        }
        if (!checkAuth(context)) { event("error", jsonObject("errorMsg" to "请登录后使用")); response.end().coAwait(); return }
        val job = currentCoroutineContext()[Job]
        response.closeHandler { job?.cancel() }
        try {
            val result = assistant.chat(owner(context), body(context), ::event)
            event("end", result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { event("error", jsonObject("errorMsg" to "助手请求失败，请检查模型服务和请求参数")) }
        finally { if (!response.closed() && !response.ended()) response.end().coAwait() }
    }

    suspend fun mcp(context: RoutingContext) {
        suspend fun respond(value: JsonObject) {
            // JSON-RPC requires id:null in parse/invalid request responses; the shared Gson writer omits nulls.
            context.response().putHeader("Content-Type", "application/json; charset=UTF-8").putHeader("Cache-Control", "no-store")
                .end(advancedJson.toJson(value)).coAwait()
        }
        if (!checkAuth(context)) {
            context.response().setStatusCode(401); respond(rpc.error(code = -32001, message = "Authentication required")); return
        }
        val protocol = context.request().getHeader("MCP-Protocol-Version")
        if (protocol != null && protocol !in AdvancedMcp.supportedVersions) {
            context.response().setStatusCode(400); respond(rpc.error(code = -32600, message = "Unsupported MCP protocol version")); return
        }
        val parsed = try {
            JsonReader(StringReader(context.body().asString())).use { reader ->
                reader.strictness = Strictness.STRICT
                JsonParser.parseReader(reader).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
            }
        }
            catch (_: Exception) { respond(rpc.error(code = -32700, message = "Parse error")); return }
        if (!parsed.isJsonObject) { respond(rpc.error(code = -32600, message = "Invalid Request")); return }
        val request = parsed.asJsonObject
        val result = withContext(Dispatchers.IO) { rpc.handle(owner(context), request) }
        if (result == null) context.response().setStatusCode(202).end().coAwait() else respond(result)
    }
}
