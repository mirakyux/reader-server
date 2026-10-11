package com.htmake.reader.api.service

import com.htmake.reader.api.service.advanced.AdvancedStore
import io.legado.app.data.entities.BookChapter
import io.vertx.core.json.JsonObject
import java.io.File

/** An edit keeps a recoverable original and remains bound to the chapter URL across directory changes. */
object ChapterEdits {
    /** User edits and their recovery copy are durable even when downloaded files are removed. */
    fun clearCache(directory: File) = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        directory.listFiles()?.filterNot { it.name == ".source.json" || it.name == ".sources" || it.name == "catalog.json" || it.name == ".catalog-history" || it.name.startsWith(".catalog-") || it.name == ".catalog.json" || it.name.matches(Regex("\\d+\\.(edit\\.json|original\\.txt)")) }
            ?.forEach { it.deleteRecursively() }
    }

    fun read(directory: File, chapter: BookChapter): JsonObject? = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        val file = File(directory, "${chapter.index}.edit.json")
        val value = if (file.isFile) runCatching { JsonObject(file.readText()) }.getOrNull() else null
        value?.takeIf { it.getString("url") == chapter.url }
    }

    fun save(directory: File, chapter: BookChapter, title: String, content: String, original: String): JsonObject = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        require(chapter.index >= 0 && title.isNotBlank() && title.length <= 1000) { "章节标题不合法" }
        require(content.length <= 2_000_000 && (content.isNotBlank() || chapter.isVolume)) { "正文为空或过长" }
        ChapterCache.checkCurrent(directory, chapter)
        directory.mkdirs()
        if (read(directory, chapter) == null) {
            AdvancedStore.atomicWrite(File(directory, "${chapter.index}.original.txt").toPath(), original.toByteArray(Charsets.UTF_8))
        }
        val value = JsonObject().put("url", chapter.url).put("title", title).put("content", content).put("updatedAt", System.currentTimeMillis())
        AdvancedStore.atomicWrite(File(directory, "${chapter.index}.edit.json").toPath(), value.encode().toByteArray(Charsets.UTF_8))
        apply(directory, chapter, value)
        value
    }

    fun apply(directory: File, chapter: BookChapter, value: JsonObject? = read(directory, chapter)) = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        if (value == null) return@synchronized
        ChapterCache.save(directory, chapter, value.getString("content"))
        ChapterMetadata.save(directory, chapter.copy(title = value.getString("title")), chapter)
    }

    fun restore(directory: File, chapter: BookChapter): String? = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        if (read(directory, chapter) == null) return@synchronized null
        val original = File(directory, "${chapter.index}.original.txt")
        check(original.isFile) { "原始正文备份缺失，无法恢复" }
        val text = original.readText()
        ChapterCache.save(directory, chapter, text)
        File(directory, "${chapter.index}.edit.json").delete()
        original.delete()
        ChapterMetadata.save(directory, chapter)
        text
    }
}
