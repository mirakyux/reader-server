package io.legado.app.model

import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.*
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.webBook.*
import io.legado.app.utils.GSON
import io.vertx.core.json.Json
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceRuleCompletenessTests {
    @Test
    fun `modern source rules and unknown fields survive JSON normalization and export`() {
        val raw = """{"bookSourceUrl":"https://example.org","bookSourceName":"modern","coverDecodeJs":"result","eventListener":true,"customButton":true,"exploreScreen":[],"futureFlag":{"count":7},"ruleSearch":{"checkKeyWord":"验证","futureSearch":"keep"},"ruleToc":{"formatJs":"title","isPay":"@css:.paid@text","futureToc":[1,2]},"ruleContent":{"title":"@css:h1@text","subContent":"@css:aside@text","imageDecode":"result","payAction":"true","callBackJs":"event","futureContent":true},"ruleReview":{"reviewUrl":"/reviews","futureReview":"keep"}}"""
        val source = BookSource.fromJson(raw).getOrThrow()
        assertEquals("验证", source.getCheckKeyword("default"))
        assertTrue(source.eventListener)
        assertTrue(source.customButton)
        assertEquals("result", source.coverDecodeJs)
        val exported = JsonParser.parseString(Json.encode(source)).asJsonObject
        assertEquals(7, exported.getAsJsonObject("futureFlag").get("count").asInt)
        assertEquals("keep", exported.getAsJsonObject("ruleSearch").get("futureSearch").asString)
        assertTrue(exported.getAsJsonObject("ruleContent").get("futureContent").asBoolean)
        assertEquals("keep", exported.getAsJsonObject("ruleReview").get("futureReview").asString)
        val restored = BookSource.fromJson(GSON.toJson(source)).getOrThrow()
        assertEquals(listOf(1.0, 2.0), restored.ruleToc!!.extraFields()["futureToc"])
        val emptyExtensions = BookSource.fromJson("""{"bookSourceUrl":"https://example.org","bookSourceName":"nullable","_extensions":null,"futureField":"retained","ruleContent":{"_extensions":null,"futureRule":true}}""").getOrThrow()
        assertEquals("retained", emptyExtensions.extraFields()["futureField"])
        assertEquals(true, emptyExtensions.ruleContent!!.extraFields()["futureRule"])
        source.ruleSearch!!.checkKeyWord = "https://invalid.example"
        assertEquals("fallback", source.getCheckKeyword("fallback"))
    }

    @Test
    fun `file source only needs search and details and resolves download URLs`() = runBlocking {
        val source = BookSource.fromJson("""
            var config={bookSourceUrl:'https://example.org',bookSourceName:'files',bookSourceType:3};
            function search(key,page){return [{name:'下载书',bookUrl:'/detail',downloadUrls:['/first.epub']}];}
            function getBookInfo(book){return {downloadUrls:['/book.epub','https://cdn.example/book.txt']};}
        """.trimIndent()).getOrThrow()
        val web = WebBook(source, false)
        val search = web.searchBook("书").single()
        assertEquals(listOf("https://example.org/first.epub"), search.toBook().downloadUrls)
        val book = web.getBookInfo(search.toBook())
        assertEquals(listOf("https://example.org/book.epub", "https://cdn.example/book.txt"), book.downloadUrls)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { web.getChapterList(book) } }
        assertTrue(BookSource.fromJson("var config={bookSourceUrl:'https://example.org',bookSourceName:'bad',bookSourceType:3}; function search(){return[];}").isFailure)
        Unit
    }

    @Test
    fun `declarative file source extracts download links without requiring a toc`() {
        val source = BookSource(bookSourceUrl = "https://example.org", bookSourceType = 3,
            ruleBookInfo = BookInfoRule(name = "@css:h1@text", downloadUrls = "@css:a@href"))
        val book = Book(bookUrl = "https://example.org/detail", type = 3)
        BookInfo.analyzeBookInfo(book, "<h1>文件书</h1><a href='/book.epub'>下载</a>", source, book.bookUrl, book.bookUrl, true)
        assertEquals(listOf("https://example.org/book.epub"), book.downloadUrls)
    }

    @Test
    fun `toc flags and formatting share gInt while index starts at one`() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://example.org", mainJs = "function formatTitle(n,index,title){return n+':'+index+':'+title;}", ruleToc = TocRule(
            chapterList = "@css:li", chapterName = "@css:a@text", chapterUrl = "@css:a@href",
            isVip = "@css:.vip@text", isPay = "@css:.paid@text", formatJs = "formatTitle(++gInt,index,title)"))
        val book = Book(bookUrl = "https://example.org/book", tocUrl = "https://example.org/toc")
        val chapters = BookChapterList.analyzeChapterList(book,
            "<ul><li><a href='/one'>第一章</a><b class='vip'>true</b><b class='paid'>true</b></li><li><a href='/two'>第二章</a></li></ul>",
            source, book.tocUrl, book.tocUrl)
        assertEquals(listOf("1:1:第一章", "2:2:第二章"), chapters.map { it.title })
        assertTrue(chapters.first().isVip)
        assertTrue(chapters.first().isPay)
        assertEquals("2:2:第二章", book.latestChapterTitle)
    }

    @Test
    fun `content rule overrides title and appends text subcontent`() = runBlocking {
        val source = BookSource(bookSourceUrl = "https://example.org", ruleContent = ContentRule(
            content = "@css:main@text", title = "@css:h1@text", subContent = "@css:aside@text"))
        val book = Book(bookUrl = "https://example.org/book")
        val chapter = BookChapter(title = "目录标题", url = "https://example.org/one")
        val content = BookContent.analyzeContent("<h1>正文标题</h1><main>主要正文</main><aside>作者的话</aside>",
            book, chapter, source, chapter.url, chapter.url)
        assertEquals("正文标题", chapter.title)
        assertTrue(content.contains("主要正文"))
        assertTrue(content.contains("作者的话"))
        assertEquals("作者的话", chapter.subContent)
        Unit
    }
}
