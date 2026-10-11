package com.htmake.reader.api

import com.htmake.reader.api.service.ChapterEdits
import com.htmake.reader.api.service.ReadingPosition
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReadingParityTests {
    @TempDir lateinit var directory: Path

    @Test fun olderProgressCannotOverwriteNewAndNewerIntentionalRewindWorks() {
        val saved = Book(durChapterIndex = 8, durChapterPos = 120, durChapterTime = 500)
        assertFalse(ReadingPosition.shouldApply(saved, saved.copy(durChapterIndex = 12, durChapterTime = 499)))
        assertFalse(ReadingPosition.shouldApply(saved, saved.copy(durChapterPos = 119)))
        assertTrue(ReadingPosition.shouldApply(saved, saved.copy(durChapterPos = 121)))
        assertTrue(ReadingPosition.shouldApply(saved, saved.copy(durChapterIndex = 2, durChapterPos = 5, durChapterTime = 501)))
        assertFalse(ReadingPosition.shouldApply(saved, saved.copy(durChapterIndex = -1, durChapterTime = 600)))
        assertTrue(ReadingPosition.shouldApply(saved, saved.copy(durChapterIndex = 9, durChapterTime = 0)))
    }

    @Test fun switchSourceMatchesTitlesAndChineseChapterNumbersAcrossExtraVolumes() {
        val chapters = listOf(
            BookChapter(title = "第一卷", isVolume = true, index = 0),
            BookChapter(title = "第1章 初见", index = 1),
            BookChapter(title = "第二章 重逢", index = 2),
            BookChapter(title = "第003章 前路", index = 3)
        )
        assertEquals(2, ReadingPosition.matchChapter(1, "第二章 重逢", chapters))
        assertEquals(3, ReadingPosition.matchChapter(2, "第三章 前路（求订阅）", chapters))
        assertEquals(1, ReadingPosition.matchChapter(0, "第一章 初见", chapters))
        assertEquals(3, ReadingPosition.matchChapter(99, "unknown", chapters))
        assertEquals(0, ReadingPosition.matchChapter(0, "", emptyList()))
        assertEquals(1203, ReadingPosition.chapterNumber("第一千二百零三章"))
    }

    @Test fun editingPreservesOriginalAndRestoresItWithoutTouchingOtherChapters() {
        val root = directory.toFile()
        val chapter = BookChapter(index = 2, title = "original title", url = "https://fixture/2")
        val original = "原始正文 & 第一段\n第二段"
        java.io.File(root, "2.txt").writeText(original)
        java.io.File(root, "3.txt").writeText("other")
        ChapterEdits.save(root, chapter, "new title", "编辑后正文", original)
        assertEquals("编辑后正文", java.io.File(root, "2.txt").readText())
        assertEquals("new title", ChapterEdits.read(root, chapter)?.getString("title"))
        ChapterEdits.save(root, chapter, "newer title", "再次编辑", "wrong original")
        assertNull(ChapterEdits.read(root, chapter.copy(url = "https://different/2")))
        assertEquals(original, ChapterEdits.restore(root, chapter))
        assertEquals(original, java.io.File(root, "2.txt").readText())
        assertEquals("other", java.io.File(root, "3.txt").readText())
        assertNull(ChapterEdits.read(root, chapter))
    }
}
