package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.GSON
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import io.legado.app.help.http.StrResponse
import io.legado.app.model.jsSource.SourceScriptRuntime

/** Requests come only from stored source scripts; the UI consumes their opaque identifiers. */
object SourceInteractions {
    private const val PENDING = "source-interactions:pending"
    private const val TTL = 300
    private val lock = Any()
    private val waiters = ConcurrentHashMap<String, CompletableFuture<StrResponse>>()
    private fun waiterKey(source: BaseSource, id: String) = "${source.getStorageNamespace()}:$id"

    data class Interaction(
        val id: String,
        val type: String,
        val url: String,
        val title: String,
        val html: String? = null,
        val isFloat: Boolean = false,
        val waiting: Boolean = false,
        val refetchAfterSuccess: Boolean = false
    ) {
        fun publicFields(): Map<String, Any?> = mapOf("id" to id, "type" to type, "url" to url,
            "title" to title, "isFloat" to isFloat, "waiting" to waiting)
    }

    fun request(source: BaseSource?, type: String, url: String, title: String, html: String? = null, isFloat: Boolean = false,
                waiting: Boolean = false, refetchAfterSuccess: Boolean = false): Interaction {
        require(source != null) { "书源交互缺少书源" }
        require(type == "browser" || type == "video") { "书源交互类型无效" }
        require(html == null || html.length <= 2 * 1024 * 1024) { "浏览器交互页面过大" }
        val address = requireNotNull(source.getKey().toHttpUrl().resolve(url)) { "书源交互链接无效" }
        require(address.scheme == "http" || address.scheme == "https") { "书源交互仅支持 HTTP 链接" }
        val interaction = Interaction(UUID.randomUUID().toString(), type, address.toString(), title, html, isFloat, waiting, refetchAfterSuccess)
        synchronized(lock) {
            val cache = source.getScriptCache()
            val pending = pending(source).takeLast(31) + interaction.id
            cache.put("source-interaction:${interaction.id}", GSON.toJson(interaction), TTL)
            cache.put(PENDING, GSON.toJson(pending), TTL)
            if (waiting) waiters[waiterKey(source, interaction.id)] = CompletableFuture()
        }
        return interaction
    }

    fun await(source: BaseSource, interaction: Interaction, timeoutMillis: Long = 120_000): StrResponse {
        val key = waiterKey(source, interaction.id)
        val future = waiters[key] ?: error("网页验证已过期")
        try {
            return SourceScriptRuntime.pauseDeadline { future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        } catch (error: TimeoutException) {
            throw IllegalStateException("等待网页验证超时，请重新操作", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("网页验证已取消", error)
        } finally {
            waiters.remove(key, future)
            source.getScriptCache().delete("source-interaction:${interaction.id}")
            synchronized(lock) { source.getScriptCache().put(PENDING, GSON.toJson(pending(source).filterNot { it == interaction.id }), TTL) }
        }
    }

    fun complete(source: BaseSource, id: String, url: String, body: String?): Boolean =
        waiters[waiterKey(source, id)]?.complete(StrResponse(url, body)) ?: false

    private fun pending(source: BaseSource): List<String> = source.getScriptCache().get(PENDING, true)?.let {
        runCatching { GSON.fromJson(it, Array<String>::class.java).toList() }.getOrNull()
    }.orEmpty()

    fun resolve(source: BaseSource, id: String): Interaction {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "书源交互标识无效" }
        val encoded = source.getScriptCache().get("source-interaction:$id", true) ?: throw IllegalArgumentException("书源交互已过期，请重试书源操作")
        return GSON.fromJson(encoded, Interaction::class.java)
    }

    fun drain(source: BaseSource): List<Map<String, Any?>> = synchronized(lock) {
        val actions = pending(source).mapNotNull { id ->
            runCatching { resolve(source, id).publicFields() + ("bookSourceUrl" to source.getKey()) }.getOrNull()
        }
        source.getScriptCache().delete(PENDING)
        actions
    }
}
