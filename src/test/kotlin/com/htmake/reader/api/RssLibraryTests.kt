package com.htmake.reader.api

import com.htmake.reader.api.service.*
import com.htmake.reader.api.service.advanced.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RssLibraryTests {
    @TempDir lateinit var root: Path
    @Test fun favoritesAreGroupedIdempotentAndUserIsolatedWithFuEntityShape() {
        val files = UserDataFiles(root); val library = RssLibrary(files)
        val article = jsonObject("origin" to "https://source", "link" to "https://article", "title" to "文章", "content" to "正文", "group" to "新闻")
        library.save("alice", "favorites", article); library.save("alice", "favorites", article)
        assertEquals(1, library.list("alice", "favorites", "新闻").size())
        assertEquals(0, library.list("bob", "favorites").size())
        assertEquals(0, library.list("alice", "favorites", "其他").size())
        assertTrue(java.nio.file.Files.readString(files.path("alice", "rssStar.json")).startsWith("["))
        assertEquals(1, library.remove("alice", "favorites", article))
    }
    @Test fun offlineHistoryAndTraversalHaveIndependentStorage() {
        val library = RssLibrary(UserDataFiles(root))
        val article = jsonObject("origin" to "https://source", "link" to "https://article", "title" to "文章", "content" to "正文")
        library.save("alice", "offline", article); library.save("alice", "history", article)
        assertEquals("正文", library.cached("alice", article)?.string("content"))
        library.remove("alice", "history", jsonObject("all" to true))
        assertNotNull(library.cached("alice", article))
        assertThrows(IllegalArgumentException::class.java) { library.list("../escape", "favorites") }
    }
}
