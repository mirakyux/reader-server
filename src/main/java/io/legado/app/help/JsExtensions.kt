package io.legado.app.help

import cn.hutool.crypto.digest.DigestUtil
import io.legado.app.utils.Base64
import io.legado.app.constant.AppConst.dateFormat
import io.legado.app.help.http.*
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.*
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jsoup.Connection
import org.jsoup.Jsoup
import com.htmake.reader.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.text.SimpleDateFormat
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * js扩展类, 在js中通过java变量调用
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 */
@Suppress("unused")
interface JsExtensions {

    fun getSource(): BaseSource?
    fun getTag(): String? = getSource()?.getTag()

    fun startBrowser(url: String, title: String) = startBrowser(url, title, null)

    fun startBrowser(url: String, title: String, html: String?) {
        io.legado.app.help.source.SourceInteractions.request(getSource(), "browser", url, title, html)
    }

    fun startBrowserAwait(url: String, title: String): StrResponse = startBrowserAwait(url, title, true, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse =
        startBrowserAwait(url, title, refetchAfterSuccess, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
        val source = requireNotNull(getSource()) { "网页验证缺少书源" }
        val interaction = io.legado.app.help.source.SourceInteractions.request(source, "browser", url, title, html,
            waiting = true, refetchAfterSuccess = refetchAfterSuccess)
        return io.legado.app.help.source.SourceInteractions.await(source, interaction)
    }

    fun getVerificationCode(imageUrl: String): String {
        val source = requireNotNull(getSource()) { "图片验证码缺少书源" }
        val image = requireNotNull(source.getKey().toHttpUrl().resolve(imageUrl)) { "验证码图片必须是 HTTP 链接" }
        require(image.scheme == "http" || image.scheme == "https") { "验证码图片必须是 HTTP 链接" }
        val document = Jsoup.parse("""
            <!doctype html><html><head><meta charset="utf-8"><title>图片验证码</title>
            <style>body{font:18px sans-serif;margin:24px}img{display:block;max-width:480px;max-height:240px;margin:20px 0}input{display:block;width:320px;padding:12px;font:24px monospace;margin:12px 0}</style>
            </head><body><h1>图片验证码</h1><img id="reader-verification-image" alt="验证码图片">
            <label for="reader-verification-code">验证码</label>
            <input id="reader-verification-code" autofocus autocomplete="off" value="" oninput="this.setAttribute('value',this.value)">
            <p>输入图片中的验证码，然后保存并关闭验证窗口继续。</p></body></html>
        """.trimIndent())
        // Use the DOM serializer to escape the resolved URL instead of interpolating it into HTML.
        document.getElementById("reader-verification-image")!!.attr("src", image.toString())
        val response = startBrowserAwait(source.getKey(), "图片验证码", false, document.outerHtml())
        val code = Jsoup.parse(response.body.orEmpty()).getElementById("reader-verification-code")?.attr("value")?.trim().orEmpty()
        require(code.isNotEmpty()) { "验证码不能为空" }
        return code
    }

    fun openVideoPlayer(url: String, title: String) = openVideoPlayer(url, title, false)

    fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {
        io.legado.app.help.source.SourceInteractions.request(getSource(), "video", url, title, isFloat = isFloat)
    }

    fun openUrl(url: String) = openUrl(url, null)
    fun openUrl(url: String, mimeType: String?) {
        require(url.length < 64 * 1024) { "openUrl parameter url too long" }
        if (mimeType?.startsWith("video/", true) == true) openVideoPlayer(url, getTag() ?: "书源视频")
        else startBrowser(url, getTag() ?: "书源网页")
    }

    /**
     * 访问网络,返回String
     */
    fun ajax(urlStr: String): String? = ajax(urlStr, null)
    fun ajax(url: Any): String? = ajax(url, null)
    fun ajax(url: Any, callTimeout: Long?): String? = ajax(when (url) {
        is List<*> -> url.firstOrNull()?.toString().orEmpty()
        is org.mozilla.javascript.NativeArray -> url.get(0, url)?.toString().orEmpty()
        else -> url.toString()
    }, callTimeout)

    fun ajax(urlStr: String, callTimeout: Long?): String? {
        return runBlocking {
            kotlin.runCatching {
                val analyzeUrl = AnalyzeUrl(urlStr, source = getSource(), callTimeout = callTimeout)
                analyzeUrl.getStrResponse().body
            }.onFailure {
                it.printOnDebug()
            }.getOrElse {
                it.msg
            }
        }
    }

    /**
     * 并发访问网络
     */
    fun ajaxAll(urlList: Array<String>): Array<StrResponse?> = ajaxAll(urlList, false)
    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse?> {
        return runBlocking {
            val asyncArray = Array(urlList.size) {
                async(IO) {
                    val url = urlList[it]
                    val analyzeUrl = AnalyzeUrl(url, source = getSource())
                    analyzeUrl.getStrResponseAwait(skipRateLimit = skipRateLimit)
                }
            }
            val resArray = Array<StrResponse?>(urlList.size) {
                asyncArray[it].await()
            }
            resArray
        }
    }

    fun ajaxTestAll(urlList: Array<String>, timeout: Int): Array<StrResponse> = ajaxTestAll(urlList, timeout, false)
    fun ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean): Array<StrResponse> = runBlocking {
        require(timeout > 0) { "超时必须大于 0" }
        urlList.map { url -> async(IO) {
            val started = System.nanoTime()
            val response = try { AnalyzeUrl(url, source = getSource(), callTimeout = timeout.toLong()).getStrResponseAwait(skipRateLimit = skipRateLimit) }
                catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
                catch (failure: Exception) { StrResponse(url, failure.message).apply { putCallTime(when (failure) {
                    is java.net.SocketTimeoutException -> -2
                    is java.net.UnknownHostException -> -3
                    is java.net.ConnectException -> -4
                    is java.net.SocketException -> -5
                    is javax.net.ssl.SSLException -> -6
                    is java.io.InterruptedIOException -> if (failure.message?.contains("timeout", true) == true) -1 else -7
                    else -> -7
                }) } }
            if (response.getCallTime() >= 0) response.putCallTime(((System.nanoTime() - started) / 1_000_000).toInt())
            response
        } }.map { it.await() }.toTypedArray()
    }

