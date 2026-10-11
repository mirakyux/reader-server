package com.htmake.reader.api.service

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/** New timestamps represent deliberate navigation; legacy/equal timestamps use the farther position. */
object ReadingPosition {
    fun shouldApply(saved: Book, incoming: Book): Boolean {
        if (incoming.durChapterIndex < 0 || incoming.durChapterPos < 0 || incoming.durChapterTime < 0) return false
        if (incoming.durChapterTime > 0 && incoming.durChapterTime != saved.durChapterTime) {
            return incoming.durChapterTime > saved.durChapterTime
        }
        return incoming.durChapterIndex > saved.durChapterIndex ||
            (incoming.durChapterIndex == saved.durChapterIndex && incoming.durChapterPos > saved.durChapterPos)
    }

    fun matchChapter(oldIndex: Int, oldTitle: String?, chapters: List<BookChapter>): Int {
        if (chapters.isEmpty()) return 0
        val candidates = chapters.indices.filter { !chapters[it].isVolume }.ifEmpty { chapters.indices.toList() }
        val title = oldTitle.orEmpty()
        val pure = pureTitle(title)
        if (pure.isNotBlank()) {
            val exact = candidates.filter { pureTitle(chapters[it].title) == pure }
            if (exact.isNotEmpty()) return exact.minBy { kotlin.math.abs(it - oldIndex) }
        }
        val number = chapterNumber(title)
        if (number != null) {
            val numbered = candidates.filter { chapterNumber(chapters[it].title) == number }
            if (numbered.isNotEmpty()) return numbered.minBy { kotlin.math.abs(it - oldIndex) }
        }
        if (pure.length > 2) {
            val best = candidates.maxByOrNull { similarity(pure, pureTitle(chapters[it].title)) }
            if (best != null && similarity(pure, pureTitle(chapters[best].title)) >= 0.72) return best
        }
        return candidates.minBy { kotlin.math.abs(it - oldIndex.coerceIn(0, chapters.lastIndex)) }
    }

    private val ordinal = Regex("第?([0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]", RegexOption.IGNORE_CASE)
    private fun pureTitle(title: String) = title.replace(ordinal, "").replace(Regex("[（(].*?[）)]"), "")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun chapterNumber(title: String): Int? {
        val value = ordinal.find(title)?.groupValues?.get(1) ?: return null
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '二' to 2, '两' to 2, '贰' to 2,
            '三' to 3, '叁' to 3, '四' to 4, '肆' to 4, '五' to 5, '伍' to 5, '六' to 6, '陆' to 6,
            '七' to 7, '柒' to 7, '八' to 8, '捌' to 8, '九' to 9, '玖' to 9)
        val units = mapOf('十' to 10, '拾' to 10, '百' to 100, '佰' to 100, '千' to 1000, '仟' to 1000, '万' to 10000)
        if (value.none { it in units }) return value.map { digits[it] ?: return null }.joinToString("").toIntOrNull()
        var total = 0; var section = 0; var digit = 0
        for (character in value) {
            if (character in digits) digit = digits.getValue(character)
            else {
                val unit = units[character] ?: return null
                if (unit == 10000) { total += (section + digit).coerceAtLeast(1) * unit; section = 0 }
                else section += digit.coerceAtLeast(1) * unit
                digit = 0
            }
        }
        return total + section + digit
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val left = a.windowed(2).ifEmpty { listOf(a) }.toSet()
        val right = b.windowed(2).ifEmpty { listOf(b) }.toSet()
        return 2.0 * left.intersect(right).size / (left.size + right.size)
    }
}
