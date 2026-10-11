package io.legado.app.model.jsSource

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException

/** A validation result reports the actual stage instead of treating search as full success. */
object SourceValidation {
    suspend fun check(source: BookSource, key: String): Map<String, Any?> {
        val web = WebBook(source, false, userNameSpace = source.getUserNamespace())
        val stages = arrayListOf<Map<String, Any?>>()
        var books: List<SearchBook> = emptyList()
        suspend fun stage(name: String, work: suspend () -> String): Boolean {
            val started = System.nanoTime()
            return try {
                val detail = work()
                stages.add(mapOf("stage" to name, "status" to "passed", "detail" to detail,
                    "elapsedMs" to (System.nanoTime() - started) / 1_000_000))
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                stages.add(mapOf("stage" to name, "status" to "failed", "detail" to (failure.message ?: "执行失败"),
                    "elapsedMs" to (System.nanoTime() - started) / 1_000_000))
                false
            }
        }
        if (!source.searchUrl.isNullOrBlank() || !source.mainJs.isNullOrBlank()) stage("search") {
            books = web.searchBook(key); require(books.isNotEmpty()) { "搜索未返回书籍，请更换关键词" }; "${books.size} 本书"
        } else stages.add(mapOf("stage" to "search", "status" to "skipped", "detail" to "未配置搜索"))
        if (source.enabledExplore && !source.exploreUrl.isNullOrBlank()) stage("explore") {
            val ui = SourceActions.exploreUi(source)
            val categories = ui["categories"] as? List<*>
            val url = categories?.mapNotNull { (it as? Map<*, *>)?.get("url")?.toString()?.takeIf(String::isNotBlank) }?.firstOrNull()
                ?: error("发现没有可执行的分类链接")
            val found = web.exploreBook(url); require(found.isNotEmpty()) { "发现未返回书籍" }
            if (books.isEmpty()) books = found
            "${found.size} 本书"
        } else stages.add(mapOf("stage" to "explore", "status" to "skipped", "detail" to "未启用发现"))
        var book = books.firstOrNull()?.toBook()
        if (book == null) stages.add(mapOf("stage" to "details", "status" to "blocked", "detail" to "没有可校验的书籍"))
        else if (stage("details") { book = web.getBookInfo(book!!); require(book!!.name.isNotBlank()) { "详情书名为空" }; book!!.name }) {
            if (source.bookSourceType == 3) stage("download") { require(book!!.downloadUrls?.isNotEmpty() == true) { "下载链接为空" }; "${book!!.downloadUrls!!.size} 个链接" }
            else {
                var chapters: List<io.legado.app.data.entities.BookChapter> = emptyList()
                if (stage("toc") { chapters = web.getChapterList(book!!); require(chapters.isNotEmpty()) { "目录为空" }; "${chapters.size} 章" }) {
                    stage("content") {
                        val index = chapters.indexOfFirst { !it.isVolume }.takeIf { it >= 0 } ?: error("目录没有正文章节")
                        val content = web.getBookContent(book!!, chapters[index], chapters.getOrNull(index + 1)?.url)
                        require(content.isNotBlank()) { "正文为空" }; "${content.length} 字符"
                    }
                }
            }
        }
        return mapOf("key" to key, "books" to books, "count" to books.size, "stages" to stages,
            "success" to stages.all { it["status"] == "passed" || it["status"] == "skipped" }, "bookSourceUrl" to source.bookSourceUrl)
    }
}
