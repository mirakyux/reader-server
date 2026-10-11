package io.legado.app.model

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

class SourceFuRuleRuntimeTests {
    @Test fun `FU invalid first-match regular expression returns the replacement`() {
        assertEquals("替代", AnalyzeRule().setContent("正文").getString("@js:result##[##替代###"))
    }
    private fun source(url: String = "https://runtime.example") = BookSource(bookSourceUrl = url)
        .apply { runtimeUserNamespace = "runtime-${UUID.randomUUID()}" }

    @Test
    fun `FU rule variable lookup falls through empty chapter book and source scopes`() {
        val source = source()
        val book = Book()
        val chapter = BookChapter()
        val rules = AnalyzeRule(book, source).apply { this.chapter = chapter }
        val url = AnalyzeUrl(source.getKey(), source = source, ruleData = book, chapter = chapter)
        try {
            source.put("shared", "source")
            assertEquals("source", rules.get("shared"))
            assertEquals("source", url.get("shared"))
            book.putVariable("shared", "book")
            assertEquals("book", rules.get("shared"))
            chapter.putVariable("shared", "chapter")
            assertEquals("chapter", url.get("shared"))
            chapter.putVariable("shared", "")
            assertEquals("book", rules.get("shared"))
            book.putVariable("shared", "")
            assertEquals("source", url.get("shared"))
            AnalyzeRule(source = source).put("rule-only", "stored")
            AnalyzeUrl(source.getKey(), source = source).put("url-only", "stored")
            assertEquals("stored", source.get("rule-only"))
            assertEquals("stored", source.get("url-only"))
            assertEquals(false, AnalyzeRule(source = source).evalJS("fromBookInfo", null))
            assertEquals(true, AnalyzeRule(book, source, true, true).evalJS("fromBookInfo", null))
        } finally { listOf("v_shared", "v_rule-only", "v_url-only").forEach { source.getScriptCache().delete(it) } }
    }

    @Test
    fun `FU reGetBook refreshes actual search and detail fields without losing user namespace or progress`() {
        val source = source().apply { mainJs = """
            function search(key,page) { source.put('seenUser', source.get('marker')); return [{name:'目标书',author:'作者',bookUrl:'/new-book'}]; }
            function getBookInfo(book) { return {tocUrl:'/new-toc',intro:'新详情',latestChapterTitle:'末章',kind:'新分类'}; }
        """.trimIndent() }
        val user = source.getUserNamespace()
        val book = Book(name = "目标书", author = "作者", bookUrl = "https://runtime.example/old", durChapterIndex = 7, durChapterPos = 19, customTag = "收藏")
        try {
            source.put("marker", "isolated")
            assertThrows(IllegalStateException::class.java) { AnalyzeRule(book, source).reGetBook() }
            AnalyzeRule(book, source, preUpdateJs = true).reGetBook()
            assertEquals("https://runtime.example/new-book", book.bookUrl)
            assertEquals("https://runtime.example/new-toc", book.tocUrl)
            assertEquals("新详情", book.intro)
            assertEquals("末章", book.latestChapterTitle)
            assertEquals("新分类", book.kind)
            assertEquals(7, book.durChapterIndex)
            assertEquals(19, book.durChapterPos)
            assertEquals("收藏", book.customTag)
            assertEquals(user, source.getUserNamespace())
            assertEquals("isolated", source.get("seenUser"))
        } finally { listOf("v_marker", "v_seenUser").forEach { source.getScriptCache().delete(it) } }
    }