    /**
     * 访问网络,返回Response<String>
     */
    fun connect(urlStr: String): StrResponse {
        return runBlocking {
            val analyzeUrl = AnalyzeUrl(urlStr, source = getSource())
            kotlin.runCatching {
                analyzeUrl.getStrResponseAwait()
            }.onFailure {
                it.printOnDebug()
            }.getOrElse {
                StrResponse(analyzeUrl.url, it.localizedMessage)
            }
        }
    }

    fun connect(urlStr: String, header: String?): StrResponse = connect(urlStr, header, null)

    fun connect(urlStr: String, header: String?, callTimeout: Long?): StrResponse {
        return runBlocking {
            val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
            val analyzeUrl = AnalyzeUrl(urlStr, headerMapF = headerMap, source = getSource(), callTimeout = callTimeout)
            kotlin.runCatching {
                analyzeUrl.getStrResponseAwait()
            }.onFailure {
                it.printOnDebug()
            }.getOrElse {
                StrResponse(analyzeUrl.url, it.localizedMessage)
            }
        }
    }

    /**
     * 使用webView访问网络
     * @param html 直接用webView载入的html, 如果html为空直接访问url
     * @param url html内如果有相对路径的资源不传入url访问不了
     * @param js 用来取返回值的js语句, 没有就返回整个源代码
     * @return 返回js获取的内容
     */
    fun webView(html: String?, url: String?, js: String?): String? = webView(html, url, js, false)
    fun webView(html: String?, url: String?, js: String?, cacheFirst: Boolean): String? =
        io.legado.app.help.source.BrowserBridge.execute(getSource(), html, url, js, cacheFirst = cacheFirst)
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? =
        webViewGetSource(html, url, js, sourceRegex, false, 0)
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean): String? =
        webViewGetSource(html, url, js, sourceRegex, cacheFirst, 0)
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean, delayTime: Long): String? =
        io.legado.app.help.source.BrowserBridge.execute(getSource(), html, url, js, sourceRegex = sourceRegex, cacheFirst = cacheFirst, delayTime = delayTime)
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String? =
        webViewGetOverrideUrl(html, url, js, overrideUrlRegex, false, 0)
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean): String? =
        webViewGetOverrideUrl(html, url, js, overrideUrlRegex, cacheFirst, 0)
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean, delayTime: Long): String? =
        io.legado.app.help.source.BrowserBridge.execute(getSource(), html, url, js, overrideUrlRegex = overrideUrlRegex, cacheFirst = cacheFirst, delayTime = delayTime)

    /**
     * 可从网络，本地文件(阅读私有缓存目录和书籍保存位置支持相对路径)导入JavaScript脚本
     */
    fun importScript(path: String): String {
        val result = when {
            path.startsWith("http") -> cacheFile(path) ?: ""
            else -> readTxtFile(path)
        }
        if (result.isBlank()) throw NoStackTraceException("$path 内容获取失败或者为空")
        return result
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     */
    fun cacheFile(urlStr: String): String? {
        return cacheFile(urlStr, 0)
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param urlStr 网络文件的链接
     * @param saveTime 缓存时间，单位：秒
     * @return 返回缓存后的文件内容
     */
    fun cacheFile(urlStr: String, saveTime: Int = 0): String? {
        val key = md5Encode16(urlStr)
        val cache = getSource()?.getScriptCache()?.getFile(key) ?: if (getSource() == null) CacheManager.getFile(key) else null
        if (cache.isNullOrBlank()) {
            log("首次下载 $urlStr")
            val value = ajax(urlStr) ?: return null
            if (getSource() != null) getSource()!!.getScriptCache().putFile(key, value, saveTime)
            else CacheManager.putFile(key, value, saveTime)
            return value
        }
        return cache
    }

    /**
     *js实现读取cookie
     */
    fun getCookie(tag: String): String = getCookie(tag, null)

    fun getCookie(tag: String, key: String?): String {
        val cookie = CookieStore.forSource(getSource()).getCookie(tag)
        val cookieMap = CookieStore.cookieToMap(cookie)
        return if (key != null) {
            cookieMap[key] ?: ""
        } else {
            cookie
        }
    }

    /**
     * 下载原始文件字节，保存到当前书源的私有缓存。
     */
    fun downloadFile(url: String): String {
        val analyzeUrl = AnalyzeUrl(url, source = getSource())
        val suffix = analyzeUrl.url.substringAfterLast('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").takeIf { it.length in 1..5 && it.matches(Regex("[a-zA-Z0-9]+")) } ?: "ext"
        val path = "${md5Encode16(url)}.${analyzeUrl.type ?: suffix}"
        val file = getFile(path)
        file.delete()
        try {
            val bytes = analyzeUrl.getByteArray()
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        return path
    }

    /**
     * 实现16进制字符串转文件
     * @param content 需要转成文件的16进制字符串
     * @param url 通过url里的参数来判断文件类型
     * @return 相对路径
     */
    fun downloadFile(content: String, url: String): String {
        val type = AnalyzeUrl(url, source = getSource()).type ?: return ""
        if (getSource() != null) {
            val path = "${md5Encode16(url)}.$type"
            val file = getFile(path)
            file.parentFile.mkdirs()
            file.writeBytes(StringUtils.hexStringToByte(content))
            return path
        }
        val zipPath = FileUtils.getPath(
            FileUtils.createFolderIfNotExist(FileUtils.getCachePath()),
            "${MD5Utils.md5Encode16(url)}.${type}"
        )
        FileUtils.deleteFile(zipPath)
        val zipFile = FileUtils.createFileIfNotExist(zipPath)
        StringUtils.hexStringToByte(content).let {
            if (it.isNotEmpty()) {
                zipFile.writeBytes(it)
            }
        }
        return zipPath.substring(FileUtils.getCachePath().length)
    }

    /**
     * js实现重定向拦截,网络访问get
     */
    fun get(urlStr: String, headers: Map<String, String>): Connection.Response = get(urlStr, headers, null)
    fun get(urlStr: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return executeSourceRequest(Jsoup.connect(urlStr)
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
            .ignoreContentType(true)
            .followRedirects(false)
            .timeout(timeout ?: 30000)
            .method(Connection.Method.GET), headers)
    }

    /**
     * 网络访问post
     */
    fun post(urlStr: String, body: String, headers: Map<String, String>): Connection.Response = post(urlStr, body, headers, null)
    fun post(urlStr: String, body: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return executeSourceRequest(Jsoup.connect(urlStr)
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
            .ignoreContentType(true)
            .followRedirects(false)
            .timeout(timeout ?: 30000)
            .requestBody(body)
            .method(Connection.Method.POST), headers)
    }

    fun head(urlStr: String, headers: Map<String, String>): Connection.Response = head(urlStr, headers, null)
    fun head(urlStr: String, headers: Map<String, String>, timeout: Int?): Connection.Response =
        executeSourceRequest(Jsoup.connect(urlStr).sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
            .ignoreContentType(true).followRedirects(false).timeout(timeout ?: 30000).method(Connection.Method.HEAD), headers)

    fun strToBytes(str: String): ByteArray = strToBytes(str, "UTF-8")
    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(Charset.forName(charset))
    fun bytesToStr(bytes: ByteArray): String = bytesToStr(bytes, "UTF-8")
    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, Charset.forName(charset))
    fun hexDecodeToByteArray(hex: String): ByteArray = cn.hutool.core.util.HexUtil.decodeHex(hex)
    fun hexDecodeToString(hex: String): String = cn.hutool.core.util.HexUtil.decodeHexStr(hex)
    fun hexEncodeToString(utf8: String): String = cn.hutool.core.util.HexUtil.encodeHexStr(utf8)
    fun byteArrayToHex(bytes: ByteArray): String = StringUtils.byteToHexString(bytes)

    private fun executeSourceRequest(connection: Connection, headers: Map<String, String>): Connection.Response {
        val source = getSource()
        val store = CookieStore.forSource(source)
        val url = connection.request().url().toString()
        connection.headers(headers.filterKeys { !it.equals("Cookie", true) })
        val cookie = store.cookieToMap(store.getCookie(url)).apply {
            headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value?.let { putAll(store.cookieToMap(it)) }
        }
        store.mapToCookie(cookie)?.takeIf { it.isNotBlank() }?.let { connection.header("Cookie", it) }
        val response = connection.execute()
        if (source?.enabledCookieJar != false) {
            val responseUrl = response.url().toString().toHttpUrl()
            val received = response.multiHeaders().entries.filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }.mapNotNull { Cookie.parse(responseUrl, it) }
            store.saveFromResponse(responseUrl, received)
        }
        return response
    }

    /**
     * js实现解码,不能删
     */
    fun base64Decode(str: String?): String = cn.hutool.core.codec.Base64.decodeStr(str)

    fun base64Decode(str: String?, charset: String): String =
        cn.hutool.core.codec.Base64.decodeStr(str, Charset.forName(charset))

    fun base64Decode(str: String, flags: Int): String {
        return EncoderUtils.base64Decode(str, flags)
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return Base64.decode(str, Base64.DEFAULT)
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return Base64.decode(str, flags)
    }

    fun base64Encode(str: String): String? {
        return EncoderUtils.base64Encode(str, Base64.NO_WRAP)
    }

    fun base64Encode(str: String, flags: Int): String? {
        return EncoderUtils.base64Encode(str, flags)
    }

    fun md5Encode(str: String): String {
        return MD5Utils.md5Encode(str)
    }

    fun md5Encode16(str: String): String {
        return MD5Utils.md5Encode16(str)
    }

    /**
     * 格式化时间
     */
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        val utc = SimpleTimeZone(sh, "UTC")
        return SimpleDateFormat(format, Locale.getDefault()).run {
            timeZone = utc
            format(Date(time))
        }
    }

    /**
     * 时间格式化
     */
    fun timeFormat(time: Long): String {
        return dateFormat.format(Date(time))
    }

    /**
     * utf8编码转gbk编码
     */
    fun utf8ToGbk(str: String): String {
        val utf8 = String(str.toByteArray(charset("UTF-8")))
        val unicode = String(utf8.toByteArray(), charset("UTF-8"))
        return String(unicode.toByteArray(charset("GBK")))
    }

    fun encodeURI(str: String): String {
        return try {
            URLEncoder.encode(str, "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }

    fun encodeURI(str: String, enc: String): String {
        return try {
            URLEncoder.encode(str, enc)
        } catch (e: Exception) {
            ""
        }
    }

    fun htmlFormat(str: String): String {
        return HtmlFormatter.formatKeepImg(str)
    }

    fun t2s(text: String): String = ChineseConversion.t2s(text)
    fun s2t(text: String): String = ChineseConversion.s2t(text)
    fun getWebViewUA(): String = io.legado.app.constant.AppConst.userAgent

    fun toURL(urlStr: String): SourceJsURL = SourceJsURL(urlStr)
    fun toURL(url: String, baseUrl: String?): SourceJsURL = SourceJsURL(url, baseUrl)

    fun toNumChapter(s: String?): String? {
        if (s == null) return null
        val matcher = java.util.regex.Pattern.compile("(第)(.+?)(章)").matcher(s)
        return if (matcher.find()) "${matcher.group(1)}${StringUtils.stringToInt(matcher.group(2))}${matcher.group(3)}" else s
    }

    // The server reads saved settings. Browser-only preferences must be synchronized first.
    fun getReadBookConfig(): String = GSON.toJson(getReadBookConfigMap())
    fun getReadBookConfigMap(): Map<String, Any?> = SourceSavedConfig.reading(getSource())
    fun getThemeMode(): String = SourceSavedConfig.themeMode(getSource())
    fun getThemeConfig(): String = GSON.toJson(getThemeConfigMap())
    fun getThemeConfigMap(): Map<String, Any?> = SourceSavedConfig.theme(getSource())

    //****************文件操作******************//

    /**
     * 获取本地文件
     * @param path 相对路径
     * @return File
     */
    fun getFile(path: String): File {
        getSource()?.let { source ->
            return io.legado.app.help.source.SourceStorage.file(File(appCtx.cacheDir), source.getStorageNamespace(), path)
        }
        val cachePath = appCtx.cacheDir
        val aPath: String = if (path.startsWith(File.separator)) {
            cachePath + path
        } else {
            cachePath + File.separator + path
        }
        return File(aPath)
    }

    fun readFile(path: String): ByteArray? {
        val file = getFile(path)
        if (file.exists()) {
            return file.readBytes()
        }
        return null
    }

    fun readTxtFile(path: String): String {
        val file = getFile(path)
        if (file.exists()) {
            val charsetName = EncodingDetect.getEncode(file)
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    fun readTxtFile(path: String, charsetName: String): String {
        val file = getFile(path)
        if (file.exists()) {
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    /**
     * 删除本地文件
     */
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        if (!file.exists()) return true
        FileUtils.delete(file, true)
        return !file.exists()
    }

    /**
     * js实现压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unzipFile(zipPath: String): String {
        if (zipPath.isEmpty()) return ""
        if (getSource() != null) {
            val zipFile = getFile(zipPath)
            val path = "unzipped/${md5Encode16(zipPath)}"
            val folder = getFile(path).canonicalFile
            FileUtils.delete(folder, true)
            folder.mkdirs()
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(folder, entry.name).canonicalFile
                    require(target.toPath().startsWith(folder.toPath()) && target != folder) { "解压文件路径超出书源缓存目录" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            zipFile.delete()
            return path
        }
        val unzipPath = FileUtils.getPath(
            FileUtils.createFolderIfNotExist(FileUtils.getCachePath()),
            FileUtils.getNameExcludeExtension(zipPath)
        )
        FileUtils.deleteFile(unzipPath)
        val zipFile = getFile(zipPath)
        val unzipFolder = FileUtils.createFolderIfNotExist(unzipPath)
        ZipUtils.unzipFile(zipFile, unzipFolder)
        FileUtils.deleteFile(zipFile.absolutePath)
        return unzipPath.substring(FileUtils.getCachePath().length)
    }

    fun un7zFile(path: String): String = unArchiveFile(path)
    fun unrarFile(path: String): String = unArchiveFile(path)
    fun unArchiveFile(path: String): String {
        if (path.isBlank()) return ""
        require(getSource() != null) { "归档解压必须在书源私有缓存内执行" }
        val archive = getFile(path).canonicalFile
        val relative = "unzipped/${md5Encode16(path)}-${UUID.randomUUID()}"
        val target = getFile(relative).canonicalFile
        io.legado.app.model.jsSource.SourceArchive.extract(archive, target)
        return relative
    }

    /**
     * js实现文件夹内所有文件读取
     */
    fun getTxtInFolder(unzipPath: String): String {
        if (unzipPath.isEmpty()) return ""
        val unzipFolder = getFile(unzipPath)
        val contents = StringBuilder()
        unzipFolder.listFiles().let {
            if (it != null) {
                for (f in it) {
                    val charsetName = EncodingDetect.getEncode(f)
                    contents.append(String(f.readBytes(), charset(charsetName)))
                        .append("\n")
                }
                contents.deleteCharAt(contents.length - 1)
            }
        }
        FileUtils.deleteFile(unzipFolder.absolutePath)
        return contents.toString()
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipStringContent(url: String, path: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.startsWith("http://") || url.startsWith("https://")) {
            runBlocking {
                return@runBlocking getProxyClient(source = getSource()).newCall { url(url) }.bytes()
            }
        } else {
            StringUtils.hexStringToByte(url)
        }
        val bos = ByteArrayOutputStream()
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (entry.name.equals(path)) {
                zis.use { it.copyTo(bos) }
                return bos.toByteArray()
            }
            entry = zis.nextEntry
        }
        Debug.log("getZipContent 未发现内容")

        return null
    }

    fun getRarByteArrayContent(url: String, path: String): ByteArray? = archiveContent(url, path)
    fun get7zByteArrayContent(url: String, path: String): ByteArray? = archiveContent(url, path)
    fun getRarStringContent(url: String, path: String): String = archiveText(getRarByteArrayContent(url, path), null)
    fun getRarStringContent(url: String, path: String, charsetName: String): String = archiveText(getRarByteArrayContent(url, path), charsetName)
    fun get7zStringContent(url: String, path: String): String = archiveText(get7zByteArrayContent(url, path), null)
    fun get7zStringContent(url: String, path: String, charsetName: String): String = archiveText(get7zByteArrayContent(url, path), charsetName)

    private fun archiveText(bytes: ByteArray?, charsetName: String?): String {
        if (bytes == null) return ""
        return String(bytes, Charset.forName(charsetName ?: EncodingDetect.getEncode(bytes)))
    }

    private fun archiveContent(url: String, path: String): ByteArray? {
        require(getSource() != null) { "归档读取必须在书源私有缓存内执行" }
        val bytes = if (url.isAbsUrl()) AnalyzeUrl(url, source = getSource()).getByteArray() else hexDecodeToByteArray(url)
        val relative = "archive-content/${UUID.randomUUID()}"
        val archive = getFile("$relative/input.archive")
        val output = getFile("$relative/output")
        try {
            archive.parentFile.mkdirs()
            archive.writeBytes(bytes)
            io.legado.app.model.jsSource.SourceArchive.extract(archive, output)
            val entry = File(output, path).canonicalFile
            require(entry.toPath().startsWith(output.canonicalFile.toPath()) && entry != output.canonicalFile) { "归档条目路径越界" }
            return entry.takeIf { it.isFile }?.readBytes()
        } finally { archive.parentFile.deleteRecursively() }
    }

    //******************文件操作************************//

    /**
     * 解析字体,返回字体解析类
     */
    fun queryBase64TTF(data: String?): QueryTTF? = queryTTF(data)

    /**
     * 返回字体解析类
     * @param str 支持url,本地文件,base64,自动判断,自动缓存
     */
    fun queryTTF(data: Any?): QueryTTF? = queryTTF(data, true)
    fun queryTTF(data: Any?, useCache: Boolean): QueryTTF? = FontSourceBridge.query(this, data, useCache)

    /**
     * @param text 包含错误字体的内容
     * @param font1 错误的字体
     * @param font2 正确的字体
     */
    fun replaceFont(
        text: String,
        font1: QueryTTF?,
        font2: QueryTTF?
    ): String = replaceFont(text, font1, font2, false)
    fun replaceFont(text: String, font1: QueryTTF?, font2: QueryTTF?, filter: Boolean): String =
        FontSourceBridge.replace(text, font1, font2, filter)

    /**
     * 弹窗提示
     */
    fun toast(msg: Any?) {
        Debug.log("toast: " + msg.toString())
    }

    /**
     * 弹窗提示 停留时间较长
     */
    fun longToast(msg: Any?) {
        Debug.log("longToast: " + msg.toString())
    }

    /**
     * 输出调试日志
     */
    fun log(msg: Any?): Any? {
        Debug.log(msg.toString())
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }

    /**
     * 生成UUID
     */
    fun randomUUID(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * AES 解码为 ByteArray
     * @param str 传入的AES加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    fun aesDecodeToByteArray(
        str: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return createSymmetricCrypto(transformation, key, iv).decrypt(str)
    }

    /**
     * AES 解码为 String
     * @param str 传入的AES加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */

    fun aesDecodeToString(
        str: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(str)
    }

    /**
     * 已经base64的AES 解码为 ByteArray
     * @param str 传入的AES Base64加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */

    fun aesBase64DecodeToByteArray(
        str: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return createSymmetricCrypto(transformation, key, iv).decrypt(str)
    }

    /**
     * 已经base64的AES 解码为 String
     * @param str 传入的AES Base64加密的数据
     * @param key AES 解密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */

    fun aesBase64DecodeToString(
        str: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(str)
    }

    /**
     * 加密aes为ByteArray
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    fun aesEncodeToByteArray(
        data: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return createSymmetricCrypto(transformation, key, iv).encrypt(data)
    }

    /**
     * 加密aes为String
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    fun aesEncodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        // FU JsEncodeUtils.aesEncodeToString decrypts despite its legacy name.
        return createSymmetricCrypto(transformation, key, iv).decryptStr(data)
    }

    /**
     * 加密aes后Base64化的ByteArray
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    fun aesEncodeToBase64ByteArray(
        data: String, key: String, transformation: String, iv: String
    ): ByteArray? {
        return createSymmetricCrypto(transformation, key, iv).encryptBase64(data).toByteArray()
    }

    /**
     * 加密aes后Base64化的String
     * @param data 传入的原始数据
     * @param key AES加密的key
     * @param transformation AES加密的方式
     * @param iv ECB模式的偏移向量
     */
    fun aesEncodeToBase64String(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
    }

    fun androidId(): String {
        return ""
    }

    fun createSymmetricCrypto(transformation: String, key: ByteArray?, iv: ByteArray?): cn.hutool.crypto.symmetric.SymmetricCrypto =
        SourceSymmetricCrypto(transformation, key).apply { if (iv != null && iv.isNotEmpty()) setIv(iv) }
    fun createSymmetricCrypto(transformation: String, key: ByteArray): cn.hutool.crypto.symmetric.SymmetricCrypto =
        createSymmetricCrypto(transformation, key, null)
    fun createSymmetricCrypto(transformation: String, key: String): cn.hutool.crypto.symmetric.SymmetricCrypto =
        createSymmetricCrypto(transformation, key.toByteArray(Charsets.UTF_8), null)
    fun createSymmetricCrypto(transformation: String, key: String, iv: String?): cn.hutool.crypto.symmetric.SymmetricCrypto =
        createSymmetricCrypto(transformation, key.toByteArray(Charsets.UTF_8), iv?.toByteArray(Charsets.UTF_8))
    fun createAsymmetricCrypto(transformation: String): SourceAsymmetricCrypto = SourceAsymmetricCrypto(transformation)
    fun createSign(algorithm: String): SourceSign = SourceSign(algorithm)

    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String =
        cn.hutool.crypto.digest.HMac(algorithm, key.toByteArray(Charsets.UTF_8)).digestHex(data)
    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String =
        Base64.encodeToString(cn.hutool.crypto.digest.HMac(algorithm, key.toByteArray(Charsets.UTF_8)).digest(data), Base64.NO_WRAP)

    /**
     * AES解密，算法参数经过Base64加密
     *
     * @param data 加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 解密后的字符串
     */
    fun aesDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto(
            "AES/${mode}/${padding}",
            Base64.decode(key, Base64.NO_WRAP),
            Base64.decode(iv, Base64.NO_WRAP)
        ).decryptStr(data)
    }

    /**
     * 3DES解密
     *
     * @param data 加密的字符串
     * @param key 密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv 加盐
     * @return 解密后的字符串
     */
    fun tripleDESDecodeStr(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto("DESede/${mode}/${padding}", key, iv).decryptStr(data)
    }

    /**
     * 3DES解密，算法参数经过Base64加密
     *
     * @param data 加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 解密后的字符串
     */
    fun tripleDESDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        // FU JsEncodeUtils decodes only the key; its IV remains the original UTF8 string.
        return createSymmetricCrypto(
            "DESede/${mode}/${padding}",
            Base64.decode(key, Base64.NO_WRAP),
            iv.encodeToByteArray()
        ).decryptStr(data)
    }

    /**
     * AES加密并转为Base64，算法参数经过Base64加密
     *
     * @param data 被加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 加密后的Base64
     */
    fun aesEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        // FU JsEncodeUtils uses raw UTF8 key and IV here, unlike its paired decoder.
        return createSymmetricCrypto("AES/${mode}/${padding}", key, iv).encryptBase64(data)
    }
    /////DES
    fun desDecodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(data)
    }

    fun desBase64DecodeToString(
       data: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(data)
    }

    fun desEncodeToString(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return String(createSymmetricCrypto(transformation, key, iv).encrypt(data))
    }

    fun desEncodeToBase64String(
        data: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
    }
    /**
     * 3DES加密并转为Base64
     *
     * @param data 被加密的字符串
     * @param key 密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv 加盐
     * @return 加密后的Base64
     */
    fun tripleDESEncodeBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto("DESede/${mode}/${padding}", key, iv).encryptBase64(data)
    }

    /**
     * 3DES加密并转为Base64，算法参数经过Base64加密
     *
     * @param data 被加密的字符串
     * @param key Base64后的密钥
     * @param mode 模式
     * @param padding 补码方式
     * @param iv Base64后的加盐
     * @return 加密后的Base64
     */
    fun tripleDESEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        // Preserve FU JsEncodeUtils' raw UTF8 IV, including in the Base64-args overload.
        return createSymmetricCrypto(
            "DESede/${mode}/${padding}",
            Base64.decode(key, Base64.NO_WRAP),
            iv.encodeToByteArray()
        ).encryptBase64(data)
    }

    /**
     * 生成摘要，并转为16进制字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return 16进制字符串
     */
    fun digestHex(
        data: String,
        algorithm: String,
    ): String? {
        return DigestUtil.digester(algorithm).digestHex(data)
    }

    /**
     * 生成摘要，并转为Base64字符串
     *
     * @param data 被摘要数据
     * @param algorithm 签名算法
     * @return Base64字符串
     */
    fun digestBase64Str(
        data: String,
        algorithm: String,
    ): String? {
        return Base64.encodeToString(DigestUtil.digester(algorithm).digest(data), Base64.NO_WRAP)
    }

}

