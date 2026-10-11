package com.htmake.reader.api

import com.htmake.reader.api.service.ContentReplacement
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReplaceRule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReadingEnhancementTests {
    @Test fun scopeTargetsOrderExclusionsAndRawInput() {
        val book = Book(name = "fixture", origin = "source", originName = "Source")
        val rules = listOf(
            ReplaceRule(pattern = "changed", replacement = "final", order = 2),
            ReplaceRule(pattern = "original", replacement = "changed", scope = "fixture", order = 1),
            ReplaceRule(pattern = "final", replacement = "excluded", excludeScope = "source", order = 3),
            ReplaceRule(pattern = "original", replacement = "Title", scopeTitle = true, scopeContent = false)
        )
        assertEquals("final", ContentReplacement.apply("original", rules, book))
        assertEquals("Title", ContentReplacement.apply("original", rules, book, title = true))
        assertEquals("original", ContentReplacement.apply("original", rules.dropLast(1), Book(name = "Other")))
    }
    @Test fun backtrackingRegexStopsAtItsDeadline() {
        val start = System.nanoTime()
        val text = "a".repeat(20000) + "!"
        val rule = ReplaceRule(pattern = "(a+)+$", replacement = "bad", isRegex = true, timeoutMillisecond = 10)
        assertEquals(text, ContentReplacement.apply(text, listOf(rule), Book()))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }
    @Test fun rangesUseExclusiveEndAndRejectReversedSelection() {
        val bookmark = Bookmark(chapterIndex = 2, chapterPos = 7, bookmarkType = 1, endChapterIndex = 4, endChapterPos = 9)
        bookmark.validate()
        assertEquals(7 until Int.MAX_VALUE, bookmark.positions(2))
        assertEquals(0 until Int.MAX_VALUE, bookmark.positions(3))
        assertEquals(0 until 9, bookmark.positions(4))
        assertNull(bookmark.positions(5))
        assertThrows(IllegalArgumentException::class.java) { bookmark.copy(endChapterIndex = 1).validate() }
        Bookmark(chapterIndex = 9).validate()
    }
}
