package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceInteractions
import io.legado.app.model.jsSource.SourceActions
import io.legado.app.utils.GSON
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceVerificationTests {
    private fun source() = BookSource(bookSourceUrl = "https://captcha.example/base/page")
        .apply { runtimeUserNamespace = UUID.randomUUID().toString() }

    private fun interaction(source: BookSource): SourceInteractions.Interaction {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            SourceInteractions.drain(source).firstOrNull()?.let { return SourceInteractions.resolve(source, it["id"] as String) }
            Thread.sleep(10)
        }
        error("验证码脚本没有投递交互请求")
    }

    @Test
    fun `Rhino captcha bridge returns entered value and preserves safe image markup`() {
        val source = source()
        val other = source.copy().apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val imageUrl = "../captcha?width=1&query=\"'><script>alert(1)</script>"
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Any?> { SourceActions.evaluate(source,
                "var code=java.getVerificationCode(${GSON.toJson(imageUrl)});for(var i=0;i<20000;i++){}code;") }
            val action = interaction(source)
            assertTrue(action.waiting)
            assertFalse(action.refetchAfterSuccess)
            assertEquals(source.getKey(), action.url)
            assertFalse(result.isDone)
            val html = requireNotNull(action.html)
            val page = Jsoup.parse(html)
            assertEquals(source.getKey().toHttpUrl().resolve(imageUrl).toString(), page.getElementById("reader-verification-image")!!.attr("src"))
            assertTrue(page.select("script").isEmpty())
            assertTrue(html.contains("&amp;"))
            assertTrue(page.getElementById("reader-verification-code")!!.hasAttr("autofocus"))
            val expected = "A9<>&'\""
            page.getElementById("reader-verification-code")!!.attr("value", expected)
            assertFalse(SourceInteractions.complete(other, action.id, action.url, page.outerHtml()))
            assertTrue(SourceInteractions.complete(source, action.id, action.url, page.outerHtml()))
            assertEquals(expected, result.get(5, TimeUnit.SECONDS))
            assertTrue(SourceInteractions.drain(source).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { SourceInteractions.resolve(source, action.id) }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `captcha rejects closing a page with an empty or missing input`() {
        val source = source()
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (body in listOf("<input id='reader-verification-code' value='   '>", "<p>No code</p>")) {
                val result = executor.submit<String> { source.getVerificationCode("/captcha.png") }
                val action = interaction(source)
                assertTrue(SourceInteractions.complete(source, action.id, action.url, body))
                val error = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
                assertInstanceOf(IllegalArgumentException::class.java, error.cause)
                assertEquals("验证码不能为空", error.cause?.message)
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `captcha rejects non HTTP image URLs before queuing a browser`() {
        val source = source()
        listOf("javascript:alert(1)", "file:///secret.png", "data:image/png;base64,AA==").forEach { image ->
            assertThrows(IllegalArgumentException::class.java) { source.getVerificationCode(image) }
        }
        assertTrue(SourceInteractions.drain(source).isEmpty())
    }
}