/** FU's URL helper keeps raw parameter names, decodes values, and lets the last duplicate win. */
class SourceJsURL(url: String, baseUrl: String? = null) {
    val searchParams: Map<String, String>?
    val host: String
    val origin: String
    val pathname: String

    init {
        val parsed = if (baseUrl.isNullOrEmpty()) URL(url) else URL(URL(baseUrl), url)
        host = parsed.host
        origin = "${parsed.protocol}://$host" + if (parsed.port > 0) ":${parsed.port}" else ""
        pathname = parsed.path
        searchParams = parsed.query?.let { query ->
            hashMapOf<String, String>().apply {
                query.split('&').forEach { parameter ->
                    val parts = parameter.split('=', limit = 2)
                    if (parts.size == 2) put(parts[0], URLDecoder.decode(parts[1], "UTF-8"))
                }
            }
        }
    }
}

/** Keep FU's distinction between hexadecimal and Base64 ciphertext. */
class SourceSymmetricCrypto(algorithm: String, key: ByteArray?) : cn.hutool.crypto.symmetric.SymmetricCrypto(
    algorithm, cn.hutool.crypto.KeyUtil.generateKey(algorithm.substringBefore('/'), key)
) {
    override fun decrypt(data: String): ByteArray = decrypt(
        if (data.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) cn.hutool.core.util.HexUtil.decodeHex(data)
        else cn.hutool.core.codec.Base64.decode(data)
    )
}

