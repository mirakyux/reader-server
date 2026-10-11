package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.Book
import io.legado.app.help.http.EncodeConverter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class RuleCompatibilityTests {
    private fun analyze(content: String): AnalyzeRule =
        AnalyzeRule(Book()).setContent(content, "https://example.org/catalog/").apply {
            setRedirectUrl("https://example.org/catalog/")
        }

    @Test
    fun cssAndLegacySelectorsExtractTextAndResolveRelativeUrls() {
        val rule = analyze("<ul><li><a href='../book/1'>第一本</a></li><li><a href='/book/2'>第二本</a></li></ul>")
        assertEquals(listOf("第一本", "第二本"), rule.getStringList("@css:a@text"))
        assertEquals("第一本\n第二本", rule.getString("tag.a@text"))
        assertEquals("https://example.org/book/1", rule.getString("@css:a@href", isUrl = true))
    }

    @Test
    fun xpathPreservesTextAndAttributeExtraction() {
        val rule = analyze("<div><a href='/one'>第一章</a><a href='/two'>第二章</a></div>")
        assertEquals(listOf("第一章", "第二章"), rule.getStringList("@xpath://a/text()"))
        assertEquals(listOf("/one", "/two"), rule.getStringList("@xpath://a/@href"))
        assertEquals("第一章", rule.getString("@xpath://a[@href='/one']/text()"))
        assertEquals("第一章", Jsoup.parse(rule.content.toString()).selectXpath("//a[@href='/one']").text())
    }

    @Test
    fun excludedIndicesKeepTheirOriginalOrderWithoutNullElements() {
        val rule = analyze("<ul><li>一</li><li>二</li><li>三</li><li>四</li></ul>")
        assertEquals(listOf("一", "四"), rule.getStringList("tag.li[!1,2]@text"))
        assertEquals(listOf("四", "三", "二", "一"), rule.getStringList("tag.li[-1:0]@text"))
    }

    @Test
    fun gbkHtmlDecodesFromTheHttpHeaderAndTheMetaCharset() {
        val html = "<html><head><meta charset='GBK'></head><body><a>中文书名与第一章节</a></body></html>"
        val bytes = html.toByteArray(Charset.forName("GBK"))
        val converter = EncodeConverter().responseBodyConverter(String::class.java, emptyArray(), null)!!
        for (contentType in listOf("text/html; charset=GBK", "text/html")) {
            val decoded = converter.convert(bytes.toResponseBody(contentType.toMediaType()))!!
            assertEquals("中文书名与第一章节", analyze(decoded).getString("@css:a@text"))
            assertEquals("中文书名与第一章节", analyze(decoded).getString("@xpath://a/text()"))
        }
    }

    @Test
    fun jsonPathHandlesCollectionsAndMissingProperties() {
        val rule = analyze("""{"books":[{"name":"第一本"},{"name":"第二本"}]}""")
        assertEquals(listOf("第一本", "第二本"), rule.getStringList("$.books[*].name"))
        assertEquals("", rule.getString("$.missing"))
    }

    @Test
    fun javaScriptCanReadResultAndCallTheJavaBridge() {
        val rule = analyze("reader")
        assertEquals("READER", rule.getString("@js:result.toUpperCase()"))
        assertEquals("5d41402abc4b2a76b9719d911017c592", rule.getString("@js:java.md5Encode('hello')"))
    }
}
