package io.legado.app.model

import io.legado.app.model.jsSource.JsSourceConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsSourceConfigCompatibilityTests {
    private val functions = """
        function search() { return []; }
        function getChapters() { return []; }
        function getContent() { return ''; }
    """.trimIndent()

    @Test
    fun `FU import falls back from null or incomplete config to the legacy source object`() {
        for (config in listOf("null", "{}", "{bookSourceName:'incomplete'}", "{bookSourceUrl:'https://incomplete.example'}")) {
            val script = """
                var config = $config;
                var source = {bookSourceUrl:'https://legacy.example',bookSourceName:'旧版 JS 源'};
                $functions
            """.trimIndent()
            val imported = JsSourceConfig.extract(script)
            assertEquals("https://legacy.example", imported.bookSourceUrl)
            assertEquals("旧版 JS 源", imported.bookSourceName)
            assertEquals(script, imported.mainJs)
        }
        val preferred = JsSourceConfig.extract("""
            var config = {bookSourceUrl:'https://modern.example',bookSourceName:'新版 JS 源'};
            var source = {bookSourceUrl:'https://legacy.example',bookSourceName:'旧版 JS 源'};
            $functions
        """.trimIndent())
        assertEquals("https://modern.example", preferred.bookSourceUrl)
    }

    @Test
    fun `FU import strips declarative rule fields before parsing and preserves the actual main script`() {
        val script = """
            var config = {
                bookSourceUrl:'https://modern.example', bookSourceName:'混合字段 JS 源', bookSourceGroup:'保留',
                mainJs:'unrelated old script', ruleSearch:'old search rule', ruleExplore:[],
                ruleBookInfo:42, ruleToc:'old toc rule', ruleContent:[], ruleReview:'old review rule'
            };
            $functions
        """.trimIndent()
        val imported = JsSourceConfig.extract(script)
        assertEquals(script, imported.mainJs)
        assertEquals("保留", imported.bookSourceGroup)
        assertNull(imported.ruleSearch)
        assertNull(imported.ruleExplore)
        assertNull(imported.ruleBookInfo)
        assertNull(imported.ruleToc)
        assertNull(imported.ruleContent)
        assertNull(imported.ruleReview)
    }
}
