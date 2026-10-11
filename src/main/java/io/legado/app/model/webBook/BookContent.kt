package io.legado.app.model.webBook


import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.DebugLog
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.getCheckedResponse
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.HtmlFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object BookContent {

    suspend fun analyzeContent(
            body: String?,
            book: Book,
            bookChapter: BookChapter,
            bookSource: BookSource,
            baseUrl: String,
            redirectUrl: String,
            nextChapterUrl: String? = null,
            debugLog: DebugLog? = null
    ): String {
        body ?: throw Exception(
                "error_get_web_content"
        )
        debugLog?.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        val mNextChapterUrl = if (!nextChapterUrl.isNullOrEmpty()) {
            nextChapterUrl
        } else {
            // appDb.bookChapterDao.getChapter(book.bookUrl, bookChapter.index + 1)?.url
            null
        }
        val content = StringBuilder()
        val nextUrlList = arrayListOf(redirectUrl)
        val contentRule = bookSource.getContentRule()
        val analyzeRule = AnalyzeRule(book, bookSource).setContent(body, baseUrl)
        analyzeRule.chapter = bookChapter
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.nextChapterUrl = mNextChapterUrl
        var contentData = analyzeContent(
            book, baseUrl, redirectUrl, body, contentRule, bookChapter, bookSource, mNextChapterUrl
        )
        content.append(contentData.first)
        if (contentData.second.size == 1) {
            var nextUrl = contentData.second[0]
            while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                if (!mNextChapterUrl.isNullOrEmpty()
                    && NetworkUtils.getAbsoluteURL(redirectUrl, nextUrl)
                    == NetworkUtils.getAbsoluteURL(redirectUrl, mNextChapterUrl)
                ) break
                nextUrlList.add(nextUrl)
                val res = AnalyzeUrl(
                    mUrl = nextUrl,
                    source = bookSource,
                    ruleData = book,
                    chapter = bookChapter,
                    headerMapF = bookSource.getHeaderMap()
                ).getCheckedResponse(jsStr = contentRule.webJs, sourceRegex = contentRule.sourceRegex, debugLog = debugLog)
                res.body?.let { nextBody ->
                    contentData = analyzeContent(
                        book, nextUrl, res.url, nextBody, contentRule,
                        bookChapter, bookSource, mNextChapterUrl, false
                    )
                    nextUrl =
                        if (contentData.second.isNotEmpty()) contentData.second[0] else ""
                    content.append("\n").append(contentData.first)
                }
            }
            debugLog?.log(bookSource.bookSourceUrl, "◇本章总页数:${nextUrlList.size}")
        } else if (contentData.second.size > 1) {
            debugLog?.log(bookSource.bookSourceUrl, "◇并发解析正文,总页数:${contentData.second.size}")
            withContext(IO) {
                val asyncArray = Array(contentData.second.size) {
                    async(IO) {
                        val urlStr = contentData.second[it]
                        val analyzeUrl = AnalyzeUrl(
                            mUrl = urlStr,
                            source = bookSource,
                            ruleData = book,
                            chapter = bookChapter,
                            headerMapF = bookSource.getHeaderMap()
                        )
                        val res = analyzeUrl.getCheckedResponse(jsStr = contentRule.webJs, sourceRegex = contentRule.sourceRegex, debugLog = debugLog)
                        analyzeContent(
                            book, urlStr, res.url, res.body!!, contentRule,
                            bookChapter, bookSource, mNextChapterUrl, false
                        ).first
                    }
                }
                asyncArray.forEach { coroutine ->
                    content.append("\n").append(coroutine.await())
                }
            }
        }
        contentRule.subContent?.takeIf { it.isNotBlank() }?.let { rule ->
            val raw = analyzeRule.getString(rule)
            bookChapter.subContent = raw
            if (book.type == 0 && raw.isNotBlank()) content.append("\n").append(raw)
            else if (book.type == 1 || book.type == 4) {
                val value = if (raw.trim().startsWith("http", true)) AnalyzeUrl(raw.trim(), source = bookSource, ruleData = book)
                    .getStrResponseAwait(debugLog = debugLog).body else raw
                if (book.type == 1) bookChapter.putLyric(value) else bookChapter.putDanmaku(value)
            }
        }
        var contentStr = content.toString()
        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            contentStr = analyzeRule.getString(replaceRegex, contentStr)
        }
        debugLog?.log(bookSource.bookSourceUrl, "┌获取章节名称")
        debugLog?.log(bookSource.bookSourceUrl, "└${bookChapter.title}")
        debugLog?.log(bookSource.bookSourceUrl, "┌获取正文内容 (长度：${contentStr.length})")
        if (contentStr.length > 300) {
            debugLog?.log(bookSource.bookSourceUrl, "└\n${contentStr.substring(0, 50)} ... ${contentStr.substring(contentStr.length - 30, contentStr.length)}")
        } else {
            debugLog?.log(bookSource.bookSourceUrl, "└\n${contentStr}")
        }
        return contentStr
    }

    @Throws(Exception::class)
    private fun analyzeContent(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        contentRule: ContentRule,
        chapter: BookChapter,
        bookSource: BookSource,
        nextChapterUrl: String?,
        printLog: Boolean = true,
        debugLog: DebugLog? = null
    ): Pair<String, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body, baseUrl)
        val rUrl = analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.nextChapterUrl = nextChapterUrl
        val nextUrlList = arrayListOf<String>()
        analyzeRule.chapter = chapter
        if (printLog && !contentRule.title.isNullOrBlank()) {
            analyzeRule.getString(contentRule.title).takeIf { it.isNotBlank() }?.let { chapter.title = it }
        }
        //获取正文
        var content = analyzeRule.getString(contentRule.content)
        content = HtmlFormatter.formatKeepImg(content, rUrl)
        //获取下一页链接
        val nextUrlRule = contentRule.nextContentUrl
        if (!nextUrlRule.isNullOrEmpty()) {
            if(printLog) debugLog?.log(bookSource.bookSourceUrl, "┌获取正文下一页链接")
            analyzeRule.getStringList(nextUrlRule, isUrl = true)?.let {
                nextUrlList.addAll(it)
            }
            if(printLog) debugLog?.log(bookSource.bookSourceUrl, "└" + nextUrlList.joinToString("，"))
        }
        return Pair(content, nextUrlList)
    }
}
