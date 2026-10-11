@file:Suppress("unused")
package io.legado.app.help.http

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.source.SourceCache
import io.legado.app.help.http.api.CookieManager
import io.legado.app.utils.GSON
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/** Legacy scripts use the default store; book-source bindings use isolated instances. */
object CookieStore : CookieManager by SourceCookieStore("legacy") {
    private val stores = ConcurrentHashMap<String, SourceCookieStore>()
    fun forSource(source: BaseSource?): SourceCookieStore = stores.computeIfAbsent(
        source?.getStorageNamespace() ?: "legacy"
    ) { SourceCookieStore(it) }
    fun clear() { stores.values.forEach { it.clear() }; stores.clear() }
    fun invalidate() { stores.clear() }
}

class SourceCookieStore(private val namespace: String) : CookieManager, CookieJar {
    private val storage = SourceCache(namespace)
    private val storageKey = "cookies"
    private var loaded = false
    private val cookies = mutableListOf<Cookie>()

    private fun load() {
        if (loaded) return
        loaded = true
        storage.get(storageKey)?.let { json ->
            runCatching { GSON.fromJson(json, Array<Cookie>::class.java) }.getOrNull()?.let { cookies.addAll(it) }
        }
    }
    private fun persist() {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        storage.put(storageKey, GSON.toJson(cookies.filter { it.persistent }))
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        load()
        cookies.forEach { cookie ->
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        persist()
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        load()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
    @Synchronized override fun setCookie(url: String, cookie: String?) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Cookie 地址必须是 HTTP 地址")
        load()
        cookies.removeAll { it.matches(httpUrl) }
        val values = cookieToMap(cookie.orEmpty()).map { (name, value) ->
            Cookie.Builder().name(name).value(value).hostOnlyDomain(httpUrl.host).path("/")
                .expiresAt(Long.MAX_VALUE).apply { if (httpUrl.isHttps) secure() }.build()
        }
        saveFromResponse(httpUrl, values)
    }
    @Synchronized override fun replaceCookie(url: String, cookie: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Cookie 地址必须是 HTTP 地址")
        val merged = cookieToMap(getCookie(url)).apply { putAll(cookieToMap(cookie)) }
        setCookie(httpUrl.toString(), mapToCookie(merged))
    }
    override fun getCookie(url: String): String = url.toHttpUrlOrNull()?.let {
        loadForRequest(it).joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }.orEmpty()
    @Synchronized override fun removeCookie(url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        load()
        cookies.removeAll { it.domain == httpUrl.host || it.matches(httpUrl) }
        persist()
    }
    override fun cookieToMap(cookie: String): MutableMap<String, String> = linkedMapOf<String, String>().apply {
        cookie.split(';').forEach { segment ->
            val parts = segment.trim().split('=', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) put(parts[0], parts[1])
        }
    }
    override fun mapToCookie(cookieMap: Map<String, String>?): String? = cookieMap
        ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
    @Synchronized fun clear() { loaded = true; cookies.clear(); storage.delete(storageKey) }
}
