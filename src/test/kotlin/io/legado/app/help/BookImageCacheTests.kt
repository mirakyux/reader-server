package io.legado.app.help

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.http.CookieStore
import io.legado.app.model.jsSource.SourceActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BookImageCacheTests {
    @TempDir lateinit var directory: Path
    private var previousStorageRoot = ""
    @BeforeEach fun isolateStorage() {
        previousStorageRoot = com.htmake.reader.utils.storageFinalPath
        com.htmake.reader.utils.storageFinalPath = directory.resolve("storage").toString()
    }
    @AfterEach fun restoreStorage() { com.htmake.reader.utils.storageFinalPath = previousStorageRoot }

    private fun book(base: String, name: String = "图片书") = Book(bookUrl = "$base/book", name = name).apply {
        setRootDir(directory.toString())
        setUserNameSpace("fixture")
    }

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
        }

    @Test
    fun `all images on a line are decoded using source context without altering chapter text`() = runBlocking {
        val requests = AtomicInteger()
        val authenticatedRequests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            if (exchange.requestHeaders.getFirst("X-Source") == "fixture" &&
                exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("sid=image")) authenticatedRequests.incrementAndGet()
            val bytes = "encrypted".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base, header = """{"X-Source":"fixture"}""",
            mainJs = "function decoded(){ return [137,80,78,71,13,10,26,10]; }",
            ruleContent = ContentRule(imageDecode = """
                if (book.name !== '图片书' || chapter.title !== '图片章' || result.length !== 9 || src.indexOf('/images/') < 0) {
                    throw new Error('missing decode context');
                }
                decoded();
            """.trimIndent())).apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val book = book(base)
        val chapter = BookChapter(url = "chapters/1", baseUrl = "$base/", title = "图片章")
        val text = "原始正文 & <img src=\"../images/a.png?token=1\"><img src=\"../images/b.png?token=2\">\n<img src=\"../images/a.png?token=1\">"
        try {
            CookieStore.forSource(source).setCookie(base, "sid=image")
            BookHelp.saveContent(this, source, book, chapter, text)
            assertEquals(text, BookHelp.getContent(book, chapter))
            assertEquals(2, requests.get())
            assertEquals(2, authenticatedRequests.get())
            val decoded = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            listOf("$base/images/a.png?token=1", "$base/images/b.png?token=2").forEach { url ->
                assertEquals("png", BookHelp.getImageSuffix(url))
                assertArrayEquals(decoded, BookHelp.getImage(book, url).readBytes())
            }
        } finally { CookieStore.forSource(source).clear(); server.stop(0) }
    }

    @Test
    fun `decode failures reach caller leave no cache and allow retry`() = runBlocking {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            val bytes = "encrypted".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base, ruleContent = ContentRule(imageDecode = "[]"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val book = book(base)
        val url = "$base/image.jpg"
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { BookHelp.saveImage(source, book, url) } }
            assertFalse(BookHelp.getImage(book, url).exists())
            source.getContentRule().imageDecode = "[255,216,255]"
            BookHelp.saveImage(source, book, url)
            BookHelp.saveImage(source, book, url)
            assertEquals(2, requests.get())
            assertArrayEquals(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()), BookHelp.getImage(book, url).readBytes())
            assertFalse(BookHelp.getImage(book, url).parentFile.listFiles()!!.any { it.name.endsWith(".part") })
        } finally { server.stop(0) }
    }

    @Test
    fun `parallel requests share download but another book keeps its own decoded bytes`() = runBlocking {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(1) }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base,
            ruleContent = ContentRule(imageDecode = "book.name === '图片书' ? [1,2,3] : [4,5,6]"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val book = book(base)
        val other = book(base, "另一本书")
        val url = "$base/image.png"
        try {
            coroutineScope { (0 until 24).map { async(Dispatchers.Default) { BookHelp.saveImage(source, book, url) } }.awaitAll() }
            BookHelp.saveImage(source, other, url)
            assertEquals(2, requests.get())
            assertArrayEquals(byteArrayOf(1, 2, 3), BookHelp.getImage(book, url).readBytes())
            assertArrayEquals(byteArrayOf(4, 5, 6), BookHelp.getImage(other, url).readBytes())
        } finally { server.stop(0) }
    }

    @Test
    fun `cancelled image request leaves no cache and a retry can acquire its lock`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = server { exchange ->
            if (requests.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            }
            try {
                exchange.sendResponseHeaders(200, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            } finally { exchange.close() }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val book = book(base)
        val url = "$base/image.png"
        try {
            var completedNormally = false
            val download = launch { BookHelp.saveImage(null, book, url); completedNormally = true }
            withTimeout(5_000) { started.await() }
            download.cancelAndJoin()
            assertTrue(download.isCancelled)
            assertFalse(completedNormally)
            assertFalse(BookHelp.getImage(book, url).exists())
            release.countDown()
            withTimeout(5_000) { BookHelp.saveImage(null, book, url) }
            assertArrayEquals(byteArrayOf(1, 2, 3), BookHelp.getImage(book, url).readBytes())
        } finally { release.countDown(); server.stop(0) }
    }

    @Test
    fun `failed forced refresh preserves cached bytes then successful refresh replaces them`() = runBlocking {
        val server = server { exchange ->
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val source = BookSource(bookSourceUrl = base, ruleContent = ContentRule(imageDecode = "[4,5,6]"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val book = book(base)
        val url = "$base/image.png"
        try {
            BookHelp.saveImage(source, book, url)
            source.getContentRule().imageDecode = "[]"
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { BookHelp.saveImage(source, book, url, refresh = true) }
            }
            assertArrayEquals(byteArrayOf(4, 5, 6), BookHelp.getImage(book, url).readBytes())
            source.getContentRule().imageDecode = "[7,8,9]"
            BookHelp.saveImage(source, book, url, refresh = true)
            assertArrayEquals(byteArrayOf(7, 8, 9), BookHelp.getImage(book, url).readBytes())
            assertFalse(BookHelp.getImage(book, url).parentFile.listFiles()!!.any { it.name.endsWith(".part") })
        } finally { server.stop(0) }
    }

    @Test
    fun `decoding existing cover bytes uses cover rule without a request`() {
        val source = BookSource(bookSourceUrl = "http://invalid.fixture",
            coverDecodeJs = "src === 'cached-cover' && result.length === 3 ? [255,216,255] : []",
            ruleContent = ContentRule(imageDecode = "[1,2,3]"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val input = byteArrayOf(7, 8, 9)
        assertArrayEquals(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()),
            SourceActions.decodeImage(source, "cached-cover", input, true, null))
        assertArrayEquals(byteArrayOf(1, 2, 3), SourceActions.decodeImage(source, "cached-cover", input, false, null))
        assertArrayEquals(input, SourceActions.decodeImage(null, "cached-cover", input, true, null))
    }

    @Test
    fun `data images decode locally preserve binary octets and reject invalid payloads`() = runBlocking {
        val source = BookSource(bookSourceUrl = "http://invalid.fixture",
            ruleContent = ContentRule(imageDecode = "src.indexOf('data:image/') === 0 && result.length === 9 ? [255,216,255] : []"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val encoded = "data:image/jpeg;base64,ZW5jcnlwdGVk"
        assertArrayEquals(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()),
            SourceActions.imageBytes(source, encoded, false, null))
        assertEquals("image/jpeg", SourceActions.image(source, encoded, false, null)["contentType"])
        assertArrayEquals(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 43, 43),
            SourceActions.imageBytes(null, "data:image/jpeg,%FF%D8%FF%2B+", false, null))
        listOf("data:image/png;base64,", "data:image/png;base64,%%broken", "data:image/png,%XY",
            "data:image/png;base64,broken!", "data:image/png", "data:text/plain,body").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { SourceActions.imageBytes(source, invalid, false, null) }
            }
        }
    }

    @Test
    fun `suffix comes from URI path independently of query fragment and request options`() {
        assertEquals("webp", BookHelp.getImageSuffix("https://fixture/images/1.WEBP?signature=a.jpg#fragment"))
        assertEquals("png", BookHelp.getImageSuffix("https://fixture/1.png,{\"headers\":{\"X-Key\":\"token\"}}"))
        assertEquals("jpg", BookHelp.getImageSuffix("https://fixture/no-extension?format=.png"))
        assertEquals("jpg", BookHelp.getImageSuffix("data:image/png;base64,AA=="))
    }
}
