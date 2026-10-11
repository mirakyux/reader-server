package io.legado.app.model

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.rss.Rss
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class RssParityTests {
    @Test fun searchLoginChecksAndRedirectedRelativeLinksUseTheFinalPage() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var query = ""
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/start") {
                query = exchange.requestURI.rawQuery.orEmpty()
                exchange.responseHeaders.set("Location", "/actual/list"); exchange.sendResponseHeaders(302, -1); exchange.close()
            } else {
                val html = if (exchange.requestURI.path == "/actual/list") "<article><a href='story.html'>文章标题</a></article><a class='next' href='cursor.html'>下一页</a>" else "<main><p>实际正文</p><img src='image.png'></main>"
                val bytes = html.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val source = RssSource(sourceUrl = base, ruleArticles = "@css:article", ruleTitle = "@css:a@text", ruleLink = "@css:a@href", ruleNextPage = "@css:a.next@href", ruleContent = "@css:main@html", loginCheckJs = "source.sourceComment = 'checked'; result")
            source.setUserNamespace("rss-fixture")
            val result = runBlocking { Rss.getArticles("新闻", "$base/start?search={{key}}", source, 1, null, "人物") }
            assertTrue(query.startsWith("search=")); assertFalse(query.contains("{{key}}"))
            assertEquals("checked", source.sourceComment)
            assertEquals("$base/actual/story.html", result.first.single().link)
            assertEquals("$base/actual/cursor.html", result.second)
            source.sourceComment = ""
            val content = runBlocking { Rss.getContent(RssArticle(origin = base, link = "$base/actual/story.html"), source.ruleContent!!, source, null) }
            assertTrue(content.contains("实际正文")); assertTrue(content.contains("$base/actual/image.png")); assertEquals("checked", source.sourceComment)
        } finally { server.stop(0) }
    }
}
