package io.legado.app.help

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.FileUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getFile
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.SourceActions
import io.legado.app.model.localBook.LocalBook
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

//import org.apache.commons.text.similarity.JaccardSimilarity

object BookHelp {
    private const val cacheImageFolderName = "images"
    private data class ImageKey(val source: String?, val book: String, val url: String)
    private class ImageDownload(val mutex: Mutex = Mutex(), var callers: Int = 0)
    private val downloadImages = mutableMapOf<ImageKey, ImageDownload>()

    private fun formatFolderName(folderName: String): String {
        return folderName.replace("[\\\\/:*?\"<>|.]".toRegex(), "")
    }

    fun formatAuthor(author: String?): String {
        return author
            ?.replace("作\\s*者[\\s:：]*".toRegex(), "")
            ?.replace("\\s+".toRegex(), " ")
            ?.trim { it <= ' ' }
            ?: ""
    }

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    fun getBookCacheDir(book: Book): File {
        val bookDir = book.getBookDir()
        if (bookDir.isEmpty()) {
            throw Exception("bookDir不能为空")
        }
        return com.htmake.reader.api.service.ChapterCache.directory(book, File(bookDir))
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        val file = getBookCacheDir(book).getFile(
            String.format("%d.txt", bookChapter.index)
        )
        com.htmake.reader.api.service.ChapterCache.read(file.parentFile, bookChapter.index, bookChapter)?.let { return it }
        if (book.isLocalBook()) {
            val content = LocalBook.getContent(book, bookChapter)
            if (content != null && book.isEpub()) {
                saveText(book, bookChapter, content)
            }
            return content
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        FileUtils.createFileIfNotExist(
            getBookCacheDir(book),
            String.format("%d.txt", bookChapter.index)
        ).delete()
    }

    suspend fun saveContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        saveText(book, bookChapter, content)
        saveImages(scope, bookSource, book, bookChapter, content)
    }

    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        // if (content.isEmpty()) return
        //保存文本
        com.htmake.reader.api.service.ChapterCache.save(getBookCacheDir(book), bookChapter, content)
    }

    @Suppress("UNUSED_PARAMETER") // Kept for existing callers; downloads belong to this suspending call.
    suspend fun saveImages(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) = coroutineScope {
        val images = linkedSetOf<String>()
        val matcher = AppPattern.imgPattern.matcher(content)
        while (matcher.find()) {
            matcher.group(1)?.let { src ->
                images.add(if (src.startsWith("data:", true)) src else NetworkUtils.getAbsoluteURL(bookChapter.getAbsoluteURL(), src))
            }
        }
        images.map { src -> async { saveImage(bookSource, book, src, bookChapter) } }.awaitAll()
        Unit
    }

    suspend fun saveImage(
        bookSource: BookSource?, book: Book, src: String,
        chapter: BookChapter? = null, refresh: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        val file = getImage(book, src)
        val key = ImageKey(bookSource?.getStorageNamespace(), getBookCacheDir(book).canonicalPath, src)
        val download = synchronized(downloadImages) {
            downloadImages.getOrPut(key) { ImageDownload() }.also { it.callers++ }
        }
        try {
            download.mutex.withLock {
                if (!refresh && file.isFile && file.length() > 0) return@withLock
                val bytes = SourceActions.imageBytes(bookSource, src, false, book, chapter)
                currentCoroutineContext().ensureActive()
                file.parentFile.mkdirs()
                val temporary = Files.createTempFile(file.parentFile.toPath(), file.name, ".part")
                try {
                    Files.write(temporary, bytes)
                    currentCoroutineContext().ensureActive()
                    try {
                        Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { Files.deleteIfExists(temporary) }
            }
        } finally {
            // Waiters retain the same mutex until the last caller leaves, including cancellation.
            synchronized(downloadImages) {
                if (--download.callers == 0) downloadImages.remove(key)
            }
        }
        Unit
    }

    fun getImage(book: Book, src: String): File {
        return getBookCacheDir(book).getFile(
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    fun getImageSuffix(src: String): String {
        val options = AnalyzeUrl.paramPattern.matcher(src)
        val url = if (options.find()) src.substring(0, options.start()) else src
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val suffix = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return suffix.takeIf { it.length in 1..5 && it.matches(Regex("[a-z0-9]+")) } ?: "jpg"
    }
}
