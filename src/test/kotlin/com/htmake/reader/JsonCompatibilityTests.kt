package com.htmake.reader

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Public JSON contracts shared by API responses, stored shelves and exported sources. */
class JsonCompatibilityTests {
    private val privateFields = listOf(
        "runtimeUserNamespace", "userNamespace", "storageNamespace", "scriptCache", "source", "headerMap"
    )

    @Test
    fun `book source exports keep unknown source and nested rule fields without private identity`() {
        val source = Json.decodeValue("""
            {"bookSourceUrl":"https://json.example/books","bookSourceName":"中文书源 📚",
             "futureFlag":{"enabled":true,"labels":["简体","繁體"],"nullable":null},
             "ruleSearch":{"bookList":"@css:article","futureSearch":{"mode":"保留"}},
             "ruleToc":{"chapterName":"text","futureToc":[1,2]},
             "ruleContent":{"content":"@css:main@text","futureContent":true}}
        """.trimIndent(), BookSource::class.java)
        source.runtimeUserNamespace = "json-private-${UUID.randomUUID()}"

        val exported = JsonObject(Json.encode(source))
        assertEquals("中文书源 📚", exported.getString("bookSourceName"))
        val future = exported.getJsonObject("futureFlag")
        assertTrue(future.getBoolean("enabled"))
        assertEquals(listOf("简体", "繁體"), future.getJsonArray("labels").list)
        assertTrue(future.containsKey("nullable"))
        assertNull(future.getValue("nullable"))
        assertEquals("保留", exported.getJsonObject("ruleSearch").getJsonObject("futureSearch").getString("mode"))
        assertEquals(listOf(1, 2), exported.getJsonObject("ruleToc").getJsonArray("futureToc").list)
        assertTrue(exported.getJsonObject("ruleContent").getBoolean("futureContent"))
        assertFalse(exported.containsKey("_extensions"), "Extension fields stay at their original JSON level")
        privateFields.forEach { assertFalse(exported.containsKey(it), "Source export leaks $it") }
        assertFalse(exported.encode().contains(source.runtimeUserNamespace))
    }

    @Test
    fun `RSS JSON envelopes retain forward fields and typed source responses omit runtime identity`() {
        // RSS import envelopes use JSON objects; RssSource does not currently retain typed extension fields.
        val envelope = JsonObject("""
            {"sources":[{"sourceUrl":"https://json.example/rss","sourceName":"订阅 📰",
             "futureRss":{"version":2,"categories":["新闻","科技"],"enabled":false}}]}
        """.trimIndent())
        val restored = JsonObject(Json.encode(envelope)).getJsonArray("sources").getJsonObject(0)
        assertEquals("订阅 📰", restored.getString("sourceName"))
        assertEquals(2, restored.getJsonObject("futureRss").getInteger("version"))
        assertEquals(listOf("新闻", "科技"), restored.getJsonObject("futureRss").getJsonArray("categories").list)
        assertFalse(restored.getJsonObject("futureRss").getBoolean("enabled"))

        val source = JsonObject().put("sourceUrl", restored.getString("sourceUrl"))
            .put("sourceName", restored.getString("sourceName")).mapTo(RssSource::class.java)
        val user = "rss-json-private-${UUID.randomUUID()}"
        source.setUserNamespace(user)
        val exported = JsonObject.mapFrom(source)
        assertEquals("订阅 📰", exported.getString("sourceName"))
        assertTrue(source.enabled)
        assertTrue(source.enableJs)
        privateFields.forEach { assertFalse(exported.containsKey(it), "RSS response leaks $it") }
        assertFalse(exported.encode().contains(user))
    }

    @Test
    fun `old shelf records retain reading progress and Kotlin defaults without exposing transient state`() {
        val book = JsonObject("""
            {"bookUrl":"https://json.example/book","origin":"https://json.example/source",
             "name":"旧书 📖","author":"作者","durChapterIndex":17,"durChapterPos":219,
             "durChapterTime":1780000000123,"totalChapterNum":80,"customTag":"收藏"}
        """.trimIndent()).mapTo(Book::class.java)
        assertEquals(17, book.durChapterIndex)
        assertEquals(219, book.durChapterPos)
        assertEquals(1_780_000_000_123L, book.durChapterTime)
        assertTrue(book.canUpdate)
        assertTrue(book.useReplaceRule)
        assertEquals(0, book.group)
        assertNull(book.downloadUrls)
        book.infoHtml = "private detail HTML"
        book.tocHtml = "private toc HTML"
        book.setUserNameSpace("private-reader")
        book.setRootDir("private-root")

        val stored = JsonObject(Json.encode(book))
        assertEquals("旧书 📖", stored.getString("name"))
        assertEquals("收藏", stored.getString("customTag"))
        assertEquals(17, stored.getInteger("durChapterIndex"))
        assertEquals(219, stored.getInteger("durChapterPos"))
        assertEquals(1_780_000_000_123L, stored.getLong("durChapterTime"))
        listOf("infoHtml", "tocHtml", "rootDir", "userNameSpace", "_userNameSpace", "variableMap")
            .forEach { assertFalse(stored.containsKey(it), "Stored shelf record leaks $it") }
    }

