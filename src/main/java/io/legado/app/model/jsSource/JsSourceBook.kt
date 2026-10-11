package io.legado.app.model.jsSource

import com.google.gson.JsonParser
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.DebugLog
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils

class JsSourceBook(private val source: BookSource, private val debug: DebugLog?) {
    private val engine get() = JsSourceEngine(source, debug)

    fun search(key: String, page: Int?) = books(engine.call("search", listOf(key, page ?: 1)))
    fun explore(url: String, page: Int?) = books(engine.call("explore", listOf(url, page ?: 1)))

    private fun books(json: String?): List<SearchBook> {
        val value = JsonParser.parseString(json ?: "[]")
        require(value.isJsonArray) { "search/explore 返回值必须是数组" }
        val result = value.asJsonArray.mapNotNull { item ->
            require(item.isJsonObject) { "搜索条目必须是对象" }
            val book = GSON.fromJson(item, SearchBook::class.java)
            if (book.name.isBlank() || book.bookUrl.isBlank()) return@mapNotNull null
            book.bookUrl = NetworkUtils.getAbsoluteURL(source.getKey(), book.bookUrl)
            book.origin = source.getKey(); book.originName = source.getTag(); book.originOrder = source.customOrder
            book.type = source.bookSourceType
            book.downloadUrls = item.asJsonObject.get("downloadUrls")?.let { downloads(it, book.bookUrl) }
            book
        }
        debug?.log(source.getKey(), "◇JS书源搜索/发现完成，共 ${result.size} 条")
        return result
    }

    fun info(book: Book, canReName: Boolean): Book {
        engine.call("getBookInfo", listOf(book), optional = true)?.let { json ->
            val obj = JsonParser.parseString(json)
            require(obj.isJsonObject) { "getBookInfo 返回值必须是对象" }
            obj.asJsonObject.entrySet().forEach { (field, value) ->
                if (!value.isJsonNull) when (field) {
                    "name" -> if (canReName) book.name = value.asString
                    "author" -> if (canReName || book.author.isEmpty()) book.author = value.asString
                    "intro" -> book.intro = value.asString
                    "kind" -> book.kind = value.asString
                    "coverUrl" -> book.coverUrl = value.asString
                    "latestChapterTitle" -> book.latestChapterTitle = value.asString
                    "wordCount" -> book.wordCount = value.asString
                    "tocUrl" -> book.tocUrl = NetworkUtils.getAbsoluteURL(book.bookUrl, value.asString)
                    "variable" -> book.variable = if (value.isJsonObject) value.toString() else value.asString
                    "downloadUrls" -> book.downloadUrls = downloads(value, book.bookUrl)
                }
            }
        }
        book.origin = source.getKey(); book.originName = source.getTag(); book.type = source.bookSourceType
        if (source.bookSourceType == 3) require(!book.downloadUrls.isNullOrEmpty()) { "文件书源下载链接为空" }
        else if (book.tocUrl.isBlank()) book.tocUrl = book.bookUrl
        debug?.log(source.getKey(), "◇JS书源详情完成：${book.name}")
        return book
    }

    fun chapters(book: Book): List<BookChapter> {
        if (book.tocUrl.isBlank()) book.tocUrl = book.bookUrl
        val value = JsonParser.parseString(engine.call("getChapters", listOf(book)) ?: "[]")
        require(value.isJsonArray) { "getChapters 返回值必须是数组" }
        val chapters = value.asJsonArray.mapNotNull { item ->
            require(item.isJsonObject) { "章节条目必须是对象" }
            val chapter = GSON.fromJson(item, BookChapter::class.java)
            if (chapter.title.isBlank() || chapter.url.isBlank()) return@mapNotNull null
            if (!(chapter.isVolume && chapter.url == chapter.title)) {
                chapter.url = NetworkUtils.getAbsoluteURL(book.tocUrl, chapter.url)
            }
            chapter.bookUrl = book.bookUrl; chapter.baseUrl = book.tocUrl
            chapter
        }
        require(chapters.isNotEmpty()) { "JS书源目录为空" }
        chapters.forEachIndexed { index, chapter -> chapter.index = index }
        io.legado.app.model.webBook.BookChapterList.formatTitles(book, source, chapters, debug)
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.last().title
        debug?.log(source.getKey(), "◇JS书源目录完成，共 ${chapters.size} 章")
        return chapters
    }

    private fun downloads(value: com.google.gson.JsonElement, base: String): List<String> {
        require(value.isJsonArray) { "downloadUrls 必须是字符串数组" }
        return value.asJsonArray.map { item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString && item.asString.isNotBlank()) { "下载链接必须是非空字符串" }
            NetworkUtils.getAbsoluteURL(base, item.asString)
        }.distinct()
    }

    fun content(book: Book, chapter: BookChapter, next: String?): String {
        if (chapter.isVolume && chapter.url == chapter.title) return ""
        val content = engine.call("getContent", listOf(chapter, book, next), expectText = true)
        require(!content.isNullOrBlank()) { "JS书源正文为空" }
        debug?.log(source.getKey(), "◇JS书源正文完成，${content.length} 字符")
        return content
    }
}
