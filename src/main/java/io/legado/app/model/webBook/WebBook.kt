package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.getCheckedResponse
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.BookContent
import io.legado.app.model.webBook.BookInfo
import io.legado.app.model.webBook.BookList
import io.legado.app.model.Debug
import io.legado.app.model.DebugLog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

class WebBook(val bookSource: BookSource, val debugLog: Boolean = true, var debugLogger: DebugLog? = null, userNameSpace: String = "default") {

    constructor(bookSourceString: String, debugLog: Boolean = true, userNameSpace: String = "default") : this(BookSource.fromJson(bookSourceString, userNameSpace).getOrThrow(), debugLog, userNameSpace = userNameSpace)

    init { bookSource.runtimeUserNamespace = userNameSpace }

    private val jsBook get() = io.legado.app.model.jsSource.JsSourceBook(bookSource, debugger)

    val sourceUrl: String
        get() = bookSource.bookSourceUrl

    val debugger: DebugLog?
        get() {
            if (debugLogger != null) {
                return debugLogger
            }
            if (debugLog) {
                return Debug
            }
            return null
        }

    /**
     * 搜索
     */
    suspend fun searchBook(
        key: String,
        page: Int? = 1
    ): List<SearchBook> {
        if (!bookSource.mainJs.isNullOrBlank()) return withContext(Dispatchers.IO) { jsBook.search(key, page) }
        val variableBook = SearchBook()
        return bookSource.searchUrl?.let { searchUrl ->
            val analyzeUrl = AnalyzeUrl(
                mUrl = searchUrl,
                key = key,
                page = page,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = variableBook,
                headerMapF = bookSource.getHeaderMap(true),
            )
            val res = analyzeUrl.getCheckedResponse(debugLog = debugger)
            BookList.analyzeBookList(
                res.body,
                bookSource,
                analyzeUrl,
                res.url,
                variableBook,
                true,
                debugLog = debugger
            ).map {
                it.tocHtml = ""
                it.infoHtml = ""
                it
            }
        } ?: arrayListOf()

    }

    /**
     * 发现
     */
    suspend fun exploreBook(
        url: String,
        page: Int? = 1
    ): List<SearchBook> {
        if (!bookSource.mainJs.isNullOrBlank()) return withContext(Dispatchers.IO) { jsBook.explore(url, page) }
        val variableBook = SearchBook()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = variableBook,
            headerMapF = bookSource.getHeaderMap(true)
        )
        val res = analyzeUrl.getCheckedResponse(debugLog = debugger)
        return BookList.analyzeBookList(
            res.body,
            bookSource,
            analyzeUrl,
            res.url,
            variableBook,
            false,
            debugLog = debugger
        )
    }

    /**
     * 书籍信息
     */
    suspend fun getBookInfo(book: Book, canReName: Boolean = true): Book {
        if (!bookSource.mainJs.isNullOrBlank()) return withContext(Dispatchers.IO) { jsBook.info(book, canReName) }
        book.type = bookSource.bookSourceType
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                book,
                book.infoHtml,
                bookSource,
                book.bookUrl,
                book.bookUrl,
                canReName
            )
            return book
        } else {
            return getBookInfo(book.bookUrl, canReName)
        }
    }

    /**
     * 书籍信息
     */
    suspend fun getBookInfo(bookUrl: String, canReName: Boolean = true): Book {
        val book = Book()
        book.bookUrl = bookUrl
        book.origin = bookSource.bookSourceUrl
        book.originName = bookSource.bookSourceName
        book.originOrder = bookSource.customOrder
        book.type = bookSource.bookSourceType
        if (!bookSource.mainJs.isNullOrBlank()) return withContext(Dispatchers.IO) { jsBook.info(book, canReName) }
        val analyzeUrl = AnalyzeUrl(
            mUrl = book.bookUrl,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = book,
            headerMapF = bookSource.getHeaderMap(true)
        )
        val res = analyzeUrl.getCheckedResponse(debugLog = debugger)

        BookInfo.analyzeBookInfo(book, res.body, bookSource, book.bookUrl, res.url, canReName, debugLog = debugger)
        book.tocHtml = null
        return book
    }

    /**
     * 目录
     */
    suspend fun getChapterList(
        book: Book,
        isFromBookInfo: Boolean = false
    ): List<BookChapter> {
        require(bookSource.bookSourceType != 3) { "文件书源请使用 downloadUrls 下载，不提供目录" }
        if (!bookSource.mainJs.isNullOrBlank()) return withContext(Dispatchers.IO) { jsBook.chapters(book) }
        bookSource.getTocRule().preUpdateJs?.takeIf { it.isNotBlank() }?.let {
            AnalyzeRule(book, bookSource, preUpdateJs = true, isFromBookInfo = isFromBookInfo).evalJS(it, null)
        }
        book.type = bookSource.bookSourceType
        return if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
            BookChapterList.analyzeChapterList(
                book,
                book.tocHtml,
                bookSource,
                book.tocUrl,
                book.tocUrl,
                isFromBookInfo = isFromBookInfo
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.tocUrl,
                baseUrl = book.bookUrl,
                source = bookSource,
                ruleData = book,
                headerMapF = bookSource.getHeaderMap(true)
            )
            val res = analyzeUrl.getCheckedResponse(debugLog = debugger)
            return BookChapterList.analyzeChapterList(book, res.body, bookSource, book.tocUrl, res.url,
                debugLog = debugger, isFromBookInfo = isFromBookInfo)
        }
    }

    /**
     * 章节内容
     */
    suspend fun getBookContent(
       book: Book,
       bookChapter: BookChapter,
        // bookChapterUrl:String,
        nextChapterUrl: String? = null
    ): String {
        require(bookSource.bookSourceType != 3) { "文件书源请使用 downloadUrls 下载，不提供正文" }
       if (!bookSource.mainJs.isNullOrBlank()) return withContext(Dispatchers.IO) { jsBook.content(book, bookChapter, nextChapterUrl) }
       if (bookSource.getContentRule().content.isNullOrEmpty()) {
            debugger?.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接: ${bookChapter.url}")
            return bookChapter.url
       }
       if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            debugger?.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
//        val body = if (book != null && bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
//            book.tocHtml
//        } else {
        logger.info { "bookChapterUrl: ${bookChapter.url} ${bookChapter.getAbsoluteURL()}" }
        val analyzeUrl = AnalyzeUrl(
            mUrl = bookChapter.getAbsoluteURL(),
            baseUrl = book.tocUrl,
            source = bookSource,
            ruleData = book,
            chapter = bookChapter,
            headerMapF = bookSource.getHeaderMap(true)
        )
        val res = analyzeUrl.getCheckedResponse(
            jsStr = bookSource.getContentRule().webJs,
            sourceRegex = bookSource.getContentRule().sourceRegex,
            debugLog = debugger
        )
        return BookContent.analyzeContent(
            res.body,
            book,
            bookChapter,
            bookSource,
            bookChapter.url,
            res.url,
            nextChapterUrl,
            debugLog = debugger
        )
    }
}
