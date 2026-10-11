package com.htmake.reader.api

import com.htmake.reader.api.service.ChapterCache
import com.htmake.reader.api.service.ChapterEdits
import com.htmake.reader.api.service.ChapterMetadata
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ChapterCacheTests {
    @TempDir lateinit var directory: Path
    private val chapters = (0..2).map { BookChapter(index = it, title = "第${it + 1}章", url = "https://fixture/$it") }

    @Test
    fun `a failed chapter does not prevent other downloads and retry reuses valid cache`() = runBlocking {
        val attempts = Array(3) { AtomicInteger() }
        val first = ChapterCache.download(chapters, directory.toFile(), 2, false, {}) { index ->
            attempts[index].incrementAndGet()
            if (index == 1) error("temporary failure")
            "正文$index"
        }
        assertFalse(first.complete)
        assertEquals(2, first.cachedCount)
        assertEquals(listOf(1), first.failedChapters)
        val retry = ChapterCache.download(chapters, directory.toFile(), 2, false, {}) { index ->
            attempts[index].incrementAndGet()
            "正文$index"
        }
        assertTrue(retry.complete)
        assertEquals(3, retry.cachedCount)
        assertEquals(listOf(1, 2, 1), attempts.map { it.get() })
        assertEquals("正文1", directory.resolve("1.txt").toFile().readText())
    }

    @Test
    fun `empty stale files and out of range indexes cannot make a book complete`() = runBlocking {
        directory.resolve("0.txt").toFile().writeText("   ")
        directory.resolve("999.txt").toFile().writeText("unrelated")
        assertEquals(listOf(0, 1, 2), ChapterCache.missing(chapters, directory.toFile()))
        val result = ChapterCache.download(chapters, directory.toFile(), 1, false, {}) { "" }
        assertFalse(result.complete)
        assertEquals(0, result.cachedCount)
        assertEquals(3, result.failedCount)
        assertEquals(listOf(0, 1, 2), result.failedChapters)
    }

    @Test
    fun `progress includes total and complete cached book makes no requests`() = runBlocking {
        val events = mutableListOf<Boolean>()
        val result = ChapterCache.download(chapters, directory.toFile(), 2, false, { events.add(it.complete) }) { "正文$it" }
        assertTrue(result.complete)
        assertEquals(3, result.totalCount)
        assertTrue(events.last())
        val cached = ChapterCache.download(chapters, directory.toFile(), 2, false, {}) { error("must reuse cache") }
        assertTrue(cached.complete)
        assertEquals(0, cached.successCount)
        assertEquals(0, cached.failedCount)
    }

    @Test
    fun `cancel keeps finished chapters and leaves no partial chapter`() = runBlocking {
        val pendingChapter = CompletableDeferred<Unit>()
        val job = launch {
            ChapterCache.download(chapters, directory.toFile(), 1, false, {}) { index ->
                if (index == 0) "正文0" else {
                    pendingChapter.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        pendingChapter.await()
        job.cancelAndJoin()
        assertEquals("正文0", directory.resolve("0.txt").toFile().readText())
        assertFalse(directory.resolve("1.txt").toFile().exists())
        assertFalse(directory.toFile().listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `empty volume markers are permitted while body chapters require text`() = runBlocking {
        val list = listOf(BookChapter(title = "卷一", isVolume = true), BookChapter(title = "正文"))
        val result = ChapterCache.download(list, directory.toFile(), 1, false, {}) { if (it == 0) "" else "内容" }
        assertTrue(result.complete)
        assertTrue(ChapterCache.missing(list, directory.toFile()).isEmpty())
    }

    @Test
    fun `cached bodies cannot be reused for inserted or reordered chapter urls`() = runBlocking {
        ChapterCache.download(chapters, directory.toFile(), 1, false, {}) { "正文$it" }
        val reordered = listOf(chapters[1].copy(index = 0), chapters[0].copy(index = 1), chapters[2])
        assertEquals(listOf(0, 1), ChapterCache.missing(reordered, directory.toFile()))
        val inserted = listOf(BookChapter(index = 0, title = "新增", url = "https://fixture/new")) +
            chapters.map { it.copy(index = it.index + 1) }
        assertEquals(listOf(0, 1, 2, 3), ChapterCache.missing(inserted, directory.toFile()))
    }

    @Test
    fun `catalog refresh moves legacy bodies metadata edits and originals by chapter identity`() {
        val root = directory.toFile()
        directory.resolve("0.txt").toFile().writeText("原文A")
        directory.resolve("1.txt").toFile().writeText("原文B")
        directory.resolve("2.txt").toFile().writeText("原文C")
        ChapterMetadata.save(root, chapters[1].copy(title = "正文标题B"))
        ChapterEdits.save(root, chapters[0], "编辑标题A", "编辑正文A", "原文A")
        val changed = listOf(chapters[1].copy(index = 0),
            BookChapter(index = 1, url = "https://fixture/new", title = "新增章节"), chapters[0].copy(index = 2))
        ChapterCache.reconcile(root, chapters, changed)
        assertEquals(listOf(1), ChapterCache.missing(changed, root))
        assertEquals("原文B", directory.resolve("0.txt").toFile().readText())
        assertEquals("正文标题B", ChapterMetadata.read(root, changed[0]).getString("title"))
        assertEquals(0, ChapterMetadata.read(root, changed[0]).getInteger("index"))
        assertEquals("编辑正文A", ChapterEdits.read(root, changed[2])?.getString("content"))
        assertNull(ChapterEdits.read(root, changed[0]))
        assertEquals("原文A", ChapterEdits.restore(root, changed[2]))
        assertEquals("原文A", directory.resolve("2.txt").toFile().readText())
        assertFalse(directory.resolve("3.txt").toFile().exists())
    }

    @Test
    fun `deleted chapter edits remain recoverable and stale downloads cannot overwrite moved bodies`() {
        val root = directory.toFile()
        ChapterEdits.save(root, chapters[0], "编辑A", "正文A", "原文A")
        ChapterEdits.save(root, chapters[1], "编辑B", "正文B", "原文B")
        val changed = listOf(chapters[1].copy(index = 0))
        ChapterCache.reconcile(root, chapters, changed)
        assertEquals("正文B", directory.resolve("0.txt").toFile().readText())
        assertEquals("正文B", ChapterEdits.read(root, changed[0])?.getString("content"))
        assertThrows(IllegalStateException::class.java) { ChapterCache.save(root, chapters[0], "过时A") }
        assertThrows(IllegalStateException::class.java) { ChapterEdits.save(root, chapters[0], "过时A", "过时A", "错误原文") }
        assertThrows(IllegalStateException::class.java) { ChapterMetadata.save(root, chapters[0]) }
        assertEquals("正文B", directory.resolve("0.txt").toFile().readText())
        assertEquals("正文B", ChapterEdits.read(root, changed[0])?.getString("content"))
        assertEquals("原文B", directory.resolve("0.original.txt").toFile().readText())
        assertFalse(directory.resolve("1.txt").toFile().exists())
        val backups = directory.resolve(".catalog-history").toFile().walkTopDown().filter { it.name.endsWith("original.txt") }.toList()
        assertEquals(1, backups.size)
        assertEquals("原文A", backups.single().readText())
        ChapterEdits.clearCache(root)
        assertTrue(backups.single().isFile)
    }
}
