package com.htmake.reader.api.service

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import com.htmake.reader.api.service.advanced.AdvancedStore
import io.vertx.core.json.JsonObject
import java.security.MessageDigest
import java.util.UUID

data class CacheProgress(
    val cachedCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val totalCount: Int,
    val failedChapters: List<Int>,
    val complete: Boolean
)

/** Bounded downloads write whole chapters atomically; retries reuse completed chapters. */
object ChapterCache {
    private fun lock(directory: File) = UserDataFiles.lockFor(directory.toPath())
    private fun identity(chapter: BookChapter) = chapter.url.ifBlank { "volume:${chapter.title}" }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Source ownership is durable; unmarked legacy files remain recoverable without being reused. */
    fun directory(book: Book, root: File, legacyOrigin: String = book.origin): File {
        val legacy = File(root, MD5Utils.md5Encode(book.bookUrl))
        Files.createDirectories(legacy.toPath())
        if (book.isLocalBook() || book.origin.isBlank()) return legacy
        return synchronized(lock(legacy)) {
            val binding = File(legacy, ".source.json")
            val owner = if (binding.isFile) {
                JsonObject(binding.readText(Charsets.UTF_8)).getString("origin") ?: error("章节缓存书源记录无效")
            } else {
                val hasLegacyFiles = legacy.listFiles()?.any { it.name != ".sources" } == true ||
                    File(root, "${legacy.name}.json").isFile
                val origin = if (hasLegacyFiles) "" else legacyOrigin
                AdvancedStore.atomicWrite(binding.toPath(), JsonObject().put("origin", origin).encode().toByteArray(Charsets.UTF_8))
                origin
            }
            val directory = if (owner == book.origin) legacy else File(legacy, ".sources/${MD5Utils.md5Encode(book.origin)}")
            Files.createDirectories(directory.toPath())
            directory
        }
    }

    fun valid(directory: File, index: Int, chapter: BookChapter): Boolean = synchronized(lock(directory)) {
        val file = File(directory, "$index.txt")
        if (!file.isFile) return@synchronized false
        val text = file.readText(Charsets.UTF_8)
        if (!chapter.isVolume && text.isBlank()) return@synchronized false
        val identityFile = File(directory, "$index.cache.json")
        if (identityFile.isFile) {
            val saved = runCatching { JsonObject(identityFile.readText()) }.getOrNull() ?: return@synchronized false
            return@synchronized saved.getString("key", saved.getString("url")) == identity(chapter) && saved.getString("digest") == digest(text)
        }
        // Existing servers saved metadata before identity sidecars were introduced.
        val metadata = File(directory, "$index.metadata.json")
        if (metadata.isFile) return@synchronized runCatching { JsonObject(metadata.readText()).getString("url") == chapter.url }.getOrDefault(false)
        true // Legacy index-only files are bound to the previous catalog during refresh.
    }

    /** Validation and reading must share the lock with catalog moves. */
    fun read(directory: File, index: Int, chapter: BookChapter): String? = synchronized(lock(directory)) {
        if (valid(directory, index, chapter)) File(directory, "$index.txt").readText(Charsets.UTF_8) else null
    }

    fun checkCurrent(directory: File, chapter: BookChapter) = synchronized(lock(directory)) {
        val catalog = File(directory, ".catalog.json")
        if (catalog.isFile) {
            val current = JsonObject(catalog.readText()).getString(chapter.index.toString())
            check(current == identity(chapter)) { "目录已更新，请重试章节下载" }
        }
    }

    fun save(directory: File, chapter: BookChapter, text: String) = synchronized(lock(directory)) {
        checkCurrent(directory, chapter)
        AdvancedStore.atomicWrite(File(directory, "${chapter.index}.txt").toPath(), text.toByteArray(Charsets.UTF_8))
        val record = JsonObject().put("url", chapter.url).put("key", identity(chapter)).put("digest", digest(text))
        AdvancedStore.atomicWrite(File(directory, "${chapter.index}.cache.json").toPath(), record.encode().toByteArray(Charsets.UTF_8))
    }

