package com.htmake.reader.api.service

import io.legado.app.data.entities.BookChapter
import io.vertx.core.json.JsonObject
import java.io.File

/** Sidecar metadata travels with raw chapter caches, without changing the legacy content API. */
object ChapterMetadata {
    fun values(chapter: BookChapter): JsonObject = JsonObject().put("title", chapter.title).put("index", chapter.index)
        .put("url", chapter.url).put("isPay", chapter.isPay).put("isVip", chapter.isVip)
        .put("subContent", chapter.subContent).put("lyric", chapter.getVariable("lyric")).put("danmaku", chapter.getVariable("danmaku"))
    fun save(directory: File, chapter: BookChapter) = save(directory, chapter, chapter)
    fun save(directory: File, chapter: BookChapter, identityChapter: BookChapter) = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        ChapterCache.checkCurrent(directory, identityChapter)
        directory.mkdirs()
        com.htmake.reader.api.service.advanced.AdvancedStore.atomicWrite(File(directory, "${chapter.index}.metadata.json").toPath(), values(chapter).encode().toByteArray())
    }
    fun read(directory: File, chapter: BookChapter): JsonObject = synchronized(UserDataFiles.lockFor(directory.toPath())) {
        val file = File(directory, "${chapter.index}.metadata.json")
        val saved = if (file.isFile) runCatching { JsonObject(file.readText()) }.getOrNull() else null
        if (saved?.getString("url") == chapter.url) saved else values(chapter)
    }
}
