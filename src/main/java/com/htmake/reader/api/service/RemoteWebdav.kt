package com.htmake.reader.api.service

import com.google.gson.JsonObject
import com.htmake.reader.api.service.advanced.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A bounded DAV client. Redirects are disabled so Basic credentials stay at the configured host. */
class RemoteWebdav(private val files: UserDataFiles) {
    data class Entry(val path: String, val name: String, val directory: Boolean, val size: Long, val modified: String, val etag: String = "")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun config(user: String, secrets: Boolean = false): JsonObject {
        val saved = files.read(user, "remoteWebdav.json").takeIf { it.isJsonObject }?.asJsonObject ?: jsonObject("url" to "", "username" to "", "dailyBackup" to false)
        if (!secrets) { saved.addProperty("passwordConfigured", saved.string("password").isNotBlank()); saved.remove("password") }
        return saved
    }
    fun save(user: String, input: JsonObject): JsonObject {
        val previous = config(user, true)
        val url = input.string("url").trim().trimEnd('/')
        if (url.isNotBlank()) validateBase(url)
        val username = input.string("username")
        val password = if (input.has("password")) input.string("password") else previous.string("password")
        require(username.length <= 500 && !username.contains(':') && !username.contains('\n') && !username.contains('\r') && password.length <= 4096 && !password.contains('\n') && !password.contains('\r')) { "WebDAV 凭据格式错误" }
        files.write(user, "remoteWebdav.json", jsonObject("url" to url, "username" to username, "password" to password,
            "dailyBackup" to input.flag("dailyBackup"), "lastBackupDay" to previous.string("lastBackupDay"), "lastBackupTime" to previous.long("lastBackupTime"), "lastError" to previous.string("lastError")))
        return config(user)
    }
    fun status(user: String, day: String? = null, error: String = "") {
        files.update(user, "remoteWebdav.json") { saved -> saved.asJsonObject.also {
            if (day != null) { it.addProperty("lastBackupDay", day); it.addProperty("lastBackupTime", System.currentTimeMillis()) }
            it.addProperty("lastError", error.take(1000))
        } }
    }
    fun test(user: String): List<Entry> { ensureBase(user); return list(user, "") }
    fun ensureBase(user: String) {
        val base = validateBase(config(user, true).string("url"))
        fun create(path: String, depth: Int = 0) {
            require(depth < 30) { "WebDAV 文件夹层级过多" }
            if (path == "/" || path.isEmpty()) return
            val target = URI(base.scheme, base.rawAuthority, path, null, null)
            var response = send(user, "MKCOL", "", targetOverride = target)
            if (response.statusCode() == 409) {
                val parent = path.trimEnd('/').substringBeforeLast('/', "") + "/"
                create(parent, depth + 1)
                response = send(user, "MKCOL", "", targetOverride = target)
            }
            require(response.statusCode() in listOf(200, 201, 204, 405)) { "无法创建远程 WebDAV 文件夹（HTTP ${response.statusCode()}）" }
        }
        create(base.path)
    }
    fun mkdir(user: String, path: String) {
        ensureBase(user)
        var current = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current += "$segment/"
            val response = send(user, "MKCOL", current)
            require(response.statusCode() in listOf(200, 201, 204, 405)) { "无法创建 WebDAV 目录（HTTP ${response.statusCode()}）" }
        }
    }
    fun upload(user: String, path: String, bytes: ByteArray, ifMatch: String? = null, newFile: Boolean = false) {
        require(bytes.size <= 256 * 1024 * 1024) { "同步文件过大" }
        val response = send(user, "PUT", path, bytes, ifMatch?.let { mapOf("If-Match" to it) } ?: if (newFile) mapOf("If-None-Match" to "*") else emptyMap())
        require(response.statusCode() != 412) { "远程进度已发生变化，请重新同步" }
        require(response.statusCode() in 200..299) { "WebDAV 上传失败（HTTP ${response.statusCode()}）" }
    }
    fun download(user: String, path: String): ByteArray {
        val response = send(user, "GET", path)
        require(response.statusCode() in 200..299) { "WebDAV 下载失败（HTTP ${response.statusCode()}）" }
        return response.body()
    }
    fun list(user: String, path: String): List<Entry> {
        val xml = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>".toByteArray()
        val response = send(user, "PROPFIND", path, xml, mapOf("Depth" to "1", "Content-Type" to "application/xml"))
        if (response.statusCode() == 404 && path.isNotBlank()) return emptyList()
        require(response.statusCode() in 200..299) { "WebDAV 列表读取失败（HTTP ${response.statusCode()}）" }
        val base = validateBase(config(user, true).string("url"))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(response.body().inputStream())
        val rows = document.getElementsByTagNameNS("DAV:", "response")
        val result = mutableListOf<Entry>()
        for (index in 0 until rows.length) {
            val row = rows.item(index) as org.w3c.dom.Element
            fun value(name: String) = row.getElementsByTagNameNS("DAV:", name).item(0)?.textContent.orEmpty()
            val href = runCatching { base.resolve(value("href")) }.getOrNull() ?: continue
            if (href.scheme != base.scheme || href.rawAuthority != base.rawAuthority || !href.path.startsWith(base.path)) continue
            val relative = href.path.removePrefix(base.path).trimStart('/')
            if (relative.trimEnd('/') == path.trim('/')) continue
            if (relative.split('/').any { it == ".." || it == "." }) continue
            result.add(Entry(relative, relative.trimEnd('/').substringAfterLast('/'), row.getElementsByTagNameNS("DAV:", "collection").length > 0,
                value("getcontentlength").toLongOrNull() ?: 0, value("getlastmodified"), value("getetag")))
        }
        return result
    }
    private fun send(user: String, method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), targetOverride: URI? = null): HttpResponse<ByteArray> {
        val saved = config(user, true)
        val base = validateBase(saved.string("url"))
        require(path.length <= 4096 && !path.startsWith('/') && !path.contains('\\') && path.split('/').none { it == ".." || it == "." }) { "WebDAV 路径无效" }
        val encoded = URI(null, null, base.path + path, null).rawPath
        val target = targetOverride ?: URI(base.scheme + "://" + base.rawAuthority + encoded)
        require(target.scheme == base.scheme && target.rawAuthority == base.rawAuthority) { "WebDAV 目标主机不一致" }
        val request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(60)).method(method,
            body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (saved.string("username").isNotBlank() || saved.string("password").isNotBlank()) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((saved.string("username") + ":" + saved.string("password")).toByteArray()))
        headers.forEach { (key, value) -> request.header(key, value) }
        // Always bound reads, including failed responses. This is also used for ZIP downloads.
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = response.body().use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size().toLong() + count <= 256L * 1024 * 1024) { "WebDAV 响应过大" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
        return object : HttpResponse<ByteArray> {
            override fun body() = bytes
            override fun statusCode() = response.statusCode()
            override fun request() = response.request()
            override fun previousResponse(): java.util.Optional<HttpResponse<ByteArray>> = java.util.Optional.empty()
            override fun headers() = response.headers()
            override fun sslSession() = response.sslSession()
            override fun uri() = response.uri()
            override fun version() = response.version()
        }
    }
    private fun validateBase(value: String): URI {
        val uri = URI(value.trimEnd('/') + "/")
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && !uri.path.contains("/../")) { "请输入不含凭据或查询参数的 WebDAV HTTP(S) 地址" }
        return uri
    }
}