    @Test
    fun `FU directory entry runs reGetBook and carries detail context across HTTP toc pages`() = runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val body = when (path) {
                "/search" -> """<article><a href='/wrong-book'>目标书</a><span class='author'>其他作者</span></article>
                    <article><a href='/new-book'>目标书</a><span class='author'>作者</span></article>"""
                "/new-book" -> "<h1>目标书</h1><span class='author'>作者</span><a class='toc' href='/toc'>目录</a>"
                "/toc" -> "<a class='chapter' href='/one'>第一章</a><a class='next' href='/toc-two'>下一页</a>"
                "/toc-two" -> "<a class='chapter' href='/two'>第二章</a>"
                else -> "unexpected request"
            }.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = source(base).apply {
            searchUrl = "$base/search"
            ruleSearch = SearchRule(bookList = "@css:article", name = "@css:a@text",
                author = "@css:.author@text", bookUrl = "@css:a@href")
            ruleBookInfo = BookInfoRule(name = "@css:h1@text", author = "@css:.author@text",
                tocUrl = "@css:a.toc@href", intro = "@js:fromBookInfo ? 'wrong detail context' : 'new details'")
            ruleToc = TocRule(preUpdateJs = "source.put('preFromBookInfo', String(fromBookInfo)); java.reGetBook();",
                chapterList = "@css:a.chapter", chapterName = "@js:fromBookInfo ? java.getString('text') : 'wrong toc context'",
                chapterUrl = "href", nextTocUrl = "@css:a.next@href")
        }
        val user = source.getUserNamespace()
        val book = Book(name = "目标书", author = "作者", bookUrl = "$base/old-book", tocUrl = "$base/old-toc",
            durChapterIndex = 7, durChapterPos = 19, customTag = "收藏")
        try {
            val chapters = withTimeout(5_000) {
                WebBook(source, false, userNameSpace = user).getChapterList(book, isFromBookInfo = true)
            }
            assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
            assertEquals(listOf("/search", "/new-book", "/toc", "/toc-two"), requests.toList())
            assertEquals("true", source.get("preFromBookInfo"))
            assertEquals("$base/new-book", book.bookUrl)
            assertEquals("$base/toc", book.tocUrl)
            assertEquals("new details", book.intro)
            assertEquals(7, book.durChapterIndex)
            assertEquals(19, book.durChapterPos)
            assertEquals("收藏", book.customTag)
            assertEquals(user, source.getUserNamespace())
            assertTrue(chapters.all { it.bookUrl == book.bookUrl })
        } finally { source.getScriptCache().delete("v_preFromBookInfo"); server.stop(0) }
    }

    @Test
    fun `FU URL option body and DNS setters normalize blank values`() {
        val options = AnalyzeUrl.UrlOption()
        options.setBodyJs("result.toUpperCase()")
        options.setDnsIp("127.0.0.1")
        options.setWebViewDelayTime("25")
        assertEquals("result.toUpperCase()", options.getBodyJs())
        assertEquals("127.0.0.1", options.getDnsIp())
        assertEquals(25L, options.getWebViewDelayTime())
        options.setBodyJs(" ")
        options.setDnsIp("")
        options.setWebViewDelayTime(null)
        assertNull(options.getBodyJs())
        assertNull(options.getDnsIp())
        assertEquals(0L, options.getWebViewDelayTime())
    }

    @Test
    fun `FU rates allow exactly N starts per interval and keep users independent`() = runBlocking {
        val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            starts.add(System.nanoTime())
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = source(base).apply { concurrentRate = "2/300" }
        val other = source.copy().apply { runtimeUserNamespace = "runtime-${UUID.randomUUID()}" }
        try {
            withTimeout(5_000) {
                coroutineScope { (1..3).map { async(kotlinx.coroutines.Dispatchers.IO) { AnalyzeUrl(base, source = source).getStrResponseAwait().body } }.awaitAll() }
            }
            assertEquals(3, starts.size)
            val values = starts.toList().sorted()
            assertTrue((values[2] - values[0]) / 1_000_000 >= 200, "Third request must wait for the next interval")
            // Exhaust a separate two-second window, then the other user must still finish promptly.
            source.putConcurrent("1/2000")
            AnalyzeUrl(base, source = source).getStrResponseAwait(skipRateLimit = true)
            assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
                runBlocking { withTimeout(100) { AnalyzeUrl(base, source = source).getStrResponseAwait() } }
            }
            other.concurrentRate = "1/2000"
            withTimeout(1_000) { AnalyzeUrl(base, source = other).getStrResponseAwait() }
            val secondUser = other.copy().apply { runtimeUserNamespace = other.getUserNamespace() }
            withTimeout(1_000) { AnalyzeUrl(base, source = secondUser).getStrResponseAwait(skipRateLimit = true) }
        } finally { source.getScriptCache().delete("concurrentRateOverride"); server.stop(0) }
    }
}
