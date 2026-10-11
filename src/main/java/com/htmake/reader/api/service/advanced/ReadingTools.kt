package com.htmake.reader.api.service.advanced

import com.google.gson.JsonObject
import com.htmake.reader.api.controller.BookController
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface AdvancedTools {
    fun definitions(): List<JsonObject>
    suspend fun execute(user: String, name: String, arguments: JsonObject): JsonObject
}

/** The same read-only, user-scoped tools are shared by the assistant and MCP. */
class ReadingTools(private val books: BookController, private val statistics: ReadingStatistics): AdvancedTools {
    fun ownsBook(user: String, url: String): Boolean = books.getShelfBookByURL(url, user) != null
    fun bookIdentity(user: String, url: String): JsonObject {
        val book = books.getShelfBookByURL(url, user) ?: throw IllegalArgumentException("书籍不在当前用户书架中")
        return jsonObject("bookName" to book.name, "author" to book.author)
    }
    override fun definitions(): List<JsonObject> = listOf(
        tool("bookshelf_list", "列出当前用户书架", mapOf("limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 200))),
        tool("book_sources_list", "列出当前用户书源元信息，不含脚本或凭据", emptyMap()),
        tool("book_search", "通过用户已配置的指定书源搜索", mapOf("sourceUrl" to mapOf("type" to "string"), "key" to mapOf("type" to "string"), "page" to mapOf("type" to "integer", "minimum" to 1)), listOf("sourceUrl", "key")),
        tool("book_chapters", "获取当前用户书架书籍目录", mapOf("bookUrl" to mapOf("type" to "string")), listOf("bookUrl")),
        tool("book_content", "读取当前用户书架书籍章节原文", mapOf("bookUrl" to mapOf("type" to "string"), "index" to mapOf("type" to "integer", "minimum" to 0)), listOf("bookUrl", "index")),
        tool("reading_stats", "获取当前用户按日和书籍阅读统计", emptyMap())
    )
    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String> = emptyList()) =
        jsonObject("name" to name, "description" to description,
            "inputSchema" to mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false),
            "annotations" to mapOf("readOnlyHint" to true, "destructiveHint" to false, "openWorldHint" to (name in listOf("book_search", "book_chapters", "book_content"))))

    override suspend fun execute(user: String, name: String, arguments: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val definition = definitions().firstOrNull { it.string("name") == name } ?: error("工具不在白名单中")
        val schema = definition.getAsJsonObject("inputSchema")
        require(arguments.keySet().all { it in schema.getAsJsonObject("properties").keySet() }) { "工具参数包含未允许字段" }
        schema.array("required").forEach { require(arguments.has(it.asString)) { "缺少工具必填参数" } }
        when (name) {
            "bookshelf_list" -> {
                val limit = arguments.integer("limit", 100); require(limit in 1..200) { "数量须在 1 至 200 之间" }
                val list = books.getBookShelfBooks(false, user)
                jsonObject("books" to list.take(limit), "total" to list.size)
            }
            "book_sources_list" -> jsonObject("sources" to books.loadBookSourceStringList(user).map { text ->
                val source = com.google.gson.JsonParser.parseString(text).asJsonObject
                jsonObject("bookSourceUrl" to source.string("bookSourceUrl"), "bookSourceName" to source.string("bookSourceName"),
                    "bookSourceGroup" to source.string("bookSourceGroup"), "enabled" to source.flag("enabled", true))
            })
            "book_search" -> {
                val source = books.getBookSourceStringBySourceURL(arguments.string("sourceUrl"), user) ?: error("书源不存在")
                val key = arguments.string("key"); val page = arguments.integer("page", 1)
                require(key.length in 1..200 && page in 1..10000) { "搜索参数错误" }
                jsonObject("books" to WebBook(source, false, userNameSpace = user).searchBook(key, page).take(100))
            }
            "book_chapters" -> {
                val book = books.getShelfBookByURL(arguments.string("bookUrl"), user) ?: error("书籍不在当前用户书架中")
                val source = if (book.isLocalBook()) "" else books.getBookSourceStringBySourceURL(book.origin, user) ?: error("书源不存在")
                jsonObject("chapters" to books.getLocalChapterList(book, source, false, user).take(3000))
            }
            "book_content" -> chapter(user, arguments.string("bookUrl"), arguments.integer("index", -1))
            "reading_stats" -> statistics.summary(user)
            else -> error("工具不在白名单中")
        }
    }

    suspend fun chapter(user: String, url: String, index: Int): JsonObject = withContext(Dispatchers.IO) {
        val book = books.getShelfBookByURL(url, user) ?: error("书籍不在当前用户书架中")
        require(book.type != io.legado.app.constant.BookType.audio && book.type != io.legado.app.constant.BookType.file) { "此书籍类型不支持文字读取" }
        val source = if (book.isLocalBook()) "" else books.getBookSourceStringBySourceURL(book.origin, user) ?: error("书源不存在")
        val chapters = books.getLocalChapterList(book, source, false, user)
        require(index in chapters.indices) { "章节索引无效" }
        val chapter = chapters[index]
        val raw = if (book.isLocalBook()) LocalBook.getContent(book, chapter).orEmpty() else {
            val cached = File(books.getChapterCacheDir(book, user), "$index.txt")
            if (cached.isFile && cached.length() > 0) cached.readText() else
                WebBook(source, false, userNameSpace = user).getBookContent(book, chapter, chapters.getOrNull(index + 1)?.url)
        }
        require(raw.isNotBlank()) { "章节正文为空" }
        require(raw.length <= 200000) { "章节正文过长" }
        jsonObject("bookUrl" to book.bookUrl, "bookName" to book.name, "author" to book.author,
            "index" to index, "chapterTitle" to chapter.title, "content" to raw)
    }
}
