package io.legado.app.model

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.BookSource
import io.legado.app.help.FontSourceBridge
import io.legado.app.help.http.CookieStore
import io.legado.app.model.analyzeRule.FontFixture
import io.legado.app.model.analyzeRule.QueryTTF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID

class SourceFontBridgeCompatibilityTests {
    private fun source() = BookSource(bookSourceUrl = "https://font.example", enabledCookieJar = true)
        .apply { runtimeUserNamespace = "font-${UUID.randomUUID()}" }

    @Test
    fun `FU accepts byte arrays base64 data URLs and private files with cache opt out`() {
        val source = source()
        val bytes = FontFixture.font(0x20000, 12)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val first = FontSourceBridge.query(source, bytes, true)
        assertNotNull(first)
        assertSame(first, FontSourceBridge.query(source, bytes.copyOf(), true))
        assertNotSame(first, FontSourceBridge.query(source, bytes, false))
        assertNotSame(first, FontSourceBridge.query(source().apply { bookSourceUrl = source.bookSourceUrl }, bytes, true))
        assertEquals(1, FontSourceBridge.query(source, encoded, true)!!.getGlyfIdByUnicode(0x20000))
        assertEquals(1, FontSourceBridge.query(source, "data:font/ttf;base64,$encoded", true)!!.getGlyfIdByUnicode(0x20000))
        val file = source.getFile("fixture.ttf")
        try {
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
            assertEquals(1, FontSourceBridge.query(source, "fixture.ttf", true)!!.getGlyfIdByUnicode(0x20000))
            file.writeBytes(FontFixture.font(0x4E00, 12))
            assertEquals(1, FontSourceBridge.query(source, "fixture.ttf", false)!!.getGlyfIdByUnicode(0x4E00))
            assertFalse(source().getFile("fixture.ttf").exists())
        } finally { file.delete() }
        assertNull(FontSourceBridge.query(source, null, true))
        assertNull(FontSourceBridge.query(source, 42, true))
    }

    @Test
    fun `FU replacement preserves supplementary characters spaces and optional filtering`() {
        val wrong = QueryTTF(FontFixture.font(0x20000, 12))
        val correct = QueryTTF(FontFixture.font(0x4E00, 12))
        val content = "𠀀𠀁X \u200B😀"
        assertEquals("一丁X \u200B😀", FontSourceBridge.replace(content, wrong, correct, false))
        assertEquals("一丁 \u200B", FontSourceBridge.replace(content, wrong, correct, true))
        assertEquals(content, FontSourceBridge.replace(content, null, correct, true))
        assertEquals("𠀀𠀁", FontSourceBridge.replace("一丁", correct, wrong, false))
    }

    @Test
    fun `imported FU scripts can call cache overloads and new glyph methods from Rhino`() {
        val source = source()
        val encoded = Base64.getEncoder().encodeToString(FontFixture.font(0x20000, 12))
        assertEquals("1|true|131072", source.evalScript("""
            var font = java.queryTTF('$encoded', false);
            [font.getGlyfIdByUnicode(131072), font.getGlyfByUnicode(131072) != null,
             font.getUnicodeByGlyf(font.getGlyfById(1))].join('|');
        """.trimIndent()))
        assertEquals(1.0, (source.evalScript("java.queryTTF(java.base64DecodeToByteArray('$encoded')).getGlyfIdByUnicode(131072)") as Number).toDouble())
        assertEquals(1.0, (source.evalScript("java.queryBase64TTF('$encoded').getGlyfIdByUnicode(131072)") as Number).toDouble())
        val correct = Base64.getEncoder().encodeToString(FontFixture.font(0x4E00, 12))
        assertEquals("一丁 ", source.evalScript("java.replaceFont('𠀀𠀁 X', java.queryTTF('$encoded'), java.queryTTF('$correct'), true)"))
    }

    @Test
    fun `font URL loading uses source login cookies options and cache bypass`() {
        val bytes = FontFixture.font(0x20000, 12)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String?, String?>>()
        server.createContext("/") { exchange ->
            requests.add(exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("Cookie"))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = source().apply { bookSourceUrl = base }
        val store = CookieStore.forSource(source)
        try {
            source.putLoginHeader("{\"Authorization\":\"Bearer font-session\"}")
            store.setCookie(base, "sid=font")
            val url = "$base/font.ttf,{\"headers\":{\"X-Font-Options\":\"true\"}}"
            val font = FontSourceBridge.query(source, url, true)
            assertEquals(1, font!!.getGlyfIdByUnicode(0x20000))
            assertSame(font, FontSourceBridge.query(source, url, true))
            assertEquals(1, requests.size)
            assertNotSame(font, FontSourceBridge.query(source, url, false))
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.first == "Bearer font-session" && it.second?.contains("sid=font") == true })
        } finally {
            source.removeLoginHeader()
            store.clear()
            server.stop(0)
        }
    }
}
