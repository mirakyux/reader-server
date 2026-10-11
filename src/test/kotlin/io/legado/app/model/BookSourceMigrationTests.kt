package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeByJSoup
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.jsSource.SourceLogin
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.source.SourceStorage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookSourceMigrationTests {
    @Test
    fun `ordinary script cache retains text and delete removes it`() {
        val key = "migration-${UUID.randomUUID()}"
        CacheManager.put(key, "token=value", 0)
        try {
            assertEquals("token=value", CacheManager.get(key))
        } finally {
            CacheManager.delete(key)
        }
        assertNull(CacheManager.get(key))
    }

    @Test
    fun `manual cookies preserve values containing equals`() {
        val url = "https://${UUID.randomUUID()}.example.org/login"
        try {
            CookieStore.setCookie(url, "session=abc==; token=hello")
            assertEquals("abc==", CookieStore.cookieToMap(CookieStore.getCookie(url))["session"])
            CookieStore.removeCookie(url)
            assertEquals("", CookieStore.getCookie(url))
        } finally {
            CookieStore.removeCookie(url)
        }
    }

    @Test
    fun `modern source retains nested declarative rules`() {
        val source = BookSource.fromJson("""{"bookSourceUrl":"https://example.org","bookSourceName":"fixture","ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},"ruleToc":{"chapterList":"a","chapterName":"text","chapterUrl":"href"}}""").getOrThrow()
        assertEquals(".book", source.getSearchRule().bookList)
        assertEquals("a", source.getTocRule().chapterList)
    }

    @Test
    fun `legacy source still converts search and content fields`() {
        val source = BookSource.fromJson("""{"bookSourceUrl":"https://example.org","bookSourceName":"legacy","ruleSearchUrl":"https://example.org/search?key={{key}}","ruleSearchList":"class.book","ruleBookContent":"class.content@text"}""").getOrThrow()
        assertTrue(source.searchUrl!!.contains("search"))
        assertNotNull(source.getContentRule().content)
    }

    @Test
    fun `script cache and cookies are isolated by both user and source`() {
        val first = BookSource(bookSourceUrl = "https://same.example.org/first").apply { runtimeUserNamespace = "alice-${UUID.randomUUID()}" }
        val second = first.copy(bookSourceUrl = "https://same.example.org/second").apply { runtimeUserNamespace = first.runtimeUserNamespace }
        val otherUser = first.copy().apply { runtimeUserNamespace = "bob-${UUID.randomUUID()}" }
        val cookieUrl = "https://same.example.org/"
        try {
            first.getScriptCache().put("state", "alice")
            assertNull(second.getScriptCache().get("state"))
            assertNull(otherUser.getScriptCache().get("state"))
            CookieStore.forSource(first).setCookie(cookieUrl, "session=alice==")
            assertEquals("session=alice==", CookieStore.forSource(first).getCookie(cookieUrl))
            assertEquals("", CookieStore.forSource(second).getCookie(cookieUrl))
            assertEquals("", CookieStore.forSource(otherUser).getCookie(cookieUrl))
        } finally {
            first.getScriptCache().delete("state")
            listOf(first, second, otherUser).forEach { CookieStore.forSource(it).clear() }
        }
    }

    @Test
    fun `Java bridge cannot switch source identity or instantiate a different cache`() {
        val source = BookSource(bookSourceUrl = "https://example.org/original").apply { runtimeUserNamespace = "alice" }
        listOf(
            "source.setRuntimeUserNamespace('bob')", "source.runtimeUserNamespace='bob'",
            "source.setBookSourceUrl('https://example.org/other')", "source.bookSourceUrl='https://example.org/other'",
            "source.copy()", "source.getScriptCache()", "source.getClass()",
            "new Packages.io.legado.app.help.source.SourceCache('other')"
        ).forEach { script -> assertThrows(Exception::class.java) { source.evalScript(script) } }
        assertEquals("alice", source.runtimeUserNamespace)
        assertEquals("https://example.org/original", source.bookSourceUrl)
        assertEquals("function", source.evalScript("typeof Packages.org.jsoup.Jsoup.parse"))
    }

    @Test
    fun `script cache expiry and permanent values have distinct semantics`() {
        val key = "ttl-${UUID.randomUUID()}"
        try {
            CacheManager.put(key, "expired", 1)
            assertEquals("expired", CacheManager.get(key))
            Thread.sleep(1100)
            assertNull(CacheManager.get(key))
            CacheManager.putFile(key, "permanent", 0)
            assertEquals("permanent", CacheManager.getFile(key))
            CacheManager.put(key, byteArrayOf(1, 2, 3), 0)
            Thread.sleep(10)
            assertArrayEquals(byteArrayOf(1, 2, 3), CacheManager.getByteArray(key))
        } finally { CacheManager.delete(key) }
    }

    @Test
    fun `raw script and modern JSON retain config libraries and contract`() {
        val script = script("https://example.org")
        val source = BookSource.fromJson(script).getOrThrow()
        assertEquals(script, source.mainJs)
        source.jsLib = "function label() { return '图书'; }"
        source.loginUi = """[{"name":"账号","type":"text"}]"""
        val restored = BookSource.fromJson(GSON.toJson(source)).getOrThrow()
        assertEquals(source.mainJs, restored.mainJs)
        assertEquals(source.jsLib, restored.jsLib)
        assertEquals(source.loginUi, restored.loginUi)
        assertTrue(BookSource.fromJson("var config={bookSourceUrl:'https://example.org',bookSourceName:'invalid'}; function search(){return[];}").isFailure)
        assertTrue(BookSource.fromJson("var config=;").isFailure)
    }

    @Test
    fun `declarative rules can call shared library and source scoped cache`() {
        val source = BookSource(bookSourceUrl = "https://example.org/${UUID.randomUUID()}", jsLib = "function shared(value){return value.toUpperCase();}")
        val cache = io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace())
        cache.put("label", "reader")
        try {
            val rule = AnalyzeRule(Book(), source).setContent("reader", source.getKey())
            assertEquals("READER", rule.getString("@js:shared(cache.get('label'))"))
        } finally { cache.delete("label") }
    }

    @Test
    fun `non string content and Java process access are rejected`() = runBlocking {
        val source = BookSource.fromJson(script("https://example.org").replace("return java.ajax(chapter.url);", "return {content:'unexpected'};")).getOrThrow()
        val web = WebBook(source, false)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { web.getBookContent(Book(), BookChapter(title = "a", url = "https://example.org/c")) } }
        assertThrows(Exception::class.java) { source.evalScript("Packages.java.lang.Runtime.getRuntime()") }
        assertThrows(Exception::class.java) { source.evalScript("Packages.io.legado.app.utils.FileUtils.getCachePath()") }
        assertThrows(Exception::class.java) { source.evalScript("Packages.java.util.zip.ZipFile('/private-data')") }
        Unit
    }

    @Test
    fun `file paths cannot escape the user source cache`() {
        val root = File(System.getProperty("java.io.tmpdir"), "reader-tests")
        val namespace = SourceStorage.namespace("alice", "https://example.org")
        assertThrows(IllegalArgumentException::class.java) { SourceStorage.file(root, namespace, "../../other") }
        assertTrue(SourceStorage.file(root, namespace, "chapter.txt").path.contains(namespace))
    }

    @Test
    fun `script downloads and unzip stay inside their source and reject traversal entries`() {
        val source = BookSource(bookSourceUrl = "https://example.org/${UUID.randomUUID()}")
        val other = BookSource(bookSourceUrl = "https://example.org/${UUID.randomUUID()}")
        fun zip(name: String): String = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { archive ->
                archive.putNextEntry(ZipEntry(name))
                archive.write("正文".toByteArray())
                archive.closeEntry()
            }
        }.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        val path = source.downloadFile(zip("chapter.txt"), "https://example.org/book,{\"type\":\"zip\"}")
        val malicious = source.downloadFile(zip("../../../other.txt"), "https://example.org/bad,{\"type\":\"zip\"}")
        try {
            assertNull(other.readFile(path))
            val unpacked = source.unzipFile(path)
            assertEquals("正文", source.readTxtFile("$unpacked/chapter.txt", "UTF-8"))
            source.deleteFile(unpacked)
            assertThrows(IllegalArgumentException::class.java) { source.unzipFile(malicious) }
            assertThrows(Exception::class.java) { source.evalScript("java.getFile('chapter.txt').getParentFile()") }
        } finally {
            source.deleteFile(malicious)
            source.deleteFile("unzipped/${source.md5Encode16(malicious)}")
        }
    }

    @Test
    fun `built in CryptoJS hashes and generates secure random bytes without node modules`() {
        val source = BookSource(bookSourceUrl = "https://example.org")
        assertEquals("5d41402abc4b2a76b9719d911017c592", source.evalScript("CryptoJS.MD5('hello').toString()"))
        val first = source.evalScript("CryptoJS.lib.WordArray.random(32).toString()") as String
        val second = source.evalScript("CryptoJS.lib.WordArray.random(32).toString()") as String
        assertEquals(64, first.length)
        assertNotEquals(first, second)
    }

    @Test
    fun `XML book source parsing preserves case sensitive element names`() {
        val parser = AnalyzeByJSoup("<?xml version=\"1.0\"?><Catalog><Entry>正文</Entry></Catalog>")
        assertEquals("Entry", parser.getElements("tag.Entry").single().tagName())
    }

    @Test
    fun `out of bounds list slices are empty instead of selecting boundary chapter`() {
        val parser = AnalyzeByJSoup("<ul><li>一</li><li>二</li><li>三</li></ul>")
        assertTrue(parser.getElements("tag.li[5:7]").isEmpty())
        assertTrue(parser.getElements("tag.li[-7:-5]").isEmpty())
        assertEquals(listOf("三", "二", "一"), parser.getElements("tag.li[-1:0]").map { it.text() })
        assertEquals(listOf("三", "二", "一"), parser.getElements("tag.li[-1:0:-1]").map { it.text() })
        assertEquals(listOf("三", "一"), parser.getElements("tag.li[-1:0:-2]").map { it.text() })
        assertEquals(listOf("一", "二", "三"), parser.getElements("tag.li[0:-1:0]").map { it.text() })
    }

    @Test
    fun `remote library bootstraps before dynamic headers and keeps login cookies`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val downloads = AtomicInteger()
        server.createContext("/") { exchange ->
            val response = if (exchange.requestURI.path == "/library.js") {
                downloads.incrementAndGet()
                if (exchange.requestHeaders.getFirst("Authorization") == "fixture-token" &&
                    exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("sid=reader==") &&
                    exchange.requestHeaders.getFirst("X-Sign") == null) "function signed(){return 'from-library';}"
                else "throw new Error('bootstrap lost authentication');"
            } else exchange.requestHeaders.getFirst("X-Sign").orEmpty()
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base, jsLib = "$base/library.js",
            header = "@js:JSON.stringify({'X-Sign':signed()})").apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        try {
            source.putLoginHeader("""{"Authorization":"fixture-token"}""")
            CookieStore.forSource(source).setCookie(base, "sid=reader==")
            assertEquals("from-library", source.evalScript("java.ajax('$base/chapter')"))
            assertEquals(1, downloads.get())
            assertEquals("fixture-token", source.evalScript("java.connect('$base/chapter').headers().get('missing') || source.getLoginHeaderMap().get('Authorization')"))
        } finally {
            source.getScriptCache().delete(source.md5Encode16(source.jsLib!!))
            source.removeLoginHeader()
            CookieStore.forSource(source).clear()
            server.stop(0)
        }
    }

    @Test
    fun `failed remote library downloads throw and are never cached as script text`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val downloads = AtomicInteger()
        server.createContext("/library.js") { exchange ->
            downloads.incrementAndGet()
            val bytes = "throw new Error('unavailable');".toByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base, jsLib = "$base/library.js").apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        try {
            repeat(2) { assertThrows(IllegalStateException::class.java) { source.evalScript("42") } }
            assertEquals(2, downloads.get())
            assertNull(source.getScriptCache().getFile(source.md5Encode16(source.jsLib!!)))
        } finally { server.stop(0) }
    }

    @Test
    fun `Jsoup get and post bridges share scoped cookies and honor disabled saving`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "${exchange.requestMethod.lowercase()}=saved; Path=/")
            val bytes = exchange.requestHeaders.getFirst("Cookie").orEmpty().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base).apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val other = source.copy().apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        try {
            CookieStore.forSource(source).setCookie(base, "manual=one==")
            assertEquals("manual=one==", source.evalScript("java.get('$base',{}).body()"))
            val posted = source.evalScript("java.post('$base','request',{'Cookie':'custom=two=='}).body()").toString()
            assertTrue(posted.contains("manual=one=="))
            assertTrue(posted.contains("get=saved"))
            assertTrue(posted.contains("custom=two=="))
            assertTrue(CookieStore.forSource(source).getCookie(base).contains("post=saved"))
            assertEquals("", CookieStore.forSource(other).getCookie(base))
            CookieStore.forSource(source).clear()
            source.enabledCookieJar = false
            source.evalScript("java.get('$base',{}).body()")
            assertEquals("", CookieStore.forSource(source).getCookie(base))
        } finally {
            CookieStore.forSource(source).clear()
            server.stop(0)
        }
    }

    @Test
    fun `dynamic source login renders selects updates state and field errors`() {
        val base = "https://example.org/${UUID.randomUUID()}"
        val raw = script(base) + """
            function loginUi(state) {
                return {rows:[{name:'账号',key:'user',type:'text'},{name:'模式',key:'mode',type:'select',options:['普通','高级']},{name:'登录',type:'button',action:'login'}]};
            }
            function loginAction(action,state,form) {
                return form.user ? {state:{user:form.user},login:form,close:true} : {state:{attempt:1},error:{user:'请输入账号'}};
            }
        """.trimIndent()
        val source = BookSource.fromJson(raw).getOrThrow()
        try {
            assertEquals(2, SourceLogin.ui(source)["version"])
            val failure = SourceLogin.login(source, emptyMap(), "login", emptyMap())
            assertEquals(false, failure["success"])
            assertEquals(mapOf("user" to "请输入账号"), failure["errors"])
            assertNotNull(failure["rows"])
            val success = SourceLogin.login(source, mapOf("user" to "reader"), "login", emptyMap())
            assertEquals(true, success["close"])
            assertTrue(source.getLoginInfo()!!.contains("reader"))
        } finally { source.getScriptCache().delete("loginInfo") }
    }

    @Test
    fun `automatic cookies honor URL path secure flag expiry and disabled jar`() = runBlocking {
        val source = BookSource(bookSourceUrl = "http://example.org/${UUID.randomUUID()}")
        val cookies = CookieStore.forSource(source)
        try {
            val origin = okhttp3.HttpUrl.Builder().scheme("http").host("example.org").addPathSegment("private").build()
            cookies.saveFromResponse(origin, listOf(
                okhttp3.Cookie.parse(origin, "only=private; Path=/private")!!,
                okhttp3.Cookie.parse(origin, "secure=value; Secure; Path=/")!!,
                okhttp3.Cookie.parse(origin, "expired=gone; Max-Age=0; Path=/")!!))
            assertEquals("only=private", cookies.getCookie("http://example.org/private/chapter"))
            assertEquals("", cookies.getCookie("http://example.org/public"))
            assertTrue(cookies.getCookie("https://example.org/private/chapter").contains("secure=value"))
            val client = io.legado.app.help.http.getProxyClient(source = source.apply { enabledCookieJar = false })
            val jar = client.cookieJar
            jar.saveFromResponse(origin, listOf(okhttp3.Cookie.parse(origin, "disabled=ignored; Path=/")!!))
            assertFalse(cookies.getCookie("http://example.org/private").contains("disabled"))
        } finally { cookies.clear() }
    }

    @Test
    fun `raw JavaScript source logs in follows cookie redirects and reads full book`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            var response = when (exchange.requestURI.path) {
                "/login" -> {
                    exchange.responseHeaders.add("Set-Cookie", "sid=alice==; Path=/; HttpOnly")
                    exchange.responseHeaders.add("Location", "/session")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                    return@createContext
                }
                "/session" -> exchange.requestHeaders.getFirst("Cookie").orEmpty()
                "/search" -> """[{"name":"测试书","author":"作者","bookUrl":"/book","tocUrl":"/toc"}]"""
                "/book" -> """{"intro":"书籍介绍","tocUrl":"/toc"}"""
                "/toc" -> """[{"title":"第一章","url":"/chapter"},{"title":"第二章","url":"/chapter2"}]"""
                "/chapter" -> "第一章正文"
                "/gbk" -> "编码正文"
                else -> "不存在"
            }
            if (exchange.requestURI.path != "/login" && exchange.requestURI.path != "/gbk" &&
                exchange.requestURI.path != "/session" && !exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("sid=alice==")) {
                response = "缺少登录Cookie"
            }
            val encoding = if (exchange.requestURI.path == "/gbk") "GBK" else "UTF-8"
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=$encoding")
            val bytes = response.toByteArray(Charset.forName(encoding))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource.fromJson(script(base)).getOrThrow().apply { runtimeUserNamespace = "fixture-${UUID.randomUUID()}" }
        try {
            source.jsLib = "function page(url) { return JSON.parse(java.ajax(url)); }"
            source.loginUi = """[{"name":"账号","type":"text"},{"name":"密码","type":"password"}]"""
            val ui = SourceLogin.ui(source)
            assertEquals(1, ui["version"])
            assertTrue(SourceLogin.login(source, mapOf("账号" to "alice", "密码" to "secret"), null, emptyMap())["success"] == true)
            assertEquals("sid=alice==", io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace()).get("loginResult"))
            assertTrue(source.getLoginInfo()!!.contains("secret"))
            assertFalse(source.getScriptCache().get("loginInfo")!!.contains("secret"))
            val web = WebBook(source, false, userNameSpace = source.runtimeUserNamespace)
            val found = web.searchBook("书", 1)
            assertEquals("测试书", found.single().name)
            assertEquals("测试书", web.exploreBook("$base/search").single().name)
            val book = web.getBookInfo(found.single().toBook())
            assertEquals("书籍介绍", book.intro)
            val chapters = web.getChapterList(book)
            assertEquals(listOf(0, 1), chapters.map { it.index })
            assertEquals("$base/chapter", chapters.first().url)
            assertEquals("第一章正文", web.getBookContent(book, chapters.first(), chapters.last().url))
            assertEquals("编码正文", AnalyzeUrl("$base/gbk", source = source).getStrResponseAwait().body)
            val isolated = source.copy().apply { runtimeUserNamespace = "other-${UUID.randomUUID()}" }
            assertEquals("", CookieStore.forSource(isolated).getCookie(base))
        } finally {
            CookieStore.forSource(source).clear()
            listOf("loginInfo", "loginResult").forEach { source.getScriptCache().delete(it) }
            io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace()).delete("loginResult")
            server.stop(0)
        }
    }

    private fun script(base: String) = """
        var config={bookSourceUrl:'$base',bookSourceName:'JS fixture',exploreUrl:[{title:'发现',url:'$base/search'}]};
        function search(key,pageNumber){return page(config.bookSourceUrl+'/search');}
        function explore(url,pageNumber){return page(url);}
        function getBookInfo(book){return page(book.bookUrl);}
        function getChapters(book){return page(book.tocUrl);}
        function getContent(chapter,book,nextChapterUrl){return java.ajax(chapter.url);}
        function login(){cache.put('loginResult',java.ajax(config.bookSourceUrl+'/login'));}
    """.trimIndent()
}
