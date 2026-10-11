package com.htmake.reader.api

import com.htmake.reader.api.service.ChapterMetadata
import io.legado.app.data.entities.BookChapter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChapterMetadataTests {
    @TempDir lateinit var directory: Path
    @Test fun titleAndMediaMetadataSurviveCacheReadsWithoutLeakingToDifferentChapters() {
        val chapter = BookChapter(index = 2, url = "https://fixture/chapter", title = "正文标题", isPay = true)
        chapter.putLyric("[00:01]歌词")
        ChapterMetadata.save(directory.toFile(), chapter)
        val originalCatalog = BookChapter(index = 2, url = chapter.url, title = "目录标题")
        assertEquals("正文标题", ChapterMetadata.read(directory.toFile(), originalCatalog).getString("title"))
        assertEquals("[00:01]歌词", ChapterMetadata.read(directory.toFile(), originalCatalog).getString("lyric"))
        assertTrue(ChapterMetadata.read(directory.toFile(), originalCatalog).getBoolean("isPay"))
        assertEquals("目录标题", ChapterMetadata.read(directory.toFile(), originalCatalog.copy(url = "https://fixture/different")).getString("title"))
    }
}
