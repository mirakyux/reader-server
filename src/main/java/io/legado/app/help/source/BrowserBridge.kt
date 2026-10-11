package io.legado.app.help.source

import com.htmake.reader.config.AppConfig
import com.htmake.reader.utils.SpringContextUtils
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.CookieStore
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import io.legado.app.help.http.StrResponse
import io.legado.app.constant.AppConst
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/** The browser runs in a separate process, with per-source cookies and web storage. */
object BrowserBridge {
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    /** Opening a verification page must not rerun the dynamic header script currently awaiting it. */
    fun bootstrapHeaders(source: BaseSource): Map<String, String> = linkedMapOf(AppConst.UA_NAME to AppConst.userAgent).apply {
        source.header?.trim()?.takeUnless { it.startsWith("@js:", true) || it.startsWith("<js>", true) }?.let {
            GSON.fromJsonObject<Map<String, String>>(it).getOrNull()?.let(::putAll)
        }
        source.getLoginHeaderMap()?.let(::putAll)
    }
    fun call(path: String, input: JsonObject): JsonObject {
        val config = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
        check(config.browserToken.isNotBlank()) { "浏览器服务尚未配置，请使用 dev.ps1 启动或设置 reader.app.browserToken" }
        val request = Request.Builder().url(config.browserUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${config.browserToken}")
            .post(input.encode().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            val result = JsonObject(response.body.string())
            check(response.isSuccessful) { result.getString("error", "浏览器服务请求失败") }
            result
        }
    }
    fun input(source: BaseSource?, url: String, html: String? = null, headers: Map<String, String>? = null): JsonObject {
        val state = source?.getScriptCache()?.get("browserState")?.let { runCatching { JsonObject(it) }.getOrNull() }
            ?.apply { if (source?.enabledCookieJar == false) put("cookies", JsonArray()) }
        val cookies = (if (source?.enabledCookieJar == false) emptyList() else CookieStore.forSource(source).loadForRequest(url.toHttpUrl())).map {
            JsonObject().put("name", it.name).put("value", it.value)
                .put("domain", if (it.hostOnly) it.domain else ".${it.domain}").put("path", it.path)
                .put("secure", it.secure).put("httpOnly", it.httpOnly)
                .apply { if (it.persistent) put("expires", it.expiresAt / 1000.0) }
        }
        return JsonObject().put("url", url).put("html", html).put("headers", headers ?: source?.getHeaderMap(true))
            .put("cookies", JsonArray(cookies)).put("storageState", state)
    }
    fun save(source: BaseSource?, result: JsonObject) {
        val state = result.getJsonObject("storageState") ?: return
        if (source?.enabledCookieJar == false) {
            source.getScriptCache().put("browserState", state.copy().put("cookies", JsonArray()).encode())
            return
        }
        source?.getScriptCache()?.put("browserState", state.encode())
        val url = result.getString("url").toHttpUrl()
        val cookies = state.getJsonArray("cookies", JsonArray()).mapNotNull { entry ->
            runCatching {
                val json = entry as JsonObject
                val domain = json.getString("domain")
                Cookie.Builder().name(json.getString("name")).value(json.getString("value"))
                    .apply { if (domain.startsWith('.')) domain(domain.trimStart('.')) else hostOnlyDomain(domain) }
                    .path(json.getString("path", "/"))
                    .apply { if (json.getBoolean("secure", false)) secure(); if (json.getBoolean("httpOnly", false)) httpOnly()
                        val expiry = json.getDouble("expires", -1.0); if (expiry > 0) expiresAt((expiry * 1000).toLong()) }
                    .build()
            }.getOrNull()
        }
        if (source?.enabledCookieJar != false) CookieStore.forSource(source).saveFromResponse(url, cookies)
    }

    fun clearCookies(source: BaseSource) {
        val state = source.getScriptCache().get("browserState")?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return
        source.getScriptCache().put("browserState", state.copy().put("cookies", JsonArray()).encode())
    }

    fun executeResponse(source: BaseSource?, html: String?, url: String?, js: String?, sourceRegex: String? = null,
        overrideUrlRegex: String? = null, delayTime: Long = 0, headers: Map<String, String>? = null): StrResponse {
        val address = url?.takeIf { it.isNotBlank() } ?: source?.getKey() ?: error("浏览器页面地址不能为空")
        val request = input(source, address, html, headers).put("js", js).put("sourceRegex", sourceRegex)
            .put("overrideUrlRegex", overrideUrlRegex).put("delayTime", delayTime)
        val result = call("/execute", request)
        save(source, result)
        return StrResponse(result.getString("url", address), result.getString("body"))
    }
    fun execute(source: BaseSource?, html: String?, url: String?, js: String?, sourceRegex: String? = null,
                overrideUrlRegex: String? = null, cacheFirst: Boolean = false, delayTime: Long = 0): String? {
        val address = url?.takeIf { it.isNotBlank() } ?: source?.getKey() ?: error("浏览器页面地址不能为空")
        val input = input(source, address, html).put("js", js).put("sourceRegex", sourceRegex)
            .put("overrideUrlRegex", overrideUrlRegex).put("delayTime", delayTime)
        val key = "browser-result:" + io.legado.app.utils.MD5Utils.md5Encode(input.encode())
        if (cacheFirst) source?.getScriptCache()?.get(key, true)?.let { return it }
        val result = call("/execute", input)
        save(source, result)
        return result.getString("body").also { if (cacheFirst && it != null) source?.getScriptCache()?.put(key, it, 300) }
    }
}