/** FU passes booleans for key selection; Hutool's public interface uses KeyType. */
class SourceAsymmetricCrypto(algorithm: String) : cn.hutool.crypto.asymmetric.AsymmetricCrypto(algorithm) {
    fun setPrivateKey(key: ByteArray): SourceAsymmetricCrypto = apply {
        setPrivateKey(cn.hutool.crypto.KeyUtil.generatePrivateKey(algorithm, key))
    }
    fun setPrivateKey(key: String): SourceAsymmetricCrypto = setPrivateKey(key.toByteArray(Charsets.UTF_8))
    fun setPublicKey(key: ByteArray): SourceAsymmetricCrypto = apply {
        setPublicKey(cn.hutool.crypto.KeyUtil.generatePublicKey(algorithm, key))
    }
    fun setPublicKey(key: String): SourceAsymmetricCrypto = setPublicKey(key.toByteArray(Charsets.UTF_8))
    private fun keyType(usePublicKey: Boolean?) = if (usePublicKey == true) cn.hutool.crypto.asymmetric.KeyType.PublicKey else cn.hutool.crypto.asymmetric.KeyType.PrivateKey
    @JvmOverloads fun decrypt(data: Any, usePublicKey: Boolean? = true): ByteArray = when (data) {
        is ByteArray -> decrypt(data, keyType(usePublicKey))
        is String -> decrypt(data, keyType(usePublicKey))
        is java.io.InputStream -> decrypt(data, keyType(usePublicKey))
        else -> throw IllegalArgumentException("Unexpected input type")
    }
    @JvmOverloads fun decryptStr(data: Any, usePublicKey: Boolean? = true): String =
        String(decrypt(data, usePublicKey), Charsets.UTF_8)
    @JvmOverloads fun encrypt(data: Any, usePublicKey: Boolean? = true): ByteArray = when (data) {
        is ByteArray -> encrypt(data, keyType(usePublicKey))
        is String -> encrypt(data, keyType(usePublicKey))
        is java.io.InputStream -> encrypt(data, keyType(usePublicKey))
        else -> throw IllegalArgumentException("Unexpected input type")
    }
    @JvmOverloads fun encryptHex(data: Any, usePublicKey: Boolean? = true): String =
        cn.hutool.core.util.HexUtil.encodeHexStr(encrypt(data, usePublicKey))
    @JvmOverloads fun encryptBase64(data: Any, usePublicKey: Boolean? = true): String =
        Base64.encodeToString(encrypt(data, usePublicKey), Base64.NO_WRAP)
}

