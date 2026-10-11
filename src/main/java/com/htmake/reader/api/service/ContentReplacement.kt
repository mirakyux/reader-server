package com.htmake.reader.api.service

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReplaceRule
import java.util.regex.Pattern

/** Processes display/export text while keeping downloaded chapter caches unchanged. */
object ContentReplacement {
    fun apply(text: String, rules: List<ReplaceRule>, book: Book, title: Boolean = false): String =
        rules.withIndex().sortedWith(compareBy({ it.value.order }, { it.index })).fold(text) { content, entry ->
            val rule = entry.value
            if (!rule.isEnabled || (title && !rule.scopeTitle) || (!title && !rule.scopeContent) ||
                !matches(rule.scope, book, empty = true) || matches(rule.excludeScope, book, empty = false)) content
            else runCatching {
                if (rule.isRegex) Pattern.compile(rule.pattern).matcher(
                    DeadlineText(content, System.nanoTime() + rule.timeoutMillisecond.coerceIn(1, 30000) * 1_000_000)
                ).replaceAll(rule.replacement)
                else if (rule.pattern.isEmpty()) content else content.replace(rule.pattern, rule.replacement)
            }.getOrDefault(content)
        }
    private fun matches(scope: String?, book: Book, empty: Boolean): Boolean =
        if (scope.isNullOrBlank()) empty else scope.split(',', '，').any { it.trim() in listOf(book.name, book.origin, book.originName) }

    private class DeadlineText(private val value: String, private val deadline: Long) : CharSequence {
        override val length: Int get() = value.length
        override fun get(index: Int): Char {
            check(System.nanoTime() < deadline) { "替换规则执行超时" }
            return value[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineText(value.substring(startIndex, endIndex), deadline)
        override fun toString() = value
    }
}
