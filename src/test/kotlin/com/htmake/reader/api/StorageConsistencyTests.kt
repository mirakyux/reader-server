package com.htmake.reader.api

import com.google.gson.JsonArray
import com.htmake.reader.api.service.UserDataFiles
import com.htmake.reader.utils.saveStorage
import com.htmake.reader.utils.storageFinalPath
import com.htmake.reader.utils.storageTransaction
import com.htmake.reader.utils.storageDirectory
import com.htmake.reader.utils.getStorage
import com.htmake.reader.utils.workDirPath
import com.htmake.reader.utils.workDirInit
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import com.htmake.reader.api.service.ChapterCache
import com.htmake.reader.api.service.ChapterEdits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StorageConsistencyTests {
    @TempDir lateinit var directory: Path

    @Test
    fun `legacy storage writer shares the native user data transaction lock`() {
        val previous = storageFinalPath
        storageFinalPath = directory.toString()
        val files = UserDataFiles(directory.resolve("data"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            files.write("default", "bookshelf.json", JsonArray())
            val transaction = pool.submit {
                files.update("default", "bookshelf.json") { current ->
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    current
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val writer = pool.submit {
                started.countDown()
                try { saveStorage("data", "default", "bookshelf", value = listOf("latest")) }
                finally { completed.countDown() }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(completed.await(150, TimeUnit.MILLISECONDS), "writer bypassed the active transaction")
            release.countDown()
            transaction.get(5, TimeUnit.SECONDS)
            writer.get(5, TimeUnit.SECONDS)
            assertEquals("latest", files.read("default", "bookshelf.json").asJsonArray[0].asString)
        } finally {
            release.countDown()
            pool.shutdownNow()
            storageFinalPath = previous
        }
    }

    @Test
    fun `book cache directory honors the configured storage root`() {
        val previous = storageFinalPath
        storageFinalPath = directory.resolve("configured").toString()
        try {
            val book = Book(name = "测试书", author = "作者", bookUrl = "https://fixture/book")
            book.setRootDir(directory.resolve("work").toString())
            book.setUserNameSpace("alice")
            assertEquals(directory.resolve("configured/data/alice/测试书_作者").toFile().canonicalPath,
                java.io.File(book.getBookDir()).canonicalPath)
        } finally { storageFinalPath = previous }
    }

    @Test
    fun `parallel legacy and native mutations keep every update and readers only see complete json`() {
        val previous = storageFinalPath
        storageFinalPath = directory.toString()
        val files = UserDataFiles(directory.resolve("data"))
        val pool = Executors.newFixedThreadPool(6)
        try {
            files.write("default", "bookshelf.json", JsonArray())
            val jobs = (0 until 80).map { index -> pool.submit {
                if (index % 2 == 0) files.update("default", "bookshelf.json") { current ->
                    current.asJsonArray.add(index)
                    current
                } else storageTransaction("data", "default", "bookshelf") {
                    val value = com.google.gson.JsonParser.parseString(getStorage("data", "default", "bookshelf")).asJsonArray
                    value.add(index)
                    saveStorage("data", "default", "bookshelf", value = value)
                }
                assertDoesNotThrow { com.google.gson.JsonParser.parseString(getStorage("data", "default", "bookshelf")) }
            } }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            val values = files.read("default", "bookshelf.json").asJsonArray.map { it.asInt }.sorted()
            assertEquals((0 until 80).toList(), values)
            assertFalse(directory.toFile().walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            pool.shutdownNow()
            storageFinalPath = previous
        }
    }

    @Test
    fun `legacy book files migrate once without overwriting configured data or resurrecting removed caches`() {
        val previousStorage = storageFinalPath
        val previousWork = workDirPath
        val previousInit = workDirInit
        storageFinalPath = directory.resolve("configured").toString()
        workDirPath = directory.resolve("work").toString()
        workDirInit = true
        try {
            val legacy = directory.resolve("work/storage/data/alice/book")
            legacy.toFile().mkdirs()
            legacy.resolve("chapter.json").toFile().writeText("legacy catalog")
            legacy.resolve("0.txt").toFile().writeText("legacy body")
            val configured = directory.resolve("configured/data/alice/book")
            configured.toFile().mkdirs()
            configured.resolve("chapter.json").toFile().writeText("current catalog")
            val migrated = storageDirectory("data", "alice", "book")
            assertEquals("current catalog", configured.resolve("chapter.json").toFile().readText())
            assertEquals("legacy body", configured.resolve("0.txt").toFile().readText())
            assertTrue(legacy.resolve("0.txt").toFile().isFile)
            configured.resolve("0.txt").toFile().delete()
            assertEquals(migrated.canonicalFile, storageDirectory("data", "alice", "book").canonicalFile)
            assertFalse(configured.resolve("0.txt").toFile().exists())
        } finally {
            storageFinalPath = previousStorage
            workDirPath = previousWork
            workDirInit = previousInit
        }
    }

    @Test
    fun `legacy cached chapters are remapped against a newer configured catalog`() {
        val previousStorage = storageFinalPath
        val previousWork = workDirPath
        val previousInit = workDirInit
        storageFinalPath = directory.resolve("configured").toString()
        workDirPath = directory.resolve("work").toString()
        workDirInit = true
        try {
            val hash = "0123456789abcdef0123456789abcdef"
            val old = listOf(BookChapter(index = 0, url = "https://fixture/a", title = "A"),
                BookChapter(index = 1, url = "https://fixture/b", title = "B"))
            val current = listOf(old[1].copy(index = 0), old[0].copy(index = 1))
            val legacy = directory.resolve("work/storage/data/alice/book").toFile().apply { mkdirs() }
            java.io.File(legacy, "$hash.json").writeText(com.htmake.reader.utils.jsonEncode(old))
            val legacyCache = java.io.File(legacy, hash).apply { mkdirs() }
            java.io.File(legacyCache, "1.txt").writeText("legacy B")
            ChapterEdits.save(legacyCache, old[0], "编辑A", "编辑正文A", "原文A")
            val configured = directory.resolve("configured/data/alice/book").toFile().apply { mkdirs() }
            java.io.File(configured, "$hash.json").writeText(com.htmake.reader.utils.jsonEncode(current))
            val targetCache = java.io.File(configured, hash)
            ChapterCache.save(targetCache, current[0], "current B")
            storageDirectory("data", "alice", "book")
            assertTrue(ChapterCache.missing(current, targetCache).isEmpty())
            assertEquals("current B", ChapterCache.read(targetCache, 0, current[0]))
            assertEquals("编辑正文A", ChapterCache.read(targetCache, 1, current[1]))
            assertNull(ChapterEdits.read(targetCache, current[0]))
            assertEquals("原文A", ChapterEdits.restore(targetCache, current[1]))
            assertEquals(com.htmake.reader.utils.jsonEncode(current), java.io.File(configured, "$hash.json").readText())
        } finally {
            storageFinalPath = previousStorage
            workDirPath = previousWork
            workDirInit = previousInit
        }
    }
}