class SourceSign(algorithm: String) : cn.hutool.crypto.asymmetric.Sign(algorithm) {
    fun setPrivateKey(key: ByteArray): SourceSign = apply {
        setPrivateKey(cn.hutool.crypto.KeyUtil.generatePrivateKey(algorithm, key))
    }
    fun setPrivateKey(key: String): SourceSign = setPrivateKey(key.toByteArray(Charsets.UTF_8))
    fun setPublicKey(key: ByteArray): SourceSign = apply {
        setPublicKey(cn.hutool.crypto.KeyUtil.generatePublicKey(algorithm, key))
    }
    fun setPublicKey(key: String): SourceSign = setPublicKey(key.toByteArray(Charsets.UTF_8))
}

/** The authenticated source's saved configuration, without exposing arbitrary file access. */
private object SourceSavedConfig {
    private fun file(source: BaseSource?, name: String): com.google.gson.JsonElement {
        val user = source?.getUserNamespace() ?: "default"
        return com.htmake.reader.api.service.UserDataFiles(
            File(com.htmake.reader.utils.getStoragePath(), "data").toPath()
        ).read(user, name)
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectMap(value: com.google.gson.JsonElement?): MutableMap<String, Any?> =
        if (value?.isJsonObject == true) LinkedHashMap(GSON.fromJson(value, Map::class.java) as Map<String, Any?>)
        else linkedMapOf()

    private fun user(source: BaseSource?) = objectMap(file(source, "userConfig.json"))
    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    private fun selected(value: com.google.gson.JsonElement, index: Int = 0): MutableMap<String, Any?> =
        if (value.isJsonArray && value.asJsonArray.size() > 0) {
            objectMap(value.asJsonArray[index.takeIf { it in 0 until value.asJsonArray.size() } ?: 0])
        } else objectMap(value)

    private fun settings(user: Map<String, Any?>): Map<String, Any?> =
        map(user["reactReadingSettings"]).ifEmpty { map(user["config"]) }

    fun reading(source: BaseSource?): Map<String, Any?> {
        val user = user(source)
        val legacy = map(user["config"])
        val share = user["shareLayout"] == true || legacy["shareLayout"] == true
        val index = ((user["readStyleSelect"] ?: legacy["readStyleSelect"]) as? Number)?.toInt() ?: 0
        val result = if (share) selected(file(source, "shareReadConfig.json")) else selected(file(source, "readConfig.json"), index)
        val modern = settings(user)
        if (modern.isEmpty() && result.isNotEmpty()) return result
        val fontSize = (modern["fontSize"] as? Number)?.toInt() ?: (result["textSize"] as? Number)?.toInt() ?: 21
        val theme = modern["theme"]?.toString()
        val backgrounds = mapOf("paper" to "#f4ecd8", "white" to "#ffffff", "night" to "#17191d", "green" to "#dce9d7")
        val appearance = map(user["reactAppearance"])
        val active = (appearance["profiles"] as? List<*>)?.map(::map)?.firstOrNull { it["id"] == appearance["activeProfileId"] }
        result.putIfAbsent("name", active?.get("name") ?: "Web 阅读")
        result["textSize"] = fontSize
        (modern["customFontUrl"] ?: modern["fontFamily"])?.let { result["textFont"] = it }
        (modern["fontWeight"] as? Number)?.toInt()?.let { result["textBold"] = if (it >= 600) 1 else if (it <= 300) 2 else 0 }
        modern["letterSpacing"]?.let { result["letterSpacing"] = it }
        (modern["lineHeight"] as? Number)?.toDouble()?.let { result["lineSpacingExtra"] = kotlin.math.round((it - 1) * fontSize).toInt() }
        (modern["paragraphSpace"] as? Number)?.toDouble()?.let { result["paragraphSpacing"] = kotlin.math.round(it * fontSize).toInt() }
        (modern["textIndent"] as? Number)?.toInt()?.let { result["paragraphIndent"] = "　".repeat(it.coerceIn(0, 4)) }
        (modern["pagePadding"] as? Number)?.toInt()?.let { padding ->
            listOf("paddingTop", "paddingBottom", "paddingLeft", "paddingRight").forEach { result[it] = padding }
        }
        modern["pageMode"]?.let { result["pageAnim"] = if (it == "scroll") 3 else if (modern["pageAnimation"] == "none") 4 else if (modern["pageAnimation"] == "cover") 0 else 1 }
        val image = (modern["backgroundImage"] ?: modern["contentBGImg"])?.toString()?.takeIf { it.isNotBlank() }
        val background = modern["customBackgroundColor"]?.toString()?.takeIf { it.isNotBlank() } ?: backgrounds[theme] ?: result["bgStr"] ?: "#f4ecd8"
        result["bgStr"] = image ?: background
        result["bgType"] = if (image == null) 0 else 2
        (modern["backgroundOpacity"] as? Number)?.toDouble()?.let { result["bgAlpha"] = kotlin.math.round(it * 100).toInt() }
        val color = modern["customTextColor"]?.toString()?.takeIf { it.isNotBlank() } ?: modern["fontColor"] ?: if (theme == "night") "#d9d5ca" else result["textColor"] ?: "#242424"
        result["textColor"] = color
        if (theme == "night") {
            result["bgStrNight"] = result["bgStr"]
            result["bgTypeNight"] = result["bgType"]
            result["textColorNight"] = color
        }
        return result
    }

    fun themeMode(source: BaseSource?): String {
        val user = user(source)
        val value = user["themeMode"] ?: map(user["config"])["themeMode"]
            ?: return if (settings(user)["theme"] == "night") "2" else "1"
        val mode = if (value is Number && value.toDouble() % 1 == 0.0) value.toInt().toString() else value.toString()
        return when (mode) { "0", "1", "2", "3" -> mode; "4", "5", "6" -> "1"; else -> "0" }
    }

    fun theme(source: BaseSource?): Map<String, Any?> {
        val user = user(source)
        val modern = settings(user)
        val night = when (themeMode(source)) { "2" -> true; "1", "3" -> false; else -> modern["theme"] == "night" }
        val themes = file(source, "themeConfig.json")
        val saved = if (themes.isJsonArray) themes.asJsonArray.firstOrNull {
            it.isJsonObject && it.asJsonObject["isNightTheme"]?.asBoolean == night
        } else themes
        val result = objectMap(saved)
        if (result.isNotEmpty() && modern.isEmpty()) return result
        val reading = reading(source)
        result.putIfAbsent("themeName", reading["name"] ?: "Web 阅读")
        result["isNightTheme"] = night
        result.putIfAbsent("primaryColor", if (night) "#a6c8ff" else "#315f99")
        result.putIfAbsent("accentColor", result["primaryColor"])
        result["backgroundColor"] = modern["customBackgroundColor"]?.toString()?.takeIf { it.isNotBlank() }
            ?: if (reading["bgType"] == 0) reading["bgStr"] else if (night) "#17191d" else "#f4ecd8"
        result.putIfAbsent("bottomBackground", result["backgroundColor"])
        result.putIfAbsent("transparentNavBar", false)
        result["backgroundImgPath"] = modern["backgroundImage"] ?: result["backgroundImgPath"]
        result.putIfAbsent("backgroundImgBlur", 0)
        return result
    }
}