    /** Keep all chapter files with their URL when indexes move; deleted user edits stay recoverable. */
    fun reconcile(directory: File, previous: List<BookChapter>, current: List<BookChapter>) = synchronized(lock(directory)) {
        directory.mkdirs()
        val suffixes = listOf("txt", "cache.json", "metadata.json", "edit.json", "original.txt")
        val oldByUrl = previous.withIndex().groupBy { identity(it.value) }
        if (previous.map { identity(it) } == current.map { identity(it) }) {
            val catalog = JsonObject()
            current.forEachIndexed { index, chapter -> catalog.put(index.toString(), identity(chapter)) }
            AdvancedStore.atomicWrite(File(directory, ".catalog.json").toPath(), catalog.encode().toByteArray())
            return@synchronized
        }
        val snapshot = Files.createTempDirectory(directory.toPath(), ".catalog-").toFile()
        var completed = false
        try {
            directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("\\d+\\.(txt|cache\\.json|metadata\\.json|edit\\.json|original\\.txt)")) }
                ?.forEach { it.copyTo(File(snapshot, it.name)) }
            val retainedUrls = current.map { identity(it) }.toSet()
            previous.withIndex().filter { identity(it.value) !in retainedUrls }.forEach { old ->
                if (File(snapshot, "${old.index}.edit.json").isFile || File(snapshot, "${old.index}.original.txt").isFile) {
                    val archive = File(directory, ".catalog-history/${UUID.randomUUID()}")
                    suffixes.forEach { suffix -> File(snapshot, "${old.index}.$suffix").takeIf { it.isFile }?.let {
                        AdvancedStore.atomicWrite(File(archive, it.name).toPath(), it.readBytes())
                    } }
                    AdvancedStore.atomicWrite(File(archive, "chapter.json").toPath(), ChapterMetadata.values(old.value).encode().toByteArray())
                }
            }
            snapshot.listFiles()?.forEach { check(File(directory, it.name).delete()) { "无法更新章节缓存：${it.name}" } }
            current.forEachIndexed { index, chapter ->
                val candidates = oldByUrl[identity(chapter)] ?: return@forEachIndexed
                val old = candidates.singleOrNull() ?: candidates.firstOrNull { it.value.title == chapter.title }
                    ?: candidates.first()
                val oldChapter = old.value.copy(index = old.index)
                val bodyValid = valid(snapshot, old.index, oldChapter)
                val edit = File(snapshot, "${old.index}.edit.json").takeIf { it.isFile }
                    ?.let { runCatching { JsonObject(it.readText()) }.getOrNull() }
                suffixes.forEach { suffix ->
                    val source = File(snapshot, "${old.index}.$suffix")
                    val target = File(directory, "$index.$suffix")
                    if (!source.isFile || (suffix == "txt" || suffix == "cache.json") && !bodyValid) return@forEach
                    if (suffix == "original.txt" && edit?.getString("url") != chapter.url) return@forEach
                    if (suffix == "metadata.json" || suffix == "edit.json") {
                        val saved = runCatching { JsonObject(source.readText()) }.getOrNull()
                        if (saved?.getString("url") != chapter.url) return@forEach
                        if (suffix == "metadata.json") saved.put("index", index)
                        AdvancedStore.atomicWrite(target.toPath(), saved.encode().toByteArray(Charsets.UTF_8))
                    } else AdvancedStore.atomicWrite(target.toPath(), source.readBytes())
                }
                if (bodyValid) {
                    val record = JsonObject().put("url", chapter.url).put("key", identity(chapter)).put("digest", digest(File(snapshot, "${old.index}.txt").readText()))
                    AdvancedStore.atomicWrite(File(directory, "$index.cache.json").toPath(), record.encode().toByteArray())
                }
            }
            val catalog = JsonObject()
            current.forEachIndexed { index, chapter -> catalog.put(index.toString(), identity(chapter)) }
            AdvancedStore.atomicWrite(File(directory, ".catalog.json").toPath(), catalog.encode().toByteArray())
            completed = true
        } finally {
            // Keep the recovery snapshot if disk I/O fails during the catalog transition.
            if (completed) snapshot.deleteRecursively()
        }
    }

    fun missing(chapters: List<BookChapter>, directory: File): List<Int> =
        chapters.indices.filter { !valid(directory, it, chapters[it]) }

    suspend fun download(
        chapters: List<BookChapter>,
        directory: File,
        workers: Int,
        refresh: Boolean,
        onProgress: suspend (CacheProgress) -> Unit,
        fetch: suspend (Int) -> String
    ): CacheProgress = coroutineScope {
        require(chapters.isNotEmpty()) { "书籍目录为空" }
        require(directory.isDirectory || directory.mkdirs()) { "创建章节缓存目录失败" }
        val cached = if (refresh) mutableSetOf() else chapters.indices.filterTo(mutableSetOf()) { valid(directory, it, chapters[it]) }
        val failed = mutableSetOf<Int>()
        var successes = 0
        val mutex = Mutex()
        val next = AtomicInteger()
        fun progress() = CacheProgress(cached.size, successes, failed.size, chapters.size, failed.sorted(),
            cached.size == chapters.size && failed.isEmpty())
        onProgress(progress())
        List(workers.coerceIn(1, 24).coerceAtMost(chapters.size)) {
            async {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= chapters.size) break
                    if (mutex.withLock { index in cached }) continue
                    var success = false
                    try {
                        val text = fetch(index)
                        require(text.isNotBlank() || chapters[index].isVolume) { "章节正文为空：${chapters[index].title}" }
                        currentCoroutineContext().ensureActive()
                        save(directory, chapters[index].copy(index = index), text)
                        success = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Preserve other chapters and finish the queue so users can retry only failures.
                    }
                    mutex.withLock {
                        if (success) { cached.add(index); successes++ } else failed.add(index)
                        onProgress(progress())
                    }
                }
            }
        }.awaitAll()
        progress()
    }
}
