package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.jsSource.SourceSharedJsScope
import io.legado.app.model.jsSource.SourceScriptRuntime
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceMemoryCacheTests {
    private fun cache(user: String = UUID.randomUUID().toString(), source: String = "https://example.org/source") =
        SourceCache(SourceStorage.namespace(user, source))

    @Test
    fun `memory preserves object identity across cache instances and does not write disk`() {
        val namespace = SourceStorage.namespace(UUID.randomUUID().toString(), "https://example.org/source")
        val first = SourceCache(namespace)
        val second = SourceCache(namespace)
        val value = mutableListOf("first")
        try {
            first.putMemory("state", value)
            assertSame(value, second.getFromMemory("state"))
            value += "second"
            assertEquals(listOf("first", "second"), second.getFromMemory("state"))
            assertNull(second.get("state", true))
        } finally { first.delete("state") }
    }

    @Test
    fun `memory remains isolated by user and complete source identity`() {
        val user = UUID.randomUUID().toString()
        val first = cache(user, "https://example.org/source#first")
        val otherSource = cache(user, "https://example.org/source#second")
        val otherUser = cache("other-$user", "https://example.org/source#first")
        try {
            first.putMemory("state", "private")
            assertEquals("private", first.get("state"))
            assertNull(otherSource.getFromMemory("state"))
            assertNull(otherUser.getFromMemory("state"))
        } finally { first.delete("state") }
    }

    @Test
    fun `FU shared cache shares book and RSS data only within their user`() {
        val user = UUID.randomUUID().toString()
        val book = BookSource(bookSourceUrl = "https://example.org/book").apply { runtimeUserNamespace = user }
        val rss = RssSource(sourceUrl = "https://different.example.org/rss").apply { setUserNamespace(user) }
        val bookCache = SourceStorage.sharedCache(book.getUserNamespace())
        val rssCache = SourceStorage.sharedCache(rss.getUserNamespace())
        val otherUser = SourceStorage.sharedCache("other-$user")
        try {
            bookCache.putMemory("same", "memory")
            assertEquals("memory", rssCache.getFromMemory("same"))
            assertNull(otherUser.getFromMemory("same"))
            assertNull(book.getScriptCache().getFromMemory("same"))
            assertNull(rss.getScriptCache().getFromMemory("same"))
            bookCache.put("same", "disk")
            bookCache.putFile("same", "file")
            assertEquals("disk", rssCache.get("same", true))
            assertEquals("file", rssCache.getFile("same"))
            assertNull(otherUser.get("same", true))
            assertNull(otherUser.getFile("same"))
        } finally { bookCache.delete("same") }
    }

    @Test
    fun `book and RSS script bindings expose the same user cache without exposing source state`() {
        val user = UUID.randomUUID().toString()
        val book = BookSource(bookSourceUrl = "https://example.org/book").apply { runtimeUserNamespace = user }
        val rss = RssSource(sourceUrl = "https://different.example.org/rss").apply { setUserNamespace(user) }
        val otherUser = book.copy().apply { runtimeUserNamespace = "other-$user" }
        val key = "shared-${UUID.randomUUID()}"
        try {
            book.evalJS("cache.putMemory('$key', 'book'); 'stored';")
            assertEquals("book", rss.evalJS("String(cache.getFromMemory('$key'))"))
            assertEquals("missing", otherUser.evalJS("cache.getFromMemory('$key') === null ? 'missing' : 'present'"))
            rss.evalJS("cache.putMemory('$key', 'rss'); 'stored';")
            assertEquals("rss", book.evalJS("String(cache.getFromMemory('$key'))"))
            assertNull(book.getScriptCache().getFromMemory(key))
        } finally { SourceStorage.sharedCache(user).delete(key) }
    }

    @Test
    fun `memory evicts least recently read entries at the FU fifty MiB bound`() {
        val cache = cache()
        val value = "x".repeat(9_000_000)
        try {
            cache.putMemory("first", value)
            cache.putMemory("second", value)
            assertSame(value, cache.getFromMemory("first"))
            cache.putMemory("third", value)
            assertNull(cache.getFromMemory("second"))
            assertSame(value, cache.getFromMemory("first"))
            assertSame(value, cache.getFromMemory("third"))
        } finally {
            listOf("first", "second", "third").forEach(cache::deleteMemory)
        }
    }

    @Test
    fun `deleteMemory keeps persistent value and get restores its memory copy`() {
        val cache = cache()
        try {
            cache.put("state", "persisted")
            assertEquals("persisted", cache.getFromMemory("state"))
            cache.putMemory("state", "temporary")
            assertEquals("temporary", cache.get("state"))
            assertEquals("persisted", cache.get("state", true))
            cache.deleteMemory("state")
            assertNull(cache.getFromMemory("state"))
            assertEquals("persisted", cache.get("state"))
            assertEquals("persisted", cache.getFromMemory("state"))
            cache.delete("state")
            assertNull(cache.get("state"))
            assertNull(cache.getFromMemory("state"))
        } finally { cache.delete("state") }
    }

    @Test
    fun `disk expiry does not add a ttl to FU memory values`() {
        val cache = cache()
        try {
            cache.put("state", "memory-copy", -1)
            assertNull(cache.get("state", true))
            assertEquals("memory-copy", cache.get("state"))
            cache.deleteMemory("state")
            assertNull(cache.get("state"))
        } finally { cache.delete("state") }
    }

    @Test
    fun `typed getters use matching memory types or parse disk without coercing memory`() {
        val cache = cache()
        try {
            cache.putMemory("number", "73")
            assertNull(cache.getInt("number"))
            cache.put("number", "19")
            cache.putMemory("number", 73)
            assertEquals(73, cache.getInt("number"))
            assertEquals(19L, cache.getLong("number"))
            assertEquals("19", cache.get("number"))
            cache.putMemory("number", 73L)
            assertEquals(73L, cache.getLong("number"))
            assertEquals(19, cache.getInt("number"))
            cache.putMemory("number", 7.5)
            assertEquals(7.5, cache.getDouble("number"))
            assertEquals(19f, cache.getFloat("number"))
            cache.putMemory("number", 7.5f)
            assertEquals(7.5f, cache.getFloat("number"))
            assertEquals(19.0, cache.getDouble("number"))
        } finally { cache.delete("number") }
    }

    @Test
    fun `file and byte array writes do not populate the memory cache`() {
        val cache = cache()
        try {
            cache.putFile("file", "disk-file", 0)
            assertNull(cache.getFromMemory("file"))
            cache.putMemory("file", "memory-file")
            assertEquals("disk-file", cache.getFile("file"))
            val bytes = byteArrayOf(1, 2, 3)
            cache.put("bytes", bytes)
            assertNull(cache.getFromMemory("bytes"))
            assertArrayEquals(bytes, cache.getByteArray("bytes"))
        } finally { cache.delete("file"); cache.delete("bytes") }
    }

    @Test
    fun `separate FU file keys coexist with text state and delete removes both`() {
        val cache = SourceCache(SourceStorage.namespace(UUID.randomUUID().toString(), "shared"), separateFiles = true)
        try {
            cache.put("same", "text-state")
            cache.putFile("same", "file-state")
            assertEquals("text-state", cache.get("same", true))
            assertEquals("file-state", cache.getFile("same"))
            assertEquals("text-state", cache.get("same"))
            val bytes = byteArrayOf(1, 2, 3)
            cache.put("same", bytes)
            assertArrayEquals(bytes, cache.getByteArray("same"))
            assertEquals("text-state", cache.get("same", true))
            cache.delete("same")
            assertNull(cache.get("same"))
            assertNull(cache.getFile("same"))
            assertNull(cache.getByteArray("same"))
        } finally { cache.delete("same") }
    }

    @Test
    fun `separate file expiry does not expire a text entry at the same key`() {
        val cache = SourceCache(SourceStorage.namespace(UUID.randomUUID().toString(), "shared"), separateFiles = true)
        try {
            cache.put("same", "permanent", 0)
            cache.putFile("same", "expired", -1)
            assertNull(cache.getFile("same"))
            assertEquals("permanent", cache.get("same", true))
            cache.put("same", byteArrayOf(1, 2), -1)
            assertNull(cache.getByteArray("same"))
            assertEquals("permanent", cache.get("same", true))
        } finally { cache.delete("same") }
    }

    @Test
    fun `default source state keeps the existing file directory`() {
        val cache = cache()
        try {
            cache.put("same", "existing-state")
            assertEquals("existing-state", cache.getFile("same"))
            cache.putFile("same", "updated-file")
            assertEquals("updated-file", cache.get("same", true))
        } finally { cache.delete("same") }
    }

    private fun librarySource() = BookSource(
        bookSourceUrl = "https://example.org/${UUID.randomUUID()}",
        jsLib = "var count=0; function next(){ return ++count; }",
    ).apply { runtimeUserNamespace = UUID.randomUUID().toString() }

    private fun libraryScope(source: BookSource, cx: Context): ScriptableObject =
        requireNotNull(SourceSharedJsScope.getOrCreate(source, cx) { scope ->
            cx.evaluateString(scope, requireNotNull(source.jsLib), "jsLib", 1, null)
        })

    @Test
    fun `library scope retains library state without request bindings and invalidates by content`() {
        val source = librarySource()
        val cx = Context.enter()
        try {
            val first = libraryScope(source, cx)
            assertEquals("1", Context.toString(cx.evaluateString(first, "next()", "first", 1, null)))
            val second = libraryScope(source, cx)
            assertSame(first, second)
            assertEquals("2", Context.toString(cx.evaluateString(second, "next()", "second", 1, null)))
            assertEquals("undefined|undefined|undefined|undefined", cx.evaluateString(second,
                "typeof source+'|'+typeof sourceApi+'|'+typeof cookie+'|'+typeof cache", "globals", 1, null))
            assertFalse(first.isExtensible)
            source.jsLib = "var count=10; function next(){ return ++count; }"
            val changed = libraryScope(source, cx)
            assertNotSame(first, changed)
            assertEquals("11", Context.toString(cx.evaluateString(changed, "next()", "changed", 1, null)))
            SourceSharedJsScope.invalidateLibrary(source)
            assertNotSame(changed, libraryScope(source, cx))
        } finally { SourceSharedJsScope.invalidateLibrary(source); Context.exit() }
    }

    @Test
    fun `library closures read the current request without retaining bindings after return or error`() {
        val source = librarySource().apply {
            jsLib = """
                var initializedWith = typeof source+'|'+typeof cookie+'|'+typeof cache;
                function currentBinding() { return String(key)+'|'+source.getKey()+'|'+java.getSource().getKey(); }
            """.trimIndent()
        }
        try {
            assertEquals("undefined|undefined|undefined|first|${source.bookSourceUrl}|${source.bookSourceUrl}",
                source.evalScript("initializedWith+'|'+currentBinding()", mapOf("key" to "first")))
            assertEquals("second|${source.bookSourceUrl}|${source.bookSourceUrl}",
                source.evalScript("currentBinding()", mapOf("key" to "second")))
            assertSame(org.mozilla.javascript.Scriptable.NOT_FOUND, SourceScriptRuntime.libraryBinding("java"))
            assertThrows(Exception::class.java) { source.evalScript("throw new Error('fixture');") }
            assertSame(org.mozilla.javascript.Scriptable.NOT_FOUND, SourceScriptRuntime.libraryBinding("java"))
        } finally { SourceSharedJsScope.invalidateLibrary(source) }
    }

    @Test
    fun `shared libraries can import Jsoup under the request class policy`() {
        val source = librarySource().apply {
            jsLib = "var Jsoup=org.jsoup.Jsoup; function title(html){return String(Jsoup.parse(html).title());}"
        }
        try {
            assertEquals("first", source.evalScript("title('<title>first</title>')"))
            assertEquals("second", source.evalScript("title('<title>second</title>')"))
        } finally { SourceSharedJsScope.invalidateLibrary(source) }
    }

    @Test
    fun `nested source evaluation restores the outer library request bindings`() {
        val outer = librarySource().apply {
            jsLib = "function currentBinding(){ return String(key)+'|'+source.getKey(); }"
        }
        val inner = outer.copy(bookSourceUrl = "${outer.bookSourceUrl}/inner")
            .apply { runtimeUserNamespace = outer.runtimeUserNamespace }
        try {
            SourceScriptRuntime.run(outer, mapOf("key" to "outer")) { cx, scope ->
                assertEquals("outer|${outer.bookSourceUrl}", cx.evaluateString(scope, "currentBinding()", "before", 1, null))
                assertEquals("inner|${inner.bookSourceUrl}", inner.evalScript("currentBinding()", mapOf("key" to "inner")))
                assertEquals("outer|${outer.bookSourceUrl}", cx.evaluateString(scope, "currentBinding()", "after", 1, null))
            }
            assertSame(org.mozilla.javascript.Scriptable.NOT_FOUND, SourceScriptRuntime.libraryBinding("source"))
        } finally { listOf(outer, inner).forEach(SourceSharedJsScope::invalidateLibrary) }
    }

    @Test
    fun `FU library scopes isolate user and source but share across execution threads`() {
        val first = librarySource()
        val otherSource = first.copy(bookSourceUrl = "${first.bookSourceUrl}/other")
            .apply { runtimeUserNamespace = first.runtimeUserNamespace }
        val otherUser = first.copy().apply { runtimeUserNamespace = "other-${first.runtimeUserNamespace}" }
        val executor = Executors.newSingleThreadExecutor()
        val cx = Context.enter()
        try {
            val firstScope = libraryScope(first, cx)
            assertNotSame(firstScope, libraryScope(otherSource, cx))
            assertNotSame(firstScope, libraryScope(otherUser, cx))
            val threadScope = executor.submit<ScriptableObject> {
                val threadContext = Context.enter()
                try { libraryScope(first, threadContext) } finally { Context.exit() }
            }.get(10, TimeUnit.SECONDS)
            assertSame(firstScope, threadScope)
        } finally {
            listOf(first, otherSource, otherUser).forEach(SourceSharedJsScope::invalidateLibrary)
            executor.shutdownNow()
            Context.exit()
        }
    }

    @Test
    fun `FU library state survives thread changes with current request bindings`() {
        val source = librarySource().apply {
            jsLib = "var state={count:0}; function next(){ return ++state.count+'|'+key; }"
        }
        val firstExecutor = Executors.newSingleThreadExecutor()
        val secondExecutor = Executors.newSingleThreadExecutor()
        val cx = Context.enter()
        try {
            val retained = libraryScope(source, cx)
            assertEquals("1|first", source.evalScript("next()", mapOf("key" to "first")))
            assertEquals("2|second", firstExecutor.submit<String> {
                source.evalScript("next()", mapOf("key" to "second")) as String
            }.get(10, TimeUnit.SECONDS))
            assertEquals("3|third", secondExecutor.submit<String> {
                source.evalScript("next()", mapOf("key" to "third")) as String
            }.get(10, TimeUnit.SECONDS))
            assertSame(retained, libraryScope(source, cx))
            assertSame(org.mozilla.javascript.Scriptable.NOT_FOUND, SourceScriptRuntime.libraryBinding("key"))
        } finally {
            SourceSharedJsScope.invalidateLibrary(source)
            firstExecutor.shutdownNow()
            secondExecutor.shutdownNow()
            Context.exit()
        }
    }

    @Test
    fun `FU standalone CryptoJS keeps a per-thread shared scope`() {
        val source = librarySource().apply { jsLib = null }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val retained = SourceScriptRuntime.run(source) { cx, scope ->
                cx.evaluateString(scope, "CryptoJS.fixtureState=7", "fixture", 1, null)
                scope.prototype
            }
            assertEquals("7", source.evalScript("String(CryptoJS.fixtureState)"))
            assertEquals("undefined", executor.submit<String> {
                source.evalScript("typeof CryptoJS.fixtureState") as String
            }.get(10, TimeUnit.SECONDS))
            assertSame(retained, SourceScriptRuntime.run(source) { _, scope -> scope.prototype })
            SourceSharedJsScope.invalidateLibrary(source)
            assertEquals("undefined", source.evalScript("typeof CryptoJS.fixtureState"))
        } finally {
            SourceSharedJsScope.invalidateLibrary(source)
            executor.shutdownNow()
        }
    }

    @Test
    fun `FU ajaxAll can reenter the shared library on IO threads without deadlocking`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val source = librarySource().apply {
            jsLib = "function libraryHeaders(){return JSON.stringify({'X-Lib-Source':source.getKey()});}"
            header = "@js:libraryHeaders()"
        }
        server.createContext("/") { exchange ->
            val valid = exchange.requestHeaders.getFirst("X-Lib-Source") == source.bookSourceUrl
            val bytes = (if (valid) exchange.requestURI.path else "missing library header").toByteArray()
            exchange.sendResponseHeaders(if (valid) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val executor = Executors.newSingleThreadExecutor()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            assertEquals("/one|/two", executor.submit<String> {
                source.evalScript("var responses=java.ajaxAll(['$base/one','$base/two']); String(responses[0].body())+'|'+String(responses[1].body())") as String
            }.get(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            server.stop(0)
            SourceSharedJsScope.invalidateLibrary(source)
        }
    }

    @Test
    fun `invalidating an in-flight library prevents stale scope reinsertion`() {
        val source = librarySource()
        val cx = Context.enter()
        try {
            val stale = SourceSharedJsScope.getOrCreate(source, cx) { scope ->
                cx.evaluateString(scope, requireNotNull(source.jsLib), "jsLib", 1, null)
                SourceSharedJsScope.invalidateLibrary(source)
            }
            assertNotSame(stale, libraryScope(source, cx))
        } finally { SourceSharedJsScope.invalidateLibrary(source); Context.exit() }
    }

    @Test
    fun `library cache keeps at most sixteen scopes and failed initialization can retry`() {
        val first = librarySource()
        val sources = (0..16).map { index ->
            first.copy(bookSourceUrl = "${first.bookSourceUrl}/$index")
                .apply { runtimeUserNamespace = first.runtimeUserNamespace }
        }
        val cx = Context.enter()
        try {
            val retained = sources.map { libraryScope(it, cx) }
            assertNotSame(retained.first(), libraryScope(sources.first(), cx))
            SourceSharedJsScope.invalidateLibrary(first)
            assertThrows(IllegalStateException::class.java) {
                SourceSharedJsScope.getOrCreate(first, cx) { throw IllegalStateException("invalid library") }
            }
            assertNotNull(libraryScope(first, cx))
        } finally {
            (sources + first).forEach(SourceSharedJsScope::invalidateLibrary)
            Context.exit()
        }
    }

    @Test
    fun `source scripts can retain mutable JavaScript objects between invocations`() {
        val source = BookSource(bookSourceUrl = "https://example.org/${UUID.randomUUID()}")
            .apply { runtimeUserNamespace = UUID.randomUUID().toString() }
        val key = "counter-${UUID.randomUUID()}"
        try {
            source.evalScript("cache.putMemory('$key', {count: 1}); 'stored';")
            assertEquals("2", source.evalScript("var saved=cache.getFromMemory('$key'); saved.count++; String(saved.count);"))
            assertEquals("2", source.evalScript("String(cache.getFromMemory('$key').count);"))
            source.evalScript("cache.deleteMemory('$key'); 'deleted';")
            assertEquals("missing", source.evalScript("cache.getFromMemory('$key') === null ? 'missing' : 'present';"))
        } finally { source.evalScript("cache.deleteMemory('$key'); 'cleaned';") }
    }
}
