package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.JsSourceEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceImportCompatibilityTests {
    @Test fun `FU official Jsoup template imports and executes without edits`() {
        val source = BookSource.fromJson("""
            var config={bookSourceUrl:'https://template.example',bookSourceName:'FU模板'};
            var Jsoup=org.jsoup.Jsoup;
            function search(){return [{name:Jsoup.parse('<h1>正文标题</h1>').select('h1').text(),bookUrl:'/book'}];}
            function getChapters(){return [{title:'第一章',url:'/chapter'}];}
            function getContent(){return Jsoup.parse('<main>实际正文</main>').select('main').text();}
        """.trimIndent()).getOrThrow()
        assertEquals("实际正文", JsSourceEngine(source).call("getContent", expectText = true))
        assertTrue(JsSourceEngine(source).call("search")!!.contains("正文标题"))
    }

    @Test fun `FU package imports can use JSONPath and JDK utilities`() {
        val source = BookSource(bookSourceUrl = "https://import.example")
        assertEquals("正文", source.evalScript("com.jayway.jsonpath.JsonPath.read('{\"title\":\"正文\"}', '\$.title')"))
        assertEquals("value", source.evalScript("var map=new Packages.java.util.HashMap(); map.put('key','value'); map.get('key')"))
        assertEquals("\"正文\"", source.evalScript("new com.google.gson.Gson().toJson('正文')"))
    }

    @Test fun `package imports cannot construct host caches or expose reflection and processes`() {
        val source = BookSource(bookSourceUrl = "https://import.example")
        for (script in listOf(
            "new Packages.io.legado.app.help.source.SourceCache('another-user')",
            "new Packages.io.legado.app.quickjs.QuickJsSandboxBridge()",
            "new Packages.io.legado.app.data.entities.BookSource()",
            "Packages.java.lang.Runtime.getRuntime()", "Packages.java.lang.Class.forName('java.lang.Runtime')",
            "new Packages.java.io.File('/private')", "new Packages.java.lang.ProcessBuilder('cmd')",
            "org.jsoup.Jsoup.parse('safe').getClass().getClassLoader()"
        )) assertThrows(Exception::class.java, { source.evalScript(script) }, script)
        assertEquals("safe", source.evalScript("org.jsoup.Jsoup.parse('<p>safe</p>').text()"))
    }

    @Test fun `FU hides raw File returns while private file helpers still work`() {
        val source = BookSource(bookSourceUrl = "https://file-import.example").apply {
            runtimeUserNamespace = "file-import-${java.util.UUID.randomUUID()}"
        }
        val file = source.getFile("fixture.txt")
        try {
            file.parentFile.mkdirs()
            file.writeText("书源私有正文")
            assertEquals(true, source.evalScript("java.getFile('fixture.txt') === null"))
            assertEquals(true, source.evalScript("java.getFile('missing.txt') === null"))
            assertEquals("书源私有正文", source.evalScript("java.readTxtFile('fixture.txt', 'UTF-8')"))
            assertEquals("书源私有正文", source.evalScript("java.bytesToStr(java.readFile('fixture.txt'))"))
            assertEquals(true, source.evalScript("java.deleteFile('fixture.txt')"))
            assertFalse(file.exists())
            assertThrows(Exception::class.java) { source.evalScript("new Packages.java.io.File('/private')") }
            assertThrows(Exception::class.java) { source.evalScript("java.getFile('../outside.txt')") }
        } finally {
            source.deleteFile("fixture.txt")
        }
    }

    @Test fun `host arrays expose only their permitted component types`() {
        val source = BookSource(bookSourceUrl = "https://array-import.example")
        assertEquals("正文|末章", source.evalScript("String(items[0])+'|'+String(items[1])",
            mapOf("items" to arrayOf("正文", "末章"))))
        assertEquals("正文", source.evalScript("String(items[0][0])",
            mapOf("items" to arrayOf(arrayOf("正文")))))
        assertThrows(Exception::class.java) {
            source.evalScript("items[0]", mapOf("items" to arrayOf(java.io.File("private"))))
        }
    }
}
