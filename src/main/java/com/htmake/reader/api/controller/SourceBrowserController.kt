package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.source.BrowserBridge
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

class SourceBrowserController(coroutineContext: CoroutineContext, private val sources: BookSourceController) : BaseController(coroutineContext) {
    private data class Session(val user: String, val source: String, val sourceType: String, var updated: Long,
                               val interactionId: String?, val refetchAfterSuccess: Boolean)
    private val sessions = ConcurrentHashMap<String, Session>()
    private suspend fun source(context: RoutingContext, url: String, sourceType: String): BaseSource {
        val user = getUserNameSpace(context)
        require(sourceType in setOf("book", "rss")) { "书源类型无效" }
        if (sourceType == "rss") return RssSourceController(coroutineContext).getRssSourceByURL(url, user) ?: error("订阅源不存在")
        val list = sources.getUserBookSourceJson(user) ?: error("书源不存在")
        val json = (0 until list.size()).map { list.getJsonObject(it) }.firstOrNull { it.getString("bookSourceUrl") == url } ?: error("书源不存在")
        return BookSource.fromJson(json.encode(), user).getOrThrow()
    }
    suspend fun open(context: RoutingContext): ReturnData = operation(context) {
        val body = context.body().asJsonObject() ?: error("缺少请求参数")
        val sourceType = body.getString("sourceType", "book")
        val source = source(context, body.getString("bookSourceUrl") ?: error("缺少书源"), sourceType)
        val interaction = body.getString("interactionId")?.let { io.legado.app.help.source.SourceInteractions.resolve(source, it) }
        require(interaction == null || interaction.type == "browser") { "此动作不是浏览器请求" }
        val url = interaction?.url ?: source.loginUrl?.takeUnless { it.startsWith("@js:") || it.startsWith("<js>") } ?: source.getKey()
        val result = withContext(Dispatchers.IO) { BrowserBridge.call("/session", BrowserBridge.input(source, url, interaction?.html,
            BrowserBridge.bootstrapHeaders(source))) }
        sessions.entries.removeIf { it.value.updated < System.currentTimeMillis() - 300000 }
        sessions[result.getString("id")] = Session(getUserNameSpace(context), source.getKey(), sourceType, System.currentTimeMillis(),
            interaction?.id, interaction?.refetchAfterSuccess == true)
        BrowserBridge.save(source, result)
        result.remove("storageState")
        result
    }
    suspend fun action(context: RoutingContext): ReturnData = operation(context) { sessionOperation(context, false) }
    suspend fun close(context: RoutingContext): ReturnData = operation(context) { sessionOperation(context, true) }
    private suspend fun sessionOperation(context: RoutingContext, close: Boolean): JsonObject {
        val body = context.body().asJsonObject() ?: error("缺少请求参数")
        val id = body.getString("id") ?: error("缺少浏览器会话")
        val session = sessions[id] ?: error("浏览器会话已过期")
        require(session.user == getUserNameSpace(context)) { "浏览器会话不属于当前用户" }
        require(!body.containsKey("sourceType") || body.getString("sourceType") == session.sourceType) { "浏览器会话书源类型不匹配" }
        val input = JsonObject().put("id", id)
        if (close) input.put("refetch", session.refetchAfterSuccess)
        if (!close) {
            val action = body.getString("action") ?: error("缺少动作")
            require(action in listOf("click", "type", "key", "scroll", "refresh")) { "浏览器动作无效" }
            input.put("action", action)
            listOf("x", "y", "text", "key", "delta").forEach { if (body.containsKey(it)) input.put(it, body.getValue(it)) }
        }
        val result = withContext(Dispatchers.IO) { BrowserBridge.call(if (close) "/close" else "/action", input) }
        val source = source(context, session.source, session.sourceType)
        BrowserBridge.save(source, result)
        if (close && session.interactionId != null) io.legado.app.help.source.SourceInteractions.complete(source,
            session.interactionId, result.getString("url"), result.getString("body"))
        if (close) sessions.remove(id) else session.updated = System.currentTimeMillis()
        result.remove("storageState")
        result.remove("body")
        return result
    }
    private suspend fun operation(context: RoutingContext, action: suspend () -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try { val data = action(); ReturnData().setData(if (data is JsonObject) data.map else data) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "浏览器操作失败") }
    }
}
