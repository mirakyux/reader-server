package io.legado.app.model

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.*
import io.legado.app.help.http.CookieStore
import io.legado.app.model.jsSource.SourceActions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

class SourceActionTests {
    @Test
    fun `purchase and callbacks use persisted rules and explicit actions`() {
        val source = BookSource(bookSourceUrl = "https://example.org", eventListener = true, customButton = true,
            ruleContent = ContentRule(payAction = "chapter.title === '付费章'", callBackJs = "cache.put('event', event); event === 'clickCustomButton'"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val book = Book(bookUrl = "https://example.org/book", name = "书")
        val chapter = BookChapter(title = "付费章", url = "https://example.org/chapter")
        val cache = io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace())
        try {
            assertThrows(IllegalArgumentException::class.java) { SourceActions.action(source, "pay", book, chapter, null, null, false) }
            assertNull(cache.get("event"))
            assertEquals(true, SourceActions.action(source, "pay", book, chapter, null, null, true)["success"])
            assertEquals(true, SourceActions.action(source, "event", book, chapter, "clickCustomButton", null, false)["handled"])
            assertEquals("clickCustomButton", cache.get("event"))
            assertThrows(IllegalArgumentException::class.java) { SourceActions.action(source, "event", book, chapter, "arbitrary", null, false) }
            source.getContentRule().payAction = "https://example.org/pay?chapter={{chapter.index}}"
            assertEquals("https://example.org/pay?chapter=0", SourceActions.action(source, "pay", book, chapter, null, null, true)["url"])
            source.eventListener = false
            assertEquals(false, SourceActions.action(source, "event", book, chapter, "startRead", null, false)["handled"])
        } finally { cache.delete("event") }
    }

    @Test
    fun `dynamic explore controls persist infoMap and cannot execute client code`() {
        val source = BookSource(bookSourceUrl = "https://example.org", exploreUrl = """@js:JSON.stringify([
            {title:'分类',url:'/books?kind='+infoMap.get('类型')},
            {title:'刷新',type:'button',action:"cache.put('clicked','yes'); infoMap.saveNow(); true"}])""",
            exploreScreen = """[{"title":"类型","type":"select","chars":["小说","历史"],"default":"小说","action":"infoMap.saveNow()"},{"title":"关键词","type":"text"}]""")
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        try {
            val ui = SourceActions.exploreUi(source)
            assertEquals("小说", (ui["values"] as Map<*, *>)["类型"])
            val changed = SourceActions.exploreAction(source, "filters", 0, "历史")
            assertEquals("历史", (changed["values"] as Map<*, *>)["类型"])
            val categories = changed["categories"] as List<*>
            assertEquals("/books?kind=历史", (categories.first() as Map<*, *>)["url"])
            SourceActions.exploreAction(source, "categories", 1, null)
            assertEquals("yes", io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace()).get("clicked"))
            assertThrows(IllegalArgumentException::class.java) { SourceActions.exploreAction(source, "filters", 0, "javascript:injected()") }
            val other = source.copy().apply { runtimeUserNamespace = UUID.randomUUID().toString() }
            assertEquals("小说", (SourceActions.exploreUi(other)["values"] as Map<*, *>)["类型"])
            other.getScriptCache().delete("exploreInfoMap")
        } finally {
            source.getScriptCache().delete("exploreInfoMap")
            io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace()).delete("clicked")
        }
    }

    @Test
    fun `review fetch quote and image decode reuse source headers and cookies`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val authorized = exchange.requestHeaders.getFirst("X-Source") == "fixture" &&
                exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("sid=reader==")
            val response = if (!authorized) "denied" else if (exchange.requestURI.path == "/image") "encoded" else
                "<section><p>段评正文</p><img src='/avatar.png'><time>今天</time></section>"
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(if (authorized) 200 else 403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base, header = """{"X-Source":"fixture"}""",
            coverDecodeJs = "java.base64DecodeToByteArray('iVBORw0KGgo=')",
            ruleContent = ContentRule(imageDecode = "[255,216,255]"),
            ruleReview = ReviewRule(reviewUrl = "/reviews?chapter={{chapter.index}}", reviewQuoteUrl = "/quote?id={{review.id}}",
                contentRule = "@css:p@text", avatarRule = "@css:img@src", postTimeRule = "@css:time@text"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val book = Book(bookUrl = "$base/book")
        val chapter = BookChapter(url = "$base/chapter", index = 3)
        try {
            CookieStore.forSource(source).setCookie(base, "sid=reader==")
            val result = SourceActions.reviews(source, book, chapter, false, emptyMap(), "段落", 1)
            assertEquals("$base/reviews?chapter=3", result["url"])
            val review = (result["reviews"] as List<*>).single() as Map<*, *>
            assertEquals("段评正文", review["content"])
            assertEquals("$base/avatar.png", review["avatar"])
            assertEquals("今天", review["postTime"])
            assertEquals("$base/quote?id=7", SourceActions.reviews(source, book, chapter, true, mapOf("id" to 7), null, null)["url"])
            val cover = SourceActions.image(source, "$base/image", true, book)
            assertEquals("image/png", cover["contentType"])
            assertEquals("iVBORw0KGgo=", cover["base64"])
            assertEquals("image/jpeg", SourceActions.image(source, "$base/image", false, book)["contentType"])
            source.coverDecodeJs = "({unsafe:'object'})"
            assertThrows(IllegalArgumentException::class.java) { runBlocking { SourceActions.image(source, "$base/image", true, book) } }
            Unit
        } finally { CookieStore.forSource(source).clear(); server.stop(0) }
    }
}