    @Test
    fun `legacy chapter catalogs keep long offsets and missing fields through nested API envelopes`() {
        val catalog = JsonArray("""
            [{"url":"/chapter/one","title":"第一章","bookUrl":"https://json.example/book",
              "index":17,"start":4294967296,"end":4294967396},
             {"url":"/chapter/two","title":"第二章"}]
        """.trimIndent())
        val first = catalog.getJsonObject(0).mapTo(BookChapter::class.java)
        val second = catalog.getJsonObject(1).mapTo(BookChapter::class.java)
        assertEquals(4_294_967_296L, first.start)
        assertEquals(4_294_967_396L, first.end)
        assertEquals(17, first.index)
        assertFalse(second.isVolume)
        assertFalse(second.isVip)
        assertFalse(second.isPay)
        assertEquals(0, second.index)
        assertEquals("", second.baseUrl)
        assertNull(second.resourceUrl)
        assertNull(second.subContent)

        val response = JsonObject().put("isSuccess", true).put("data", JsonArray()
            .add(JsonObject.mapFrom(first)).add(JsonObject.mapFrom(second)))
            .put("metadata", JsonObject().put("flags", JsonArray().add(false).add(null).add("目录")))
        val restored = JsonObject(response.encode())
        assertTrue(restored.getBoolean("isSuccess"))
        val chapters = restored.getJsonArray("data")
        assertEquals(2, chapters.size())
        assertEquals(4_294_967_296L, chapters.getJsonObject(0).getLong("start"))
        assertEquals("第二章", chapters.getJsonObject(1).getString("title"))
        assertEquals(listOf(false, null, "目录"), restored.getJsonObject("metadata").getJsonArray("flags").list)
        assertFalse(chapters.getJsonObject(0).containsKey("variableMap"))
    }

    @Test
    fun `legacy null primitive fields and numeric strings retain prior import semantics`() {
        val source = JsonObject("""
            {"bookSourceUrl":"https://json.example/null-fields","bookSourceName":"旧格式",
             "bookSourceType":null,"enabled":null,"enabledExplore":null,"customOrder":"7","weight":null}
        """.trimIndent()).mapTo(BookSource::class.java)
        assertEquals(0, source.bookSourceType)
        assertFalse(source.enabled)
        assertFalse(source.enabledExplore)
        assertEquals(7, source.customOrder)
        assertEquals(0, source.weight)

        val book = JsonObject("""
            {"bookUrl":"https://json.example/book","name":"旧进度",
             "durChapterIndex":null,"durChapterPos":"19","durChapterTime":null,"canUpdate":null}
        """.trimIndent()).mapTo(Book::class.java)
        assertEquals(0, book.durChapterIndex)
        assertEquals(19, book.durChapterPos)
        assertEquals(0L, book.durChapterTime)
        assertFalse(book.canUpdate)
    }

    @Test
    fun `Vertx JSON retains Unicode text ISO instants and URL safe unpadded binary encoding`() {
        val text = "正文 📚\n繁體中文\t\"引号\"与\\路径"
        val instant = Instant.parse("2026-10-09T01:02:03.456Z")
        val binary = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        val objectValue = JsonObject().put("content", text).put("updatedAt", instant).put("binary", binary)
        val restored = JsonObject(Json.encode(objectValue))

        assertEquals(text, restored.getString("content"))
        assertEquals("2026-10-09T01:02:03.456Z", restored.getString("updatedAt"))
        assertEquals(instant, restored.getInstant("updatedAt"))
        assertEquals("-_8", restored.getString("binary"))
        assertArrayEquals(binary, restored.getBinary("binary"))
        assertEquals(text, Json.decodeValue(Json.encode(text), String::class.java))
    }
}
