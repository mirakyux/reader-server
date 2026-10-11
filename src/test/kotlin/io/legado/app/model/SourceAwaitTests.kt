package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceInteractions
import io.legado.app.help.source.BrowserBridge
import io.legado.app.model.jsSource.SourceActions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceAwaitTests {
    private fun source() = BookSource(bookSourceUrl = "https://await.example").apply { runtimeUserNamespace = UUID.randomUUID().toString() }

    @Test
    fun `waiting script resumes with verified DOM and cannot be completed by another user`() {
        val source = source()
        val other = source.copy().apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Any?> { SourceActions.evaluate(source,
                "var page=java.startBrowserAwait('/verify','验证',false,'<p>待验证</p>'); for(var i=0;i<20000;i++){} page.url()+'|'+page.body();") }
            var actions = emptyList<Map<String, Any?>>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (actions.isEmpty() && System.nanoTime() < deadline) { actions = SourceInteractions.drain(source); if (actions.isEmpty()) Thread.sleep(10) }
            assertEquals(1, actions.size)
            val id = actions.single()["id"] as String
            assertEquals(true, actions.single()["waiting"])
            assertFalse(result.isDone)
            assertFalse(SourceInteractions.complete(other, id, "https://await.example/verified", "wrong"))
            // A human taking longer than the 30-second script budget must still be able to resume.
            Thread.sleep(31_000)
            assertTrue(SourceInteractions.complete(source, id, "https://await.example/verified", "<p>已验证</p>"))
            assertEquals("https://await.example/verified|<p>已验证</p>", result.get(5, TimeUnit.SECONDS))
            assertFalse(SourceInteractions.complete(source, id, "https://await.example/verified", "duplicate"))
            assertTrue(SourceInteractions.drain(source).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { SourceInteractions.resolve(source, id) }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `verification timeout removes pending action and prevents late completion`() {
        val source = source()
        val action = SourceInteractions.request(source, "browser", "/verify", "验证", waiting = true)
        assertThrows(IllegalStateException::class.java) { SourceInteractions.await(source, action, 20) }
        assertFalse(SourceInteractions.complete(source, action.id, action.url, "late"))
        assertTrue(SourceInteractions.drain(source).isEmpty())
    }

    @Test
    fun `opening verification does not recursively evaluate a header awaiting verification`() {
        val source = source().apply { header = "@js:throw new Error('dynamic header must wait for verification');" }
        try {
            source.putLoginHeader("{\"X-Login\":\"stored\"}")
            val input = BrowserBridge.input(source, source.getKey(), headers = BrowserBridge.bootstrapHeaders(source))
            assertEquals("stored", input.getJsonObject("headers").getString("X-Login"))
            assertNotNull(input.getJsonObject("headers").getString("User-Agent"))
        } finally { source.removeLoginHeader() }
    }
}
