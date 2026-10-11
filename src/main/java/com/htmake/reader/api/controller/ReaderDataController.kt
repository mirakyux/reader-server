package com.htmake.reader.api.controller

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.htmake.reader.api.ReturnData
import com.htmake.reader.api.service.*
import com.htmake.reader.api.service.advanced.*
import com.htmake.reader.utils.getStoragePath
import io.legado.app.data.entities.RssSource
import io.legado.app.model.DebugLog
import io.legado.app.model.rss.Rss
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

class ReaderDataController(coroutineContext: CoroutineContext, private val books: BookController): BaseController(coroutineContext) {
    private val files = UserDataFiles(Path.of(getStoragePath(), "data"))
    private val library = RssLibrary(files)
    private val remote = RemoteWebdav(files)
    private val statistics = ReadingStatistics(AdvancedStore(Path.of(getStoragePath(), "advanced")), files)
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        CoroutineScope(coroutineContext).launch {
            while (isActive) {
                delay(60000)
                withContext(Dispatchers.IO) { files.users().forEach { user ->
                    try {
                        val saved = remote.config(user)
                        if (saved.flag("dailyBackup") && saved.string("url").isNotBlank() && saved.string("lastBackupDay") != LocalDate.now().toString()) {
                            locks.computeIfAbsent(user) { Mutex() }.withLock {
                                val latest = remote.config(user)
                                if (latest.flag("dailyBackup") && latest.string("lastBackupDay") != LocalDate.now().toString()) {
                                    backup(user); remote.status(user, LocalDate.now().toString())
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { runCatching { remote.status(user, error = "自动备份失败，请检查远程 WebDAV 连接后重试") } }
                } }
            }
        }
    }
    private fun input(context: RoutingContext) = JsonParser.parseString(context.body().asString()).asJsonObject
    private suspend fun operation(context: RoutingContext, block: suspend (String) -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val user = getUserNameSpace(context)
        return try { ReturnData().setData(withContext(Dispatchers.IO) { block(user) }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IllegalArgumentException) { ReturnData().setErrorMsg(error.message ?: "参数无效") }
        catch (_: Exception) { ReturnData().setErrorMsg("操作失败，请检查订阅源或远程 WebDAV 配置") }
    }
    suspend fun rssLibrary(context: RoutingContext): ReturnData = operation(context) { user ->
        library.list(user, context.queryParam("kind").firstOrNull() ?: "favorites", context.queryParam("group").firstOrNull().orEmpty())
    }
    suspend fun saveRssArticle(context: RoutingContext): ReturnData = operation(context) { user ->
        val body = input(context); library.save(user, body.string("kind", "favorites"), body.getAsJsonObject("article") ?: error("请选择文章"))
    }
    suspend fun deleteRssArticle(context: RoutingContext): ReturnData = operation(context) { user ->
        val body = input(context); library.remove(user, body.string("kind", "favorites"), body)
    }
    suspend fun cacheRssArticle(context: RoutingContext): ReturnData = operation(context) { user ->
        val article = input(context).getAsJsonObject("article") ?: error("请选择文章")
        val source = RssSourceController(coroutineContext).getRssSourceByURL(article.string("origin"), user) ?: error("订阅源不存在")
        val html = article.string("content").ifBlank {
            if (source.ruleContent.isNullOrBlank()) article.string("description") else Rss.getContent(
                io.legado.app.data.entities.RssArticle(origin = article.string("origin"), link = article.string("link")), source.ruleContent!!, source, null)
        }
        val document = org.jsoup.Jsoup.parseBodyFragment(html, article.string("link"))
        var downloaded = 0L
        val images = document.select("img[src]")
        require(images.size <= 100) { "文章图片超过离线缓存上限（100 张）" }
        for (image in images) {
            val url = image.absUrl("src")
            if (image.attr("src").startsWith("data:")) continue
            if (url.isBlank()) continue
            val bytes = io.legado.app.model.analyzeRule.AnalyzeUrl(url, source = source, headerMapF = source.getHeaderMap()).getByteArrayAwait()
            downloaded += bytes.size
            require(downloaded <= 16L * 1024 * 1024) { "文章图片超过离线缓存上限（16 MiB）" }
            val type = java.net.URLConnection.guessContentTypeFromStream(bytes.inputStream()) ?: "application/octet-stream"
            require(type.startsWith("image/")) { "无法识别文章图片，离线缓存未完成" }
            image.attr("src", "data:$type;base64," + java.util.Base64.getEncoder().encodeToString(bytes)).removeAttr("srcset")
        }
        document.select("script,iframe,object,embed").remove()
        article.addProperty("content", document.body().html())
        library.save(user, "offline", article)
    }
    suspend fun rssCategories(context: RoutingContext): ReturnData = operation(context) { user ->
        val source = RssSourceController(coroutineContext).getRssSourceByURL(context.queryParam("sourceUrl").firstOrNull().orEmpty(), user) ?: error("订阅源不存在")
        source.sortUrls().map { mapOf("title" to it.first, "url" to it.second) }
    }
    suspend fun debugRssSource(context: RoutingContext): ReturnData = operation(context) { user ->
        val body = input(context)
        val source = body.getAsJsonObject("source")?.let { io.vertx.core.json.JsonObject(it.toString()).mapTo(RssSource::class.java) }
            ?: RssSourceController(coroutineContext).getRssSourceByURL(body.string("sourceUrl"), user) ?: error("订阅源不存在")
        source.setUserNamespace(user)
        val logs = mutableListOf<String>()
        val debugger = object : DebugLog { override fun log(sourceUrl: String?, msg: String?, isHtml: Boolean) { if (logs.size < 500) logs.add(msg.orEmpty().take(5000)) } }
        val start = System.currentTimeMillis()
        val result = Rss.getArticles(body.string("sortName"), body.string("sortUrl").ifBlank { source.sortUrls().firstOrNull()?.second ?: source.sourceUrl }, source, body.integer("page", 1), debugger, body.string("key"))
        val content = result.first.firstOrNull()?.let { article -> if (source.ruleContent.isNullOrBlank()) article.description.orEmpty() else Rss.getContent(article, source.ruleContent!!, source, debugger) }.orEmpty()
        jsonObject("articles" to result.first, "nextUrl" to result.second, "content" to content, "logs" to logs, "elapsedMs" to System.currentTimeMillis() - start)
    }
    suspend fun deleteReadingStatistics(context: RoutingContext): ReturnData = operation(context) { _ ->
        val owner = if (appConfig.secure) (context.get("username") as String?)?.takeIf { it.isNotBlank() } ?: error("请登录后使用") else "default"
        statistics.delete(owner, input(context))
    }
    suspend fun remoteConfig(context: RoutingContext): ReturnData = operation(context) { user -> remote.config(user) }
    suspend fun saveRemoteConfig(context: RoutingContext): ReturnData = operation(context) { user -> remote.save(user, input(context)) }
    suspend fun testRemote(context: RoutingContext): ReturnData = operation(context) { user -> remote.test(user) }
    suspend fun listRemoteBackups(context: RoutingContext): ReturnData = operation(context) { user -> remote.list(user, "").filter { !it.directory && it.name.endsWith(".zip", true) }.sortedByDescending { it.name } }
    suspend fun backupRemote(context: RoutingContext): ReturnData = operation(context) { user -> locks.computeIfAbsent(user) { Mutex() }.withLock {
        backup(user).also { remote.status(user, LocalDate.now().toString()) }
    } }
    private suspend fun backup(user: String): Map<String, String> {
        require(books.saveToWebdav("", user)) { "本地备份创建失败" }
        val local = books.getLastBackFileFromWebdav(user)?.let { File(it) } ?: error("备份文件缺失")
        remote.ensureBase(user)
        remote.upload(user, local.name, local.readBytes())
        return mapOf("path" to local.name)
    }
    suspend fun restoreRemote(context: RoutingContext): ReturnData = operation(context) { user -> locks.computeIfAbsent(user) { Mutex() }.withLock {
        val path = input(context).string("path")
        require(path.endsWith(".zip", true) && !path.contains('/')) { "请选择远程根目录中的 ZIP 备份" }
        val temporary = Files.createTempFile("reader-remote-restore-", ".zip")
        try {
            Files.write(temporary, remote.download(user, path))
            require(books.syncFromWebdav(temporary.toString(), user)) { "远程备份校验或恢复失败" }
            true
        } finally { Files.deleteIfExists(temporary) }
    } }
    suspend fun syncRemoteProgress(context: RoutingContext): ReturnData = operation(context) { user -> locks.computeIfAbsent(user) { Mutex() }.withLock {
        remote.mkdir(user, "bookProgress")
        var received = 0; var sent = 0
        val entries = remote.list(user, "bookProgress/")
        for (entry in entries) {
            if (entry.directory || !entry.name.endsWith(".json", true)) continue
            require(entry.size <= 1024 * 1024) { "远程进度文件过大" }
            val bytes = remote.download(user, entry.path)
            require(bytes.size <= 1024 * 1024) { "远程进度文件过大" }
            val temporary = Files.createTempFile("reader-progress-", ".json")
            try { Files.write(temporary, bytes); books.syncBookProgressFromWebdav(temporary.toFile(), user); received++ }
            finally { Files.deleteIfExists(temporary) }
        }
        val shelf = files.read(user, "bookshelf.json").asJsonArray
        for (element in shelf) {
            val book = element.asJsonObject
            val name = (book.string("name") + "_" + book.string("author")).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".json"
            val progress = jsonObject("name" to book.string("name"), "author" to book.string("author"), "durChapterIndex" to book.integer("durChapterIndex"),
                "durChapterPos" to book.integer("durChapterPos"), "durChapterTime" to book.long("durChapterTime"), "durChapterTitle" to book.string("durChapterTitle"))
            val existing = entries.firstOrNull { it.name == name }
            remote.upload(user, "bookProgress/$name", progress.toString().toByteArray(), existing?.etag?.takeIf { it.isNotBlank() }, existing == null); sent++
        }
        mapOf("received" to received, "sent" to sent)
    } }
}
