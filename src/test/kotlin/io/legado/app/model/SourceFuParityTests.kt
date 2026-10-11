package io.legado.app.model

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.*
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.SourceLogin
import io.legado.app.model.jsSource.SourceValidation
import io.legado.app.model.jsSource.SourceScriptRuntime
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress

class SourceFuParityTests {
    @Test fun `minimal modern source retains browser headers and cookie preferences without content rules`() {
        val source = BookSource.fromJson("""{"bookSourceUrl":"https://browser.example","bookSourceName":"minimal","header":"{\"X-Scope\":\"book\"}","enabledCookieJar":false,"customOrder":9,"enabled":false}""", "fixture").getOrThrow()
        assertEquals("{\"X-Scope\":\"book\"}", source.header)
        assertEquals(false, source.enabledCookieJar)
        assertEquals(9, source.customOrder)
        assertFalse(source.enabled)
        assertEquals("fixture", source.runtimeUserNamespace)
        assertNull(source.ruleToc)
    }

    private fun fixture(test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val text = when (path) {
                "/toc" -> "<a href='/one'>第一章</a>"
                "/one" -> "<main>original</main><a href='/two'>下一页</a>"
                else -> "<main>second</main>"
            }.toByteArray()
            if (exchange.requestMethod == "HEAD") { exchange.sendResponseHeaders(200, -1); exchange.close() }
            else { exchange.sendResponseHeaders(200, text.size.toLong()); exchange.responseBody.use { it.write(text) } }
        }
        server.start()
        try { test("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }

    @Test fun `toc preprocessing changes the requested URL before refresh`() = fixture { base ->
        val source = BookSource(bookSourceUrl = base, ruleToc = TocRule(preUpdateJs = "book.tocUrl = book.bookUrl + '/toc'",
            chapterList = "@css:a", chapterName = "text", chapterUrl = "href"))
        val book = Book(bookUrl = base, tocUrl = "$base/invalid")
        val result = runBlocking { WebBook(source, false).getChapterList(book) }
        assertEquals("第一章", result.single().title)
        assertEquals("$base/toc", book.tocUrl)
    }

    @Test fun `login check executes on all content pages with chapter context`() = fixture { base ->
        val source = BookSource(bookSourceUrl = base, loginCheckJs = "book.putVariable('checked', (book.getVariable('checked') || '') + chapter.title); result",
            ruleContent = ContentRule(content = "@css:main@text", nextContentUrl = "@css:a@href"))
        val book = Book(bookUrl = base, tocUrl = base)
        val text = runBlocking { WebBook(source, false).getBookContent(book, BookChapter(url = "$base/one", title = "verified")) }
        assertEquals("original\nsecond", text.trim())
        assertEquals("verifiedverified", book.getVariable("checked"))
    }

    @Test fun `HEAD query and bodyJs use URL options`() = fixture { base ->
        assertEquals(200, AnalyzeUrl("$base/one?x=1,{\"method\":\"HEAD\"}").getStrResponse().code())
        assertEquals("replaced", AnalyzeUrl("$base/one,{\"bodyJs\":\"'replaced'\"}").getStrResponse().body)
    }

    @Test fun `traditional dynamic login exposes chars and labels`() {
        val source = BookSource(bookSourceUrl = "https://example.org", loginUi = "@js:JSON.stringify([{name:'地区',type:'select',chars:['甲','乙'],viewName:\"'选择地区'\"},{name:'发送',type:'button',countdown:30}])")
        val rows = SourceLogin.ui(source)["rows"] as List<*>
        val first = rows.first() as Map<*, *>
        assertEquals("选择地区", first["label"])
        assertEquals(2, (first["options"] as List<*>).size)
        assertEquals(30, (rows.last() as Map<*, *>)["countdown"])
    }

    @Test fun `legacy login actions use only saved control code and update rendered form`() {
        val source = BookSource(bookSourceUrl = "https://login.example", loginUrl = "@js:function login(){source.putLoginHeader(JSON.stringify({'Authorization':'Bearer '+result.token}));}",
            loginUi = """[{"name":"token","type":"text"},{"name":"send","type":"button","action":"java.upLoginData({token:'issued'});java.reLoginView()"}]""")
            .apply { runtimeUserNamespace = java.util.UUID.randomUUID().toString() }
        try {
            val action = SourceLogin.login(source, mapOf("token" to "old"), "send", emptyMap())
            assertEquals(false, action["close"])
            assertEquals("issued", (action["form"] as Map<*, *>)["token"])
            SourceLogin.login(source, mapOf("token" to "issued"), null, emptyMap())
            assertEquals("Bearer issued", source.getLoginHeaderMap()!!["Authorization"])
            assertThrows(IllegalStateException::class.java) { SourceLogin.login(source, emptyMap(), "java.deleteFile('anything')", emptyMap()) }
        } finally { source.removeLoginInfo(); source.removeLoginHeader() }
    }

    @Test fun `login check can recover a failed content request`() = fixture { base ->
        val unavailable = java.net.ServerSocket(0).use { it.localPort }
        val source = BookSource(bookSourceUrl = base, loginCheckJs = "result.code() === 500 ? java.connect('$base/two') : result",
            ruleContent = ContentRule(content = "@css:main@text"))
        val text = runBlocking { WebBook(source, false).getBookContent(Book(bookUrl = base), BookChapter(url = "http://127.0.0.1:$unavailable/one")) }
        assertEquals("second", text.trim())
    }

    @Test fun `custom DNS preserves URL host and timeout and byte bridges execute`() = fixture { base ->
        val source = BookSource(bookSourceUrl = base)
        val port = java.net.URI(base).port
        val response = AnalyzeUrl("http://fixture.invalid:$port/two,{\"dnsIp\":\"127.0.0.1\",\"timeout\":3000}", source = source).getStrResponse()
        assertTrue(response.body!!.contains("second"))
        assertEquals("字节", source.bytesToStr(source.strToBytes("字节", "UTF-8")))
        assertEquals("字节", source.bytesToStr(source.hexDecodeToByteArray(source.byteArrayToHex(source.strToBytes("字节")))))
        assertEquals(2, source.ajaxTestAll(arrayOf("$base/one", "$base/two"), 3000).size)
        assertTrue(source.ajaxTestAll(arrayOf("$base/two"), 3000).single().getCallTime() >= 0)
    }

    @Test fun `full validation covers details toc and body instead of search only`() = runBlocking {
        val source = BookSource.fromJson("""
            var config={bookSourceUrl:'https://example.org',bookSourceName:'complete'};
            function search(){return [{name:'Book',bookUrl:'/book'}];}
            function getBookInfo(book){return {name:'Book',tocUrl:'/toc'};}
            function getChapters(book){return [{title:'One',url:'/one'}];}
            function getContent(){return 'Actual body';}
        """.trimIndent()).getOrThrow()
        val result = SourceValidation.check(source, "book")
        assertEquals(true, result["success"])
        assertEquals(listOf("search", "explore", "details", "toc", "content"), (result["stages"] as List<*>).map { (it as Map<*, *>)["stage"] })
        source.mainJs = source.mainJs!!.replace("return 'Actual body'", "return ''")
        assertEquals(false, SourceValidation.check(source, "book")["success"])
    }

    @Test fun `existing 7zip extracts actual source archive bytes in its own cache`() {
        val tool = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it, "7z.exe") }.firstOrNull { it.isFile }
        assumeTrue(tool != null, "7-Zip runtime is optional")
        val source = BookSource(bookSourceUrl = "https://archive.example").apply { runtimeUserNamespace = java.util.UUID.randomUUID().toString() }
        val input = source.getFile("fixture/chapter.txt").apply { parentFile.mkdirs(); writeText("完整中文正文") }
        val archive = source.getFile("fixture.7z")
        val process = ProcessBuilder(tool!!.absolutePath, "a", "-t7z", archive.absolutePath, input.name).directory(input.parentFile).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
        val output = source.un7zFile("fixture.7z")
        try { assertEquals("完整中文正文", source.readTxtFile("$output/chapter.txt", "UTF-8")) }
        finally { source.getFile(output).deleteRecursively(); input.parentFile.deleteRecursively(); archive.delete() }
    }

    @Test fun `cancelled debug job interrupts CPU bound Rhino loops`() {
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking {
                kotlinx.coroutines.withTimeout(100) {
                    val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + SourceScriptRuntime.cancellationContext(job)) {
                        BookSource(bookSourceUrl = "https://cancel.example").evalScript("while(true) {}")
                    }
                }
            }
        }
    }
}
