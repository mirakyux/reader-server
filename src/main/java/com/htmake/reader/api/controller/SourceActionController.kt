package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.SourceActions
import io.legado.app.help.source.SourceInteractions
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class SourceActionController(coroutineContext: CoroutineContext, private val sources: BookSourceController) : BaseController(coroutineContext) {
    private suspend fun operation(context: RoutingContext, action: suspend (BookSource, JsonObject) -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try {
            val body = context.body().asJsonObject()
            val url = body.getString("bookSourceUrl") ?: error("书源链接不能为空")
            val user = getUserNameSpace(context)
            val data = withContext(Dispatchers.IO) {
                val json = sources.getUserBookSourceJson(user)?.firstOrNull { (it as? JsonObject)?.getString("bookSourceUrl") == url }
                    as? JsonObject ?: error("书源不存在")
                val source = BookSource.fromJson(json.encode(), user).getOrThrow()
                action(source, body)
            }
            ReturnData().setData(data)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "书源操作失败") }
    }

    private fun book(body: JsonObject): Book? = body.getJsonObject("book")?.let { GSON.fromJson(it.encode(), Book::class.java) }
    private fun chapter(body: JsonObject): BookChapter? = body.getJsonObject("chapter")?.let { GSON.fromJson(it.encode(), BookChapter::class.java) }

    suspend fun getSourceCapabilities(context: RoutingContext) = operation(context) { source, _ -> SourceActions.capabilities(source) }

    suspend fun drainSourceInteractions(context: RoutingContext): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try {
            val body = context.body().asJsonObject()
            val url = body.getString("bookSourceUrl")?.takeIf { it.isNotBlank() }
            val sourceType = body.getString("sourceType", "book")
            require(sourceType in setOf("book", "rss")) { "书源类型无效" }
            val user = getUserNameSpace(context)
            val actions = withContext(Dispatchers.IO) {
                if (sourceType == "rss") {
                    val stored = io.vertx.core.json.JsonArray(getUserStorage(user, "rssSources") ?: "[]").mapNotNull { it as? JsonObject }
                    val selected = if (url == null) stored else stored.filter { it.getString("sourceUrl") == url }
                    require(url == null || selected.isNotEmpty()) { "订阅源不存在" }
                    selected.mapNotNull { it.getString("sourceUrl")?.takeIf(String::isNotBlank) }.distinct().flatMap { sourceUrl ->
                        val identity = io.legado.app.data.entities.RssSource(sourceUrl = sourceUrl).apply { setUserNamespace(user) }
                        SourceInteractions.drain(identity).map { it + ("sourceType" to "rss") }
                    }
                } else {
                val stored = sources.getUserBookSourceJson(user)?.mapNotNull { it as? JsonObject }.orEmpty()
                val selected = if (url == null) stored else stored.filter { it.getString("bookSourceUrl") == url }
                require(url == null || selected.isNotEmpty()) { "书源不存在" }
                // Draining uses only identity: it must not evaluate scripts of every saved source.
                selected.mapNotNull { it.getString("bookSourceUrl")?.takeIf(String::isNotBlank) }.distinct().flatMap { sourceUrl ->
                    SourceInteractions.drain(BookSource(bookSourceUrl = sourceUrl).apply { runtimeUserNamespace = user }).map { it + ("sourceType" to "book") }
                }
                }
            }
            ReturnData().setData(mapOf("actions" to actions))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "书源交互读取失败") }
    }

    suspend fun checkBookSource(context: RoutingContext) = operation(context) { source, body ->
        val key = body.getString("key")?.takeIf { it.isNotBlank() } ?: source.getCheckKeyword()
        io.legado.app.model.jsSource.SourceValidation.check(source, key)
    }

    suspend fun getSourceExploreUi(context: RoutingContext) = operation(context) { source, _ -> SourceActions.exploreUi(source) }

    suspend fun applySourceExploreAction(context: RoutingContext) = operation(context) { source, body ->
        SourceActions.exploreAction(source, body.getString("group") ?: "categories", body.getInteger("index") ?: error("控件索引不能为空"),
            body.getValue("value")?.toString())
    }

    suspend fun runSourceAction(context: RoutingContext) = operation(context) { source, body ->
        SourceActions.action(source, body.getString("action") ?: error("动作不能为空"), book(body), chapter(body), body.getString("event"),
            body.getValue("result"), body.getBoolean("confirm") == true)
    }

    suspend fun getSourceReviews(context: RoutingContext) = operation(context) { source, body ->
        SourceActions.reviews(source, book(body) ?: error("书籍不能为空"), chapter(body), body.getBoolean("quote") == true,
            body.getJsonObject("review")?.map ?: emptyMap(), body.getString("paragraph"), body.getInteger("index"))
    }

    suspend fun decodeSourceImage(context: RoutingContext) = operation(context) { source, body ->
        SourceActions.image(source, body.getString("url") ?: error("图片链接不能为空"), body.getBoolean("isCover") == true, book(body), chapter(body))
    }
}
