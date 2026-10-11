package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import com.htmake.reader.api.service.ChapterCache
import com.htmake.reader.api.service.ChapterEdits
import com.htmake.reader.api.service.ChapterMetadata
import com.htmake.reader.api.service.ReadingPosition
import com.htmake.reader.utils.asJsonArray
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.CoroutineContext

class ReadingFeatureController(context: CoroutineContext, private val books: BookController) : BaseController(context) {
    private suspend fun operation(context: RoutingContext, block: suspend (String, JsonObject) -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try {
            val body = if (context.request().method().name() == "POST") context.body().asJsonObject() else JsonObject()
                .put("url", context.queryParam("url").firstOrNull().orEmpty())
                .put("index", context.queryParam("index").firstOrNull()?.toIntOrNull() ?: 0)
            ReturnData().setData(withContext(Dispatchers.IO) { block(getUserNameSpace(context), body) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "操作失败") }
    }

    private suspend fun chapter(user: String, body: JsonObject): Triple<Book, BookChapter, String> {
        val book = books.getShelfBookByURL(body.getString("url", ""), user) ?: error("书籍未加入书架")
        require(!book.isLocalBook()) { "本轮仅支持网络书正文编辑和临时换源" }
        val source = books.getBookSourceStringBySourceURL(book.origin, user) ?: error("书源不存在")
        val chapters = books.getLocalChapterList(book, source, false, user)
        val index = body.getInteger("index", -1)
        require(index in chapters.indices) { "章节不存在" }
        return Triple(book, chapters[index], source)
    }

    private suspend fun content(user: String, book: Book, chapter: BookChapter, source: String): String {
        val directory = books.getChapterCacheDir(book, user)
        if (ChapterCache.valid(directory, chapter.index, chapter)) return File(directory, "${chapter.index}.txt").readText()
        val chapters = books.getLocalChapterList(book, source, false, user)
        val text = WebBook(source, userNameSpace = user).getBookContent(book, chapter, chapters.getOrNull(chapter.index + 1)?.url)
        com.htmake.reader.api.service.advanced.AdvancedStore.atomicWrite(File(directory, "${chapter.index}.txt").toPath(), text.toByteArray(Charsets.UTF_8))
        ChapterMetadata.save(directory, chapter)
        return text
    }

    suspend fun getChapterEdit(context: RoutingContext) = operation(context) { user, body ->
        val (book, chapter, source) = chapter(user, body)
        val edit = ChapterEdits.read(books.getChapterCacheDir(book, user), chapter)
        mapOf("title" to (edit?.getString("title") ?: chapter.title), "content" to (edit?.getString("content") ?: content(user, book, chapter, source)),
            "edited" to (edit != null), "chapter" to ChapterMetadata.values(chapter).map)
    }

    suspend fun saveChapterEdit(context: RoutingContext) = operation(context) { user, body ->
        val (book, chapter, source) = chapter(user, body)
        val original = content(user, book, chapter, source)
        val edit = ChapterEdits.save(books.getChapterCacheDir(book, user), chapter, body.getString("title", chapter.title),
            body.getString("content") ?: error("缺少正文"), original)
        mapOf("title" to edit.getString("title"), "content" to edit.getString("content"), "edited" to true)
    }

    suspend fun restoreChapterEdit(context: RoutingContext) = operation(context) { user, body ->
        val (book, chapter, source) = chapter(user, body)
        val text = ChapterEdits.restore(books.getChapterCacheDir(book, user), chapter) ?: content(user, book, chapter, source)
        mapOf("title" to chapter.title, "content" to text, "edited" to false)
    }

    suspend fun getAlternateChapter(context: RoutingContext) = operation(context) { user, body ->
        val (original, chapter, _) = chapter(user, body)
        val sourceUrl = body.getString("bookSourceUrl", "")
        val source = books.getBookSourceStringBySourceURL(sourceUrl, user) ?: error("书源不存在")
        val alternateUrl = body.getString("alternateBookUrl", body.getString("newUrl", ""))
        require(alternateUrl.isNotBlank()) { "新书籍链接不能为空" }
        val web = WebBook(source, userNameSpace = user)
        val alternate = web.getBookInfo(alternateUrl)
        require(alternate.name.trim() == original.name.trim() && (original.author.isBlank() || alternate.author.isBlank() ||
            alternate.getRealAuthor() == original.getRealAuthor())) { "临时换源需要相同书籍" }
        val chapters = web.getChapterList(alternate)
        require(chapters.isNotEmpty()) { "新书源目录为空" }
        val index = ReadingPosition.matchChapter(chapter.index, chapter.title, chapters)
        val selected = chapters[index]
        val text = web.getBookContent(alternate, selected, chapters.getOrNull(index + 1)?.url)
        mapOf("content" to text, "chapter" to ChapterMetadata.values(selected).map, "book" to alternate)
    }

    suspend fun getChapterMediaText(context: RoutingContext) = operation(context) { user, body ->
        val (book, chapter, sourceJson) = chapter(user, body)
        val field = body.getString("field", "")
        require(field in listOf("lyric", "danmaku")) { "字幕类型不合法" }
        val metadata = ChapterMetadata.read(books.getChapterCacheDir(book, user), chapter)
        val value = metadata.getString(field).orEmpty()
        val text = if (value.trim().matches(Regex("(?s)^(https?://|/|@js:).*"))) {
            val source = BookSource.fromJson(sourceJson, user).getOrThrow()
            withTimeout(15_000) {
                AnalyzeUrl(value, baseUrl = chapter.url, source = source, ruleData = book, chapter = chapter,
                    callTimeout = 15_000, readTimeout = 15_000).getStrResponseAwait()
                    .also { require(it.raw.isSuccessful) { "字幕资源请求失败：${it.raw.code}" } }.body.orEmpty()
            }
        } else value
        require(text.length <= 2_000_000) { "字幕或弹幕文件过大" }
        text
    }

    private fun rules(user: String): JsonArray = asJsonArray(getUserStorage(user, "dictRule")) ?: JsonArray()
    suspend fun getDictRules(context: RoutingContext) = operation(context) { user, _ -> rules(user).map { (it as JsonObject).map } }

    suspend fun saveDictRule(context: RoutingContext) = operation(context) { user, body ->
        val incoming = body.getJsonArray("rules") ?: JsonArray().add(body)
        require(incoming.size() in 1..100) { "词典规则数量不合法" }
        val existing = rules(user).map { it as JsonObject }.associateBy { it.getString("name") }.toMutableMap()
        incoming.forEach { raw ->
            val rule = raw as? JsonObject ?: error("词典规则必须为对象")
            val name = rule.getString("name", "").trim()
            require(name.isNotBlank() && name.length <= 100) { "词典名称不合法" }
            require(rule.getString("urlRule", "").isNotBlank()) { "词典 URL 规则不能为空" }
            existing[name] = rule.copy().put("name", name).put("enabled", rule.getBoolean("enabled", true)).put("sortNumber", rule.getInteger("sortNumber", 0))
        }
        require(existing.size <= 100) { "词典规则过多" }
        val saved = JsonArray(existing.values.sortedBy { it.getInteger("sortNumber", 0) })
        saveUserStorage(user, "dictRule", saved)
        saved.map { (it as JsonObject).map }
    }

    suspend fun deleteDictRule(context: RoutingContext) = operation(context) { user, body ->
        val name = body.getString("name", body.getString("ruleId", ""))
        val remaining = JsonArray(rules(user).filter { (it as JsonObject).getString("name") != name })
        saveUserStorage(user, "dictRule", remaining)
        remaining.map { (it as JsonObject).map }
    }

    suspend fun lookupDictionary(context: RoutingContext) = operation(context) { user, body ->
        val word = body.getString("word", "").trim()
        require(word.isNotEmpty() && word.length <= 200) { "查询文字长度不合法" }
        val requested = body.getString("ruleId", "")
        val selected = rules(user).map { it as JsonObject }.filter {
            it.getBoolean("enabled", true) && (requested.isBlank() || it.getString("name") == requested)
        }.sortedBy { it.getInteger("sortNumber", 0) }.take(20)
        require(selected.isNotEmpty()) { "请先导入或配置词典规则" }
        val results = selected.map { rule ->
            try {
                withTimeout(15_000) {
                    val scope = BookSource(bookSourceUrl = "dictionary:${rule.getString("name")}", header = rule.getString("header")).apply { runtimeUserNamespace = user }
                    val response = AnalyzeUrl(rule.getString("urlRule"), key = word, source = scope).getStrResponseAwait()
                    val show = rule.getString("showRule", "")
                    val text = if (show.isBlank()) response.body.orEmpty() else AnalyzeRule(Book(name = word), scope)
                        .setContent(response.body.orEmpty(), response.url).getString(show)
                    mapOf("name" to rule.getString("name"), "content" to text)
                }
            } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                mapOf("name" to rule.getString("name"), "content" to "", "error" to "词典查询超时")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mapOf("name" to rule.getString("name"), "content" to "", "error" to error.message.orEmpty()) }
        }
        mapOf("word" to word, "results" to results)
    }
}
