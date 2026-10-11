package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.BrowserBridge
import io.legado.app.help.source.SourceInteractions
import io.legado.app.model.jsSource.SourceActions
import io.legado.app.data.entities.rule.ContentRule
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SourceBridgeStateTests {
    @Test
    fun `disabled browser cookie jar preserves localStorage without replaying cookies`() {
        val source = BookSource(bookSourceUrl = "https://browser.example", enabledCookieJar = false)
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val state = JsonObject().put("cookies", JsonArray().add(JsonObject().put("name", "sid").put("value", "secret")))
            .put("origins", JsonArray().add(JsonObject().put("origin", source.getKey()).put("localStorage", JsonArray())))
        try {
            CookieStore.forSource(source).setCookie(source.getKey(), "sid=stored")
            source.getScriptCache().put("browserState", state.encode())
            val input = BrowserBridge.input(source, source.getKey(), headers = mapOf("X-Option" to "retained"))
            assertEquals(0, input.getJsonArray("cookies").size())
            assertEquals(0, input.getJsonObject("storageState").getJsonArray("cookies").size())
            assertEquals(1, input.getJsonObject("storageState").getJsonArray("origins").size())
            assertEquals("retained", input.getJsonObject("headers").getString("X-Option"))
            BrowserBridge.save(source, JsonObject().put("url", source.getKey()).put("storageState", state))
            assertEquals(0, JsonObject(source.getScriptCache().get("browserState")).getJsonArray("cookies").size())
            assertEquals(1, state.getJsonArray("cookies").size())
        } finally { source.getScriptCache().delete("browserState"); CookieStore.forSource(source).clear() }
    }

    @Test
    fun `manual cookie replacement and clearing do not restore browser cookies`() {
        val source = BookSource(bookSourceUrl = "https://browser.example")
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val state = JsonObject().put("cookies", JsonArray().add(JsonObject().put("name", "old").put("value", "session")))
            .put("origins", JsonArray().add(JsonObject().put("origin", source.getKey()).put("localStorage", JsonArray())))
        val store = CookieStore.forSource(source)
        try {
            source.getScriptCache().put("browserState", state.encode())
            store.setCookie(source.getKey(), "manual=value")
            BrowserBridge.clearCookies(source)
            var input = BrowserBridge.input(source, source.getKey())
            assertEquals(0, input.getJsonObject("storageState").getJsonArray("cookies").size())
            assertEquals("manual=value", store.getCookie(source.getKey()))
            assertEquals("manual", input.getJsonArray("cookies").getJsonObject(0).getString("name"))
            source.getScriptCache().put("browserState", state.encode())
            store.clear()
            BrowserBridge.clearCookies(source)
            input = BrowserBridge.input(source, source.getKey())
            assertTrue(input.getJsonArray("cookies").isEmpty)
            assertTrue(input.getJsonObject("storageState").getJsonArray("cookies").isEmpty)
            assertEquals(1, input.getJsonObject("storageState").getJsonArray("origins").size())
        } finally { source.getScriptCache().delete("browserState"); store.clear() }
    }

    @Test
    fun `source interaction delivery is isolated and identifiers survive consumption`() {
        val source = BookSource(bookSourceUrl = "https://interaction.example", eventListener = true, customButton = true,
            ruleContent = ContentRule(callBackJs = "java.startBrowser('/verify','验证','<p>验证码</p>'); java.openVideoPlayer('/movie.mp4','视频',true); true"))
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val other = source.copy().apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val result = SourceActions.action(source, "event", null, null, "clickCustomButton", null, false)
        val actions = result["actions"] as List<*>
        assertEquals(2, actions.size)
        val browser = actions[0] as Map<*, *>
        assertEquals("browser", browser["type"])
        assertEquals("https://interaction.example/verify", browser["url"])
        assertEquals(source.getKey(), browser["bookSourceUrl"])
        assertFalse(browser.containsKey("html"))
        val id = browser["id"] as String
        assertEquals("<p>验证码</p>", SourceInteractions.resolve(source, id).html)
        assertTrue(SourceInteractions.drain(source).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SourceInteractions.resolve(other, id) }
        assertThrows(IllegalArgumentException::class.java) { source.startBrowser("javascript:alert(1)", "无效") }
        assertEquals(true, (actions[1] as Map<*, *>)["isFloat"])
    }
}
