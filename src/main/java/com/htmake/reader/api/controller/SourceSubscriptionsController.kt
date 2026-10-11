package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import io.legado.app.data.entities.BookSource
import io.legado.app.help.SourceAnalyzer
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.SourceActions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** FU sourceSub.json records are retained with their unknown fields for backup interchange. */
class SourceSubscriptionsController(context: CoroutineContext, private val sources: BookSourceController) : BaseController(context) {
    private val activeUsers = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(user: String) = locks.computeIfAbsent(user) { Mutex() }
    init {
        java.io.File(com.htmake.reader.utils.getStoragePath(), "data").listFiles()?.filter { it.isDirectory && java.io.File(it, "sourceSub.json").isFile }
            ?.forEach { activeUsers.add(it.name) }
        launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                activeUsers.toList().forEach { user ->
                    runCatching { records(user) }.getOrDefault(emptyList()).filter { it.getBoolean("autoUpdate", false) &&
                        System.currentTimeMillis() - maxOf(it.getLong("update", 0), it.getLong("lastAttempt", 0)) >= it.getInteger("updateInterval", 1).coerceAtLeast(1) * 3_600_000L
                    }.forEach { record ->
                        lock(user).withLock {
                            try { update(user, record, true) } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { record.put("error", failure.message).put("lastAttempt", System.currentTimeMillis()); saveRecord(user, record) }
                        }
                    }
                }
            }
        }
    }
    private fun records(user: String): List<JsonObject> = getUserStorage(user, "sourceSub")?.let {
        JsonArray(it).mapNotNull { value -> value as? JsonObject }
    }.orEmpty().filter { it.getInteger("type", 0) == 0 }
    private fun saveRecord(user: String, record: JsonObject) {
        val all = getUserStorage(user, "sourceSub")?.let(::JsonArray) ?: JsonArray()
        val updated = all.filterNot { (it as? JsonObject)?.getValue("id").toString() == record.getValue("id").toString() }.toMutableList()
        updated.add(record); saveUserStorage(user, "sourceSub", JsonArray(updated))
    }
    private suspend fun operation(context: RoutingContext, work: suspend (String, JsonObject) -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val user = getUserNameSpace(context); activeUsers.add(user)
        return try {
            val data = withContext(Dispatchers.IO) { lock(user).withLock { work(user, context.body().asJsonObject() ?: JsonObject()) } }
            ReturnData().setData(if (data is JsonObject) data.map else data)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ReturnData().setErrorMsg(failure.message ?: "书源订阅操作失败") }
    }
    suspend fun getBookSourceSubscriptions(context: RoutingContext) = operation(context) { user, _ -> records(user).map { it.map } }
    suspend fun saveBookSourceSubscription(context: RoutingContext) = operation(context) { user, input ->
        val url = input.getString("url")?.trim() ?: error("订阅地址不能为空")
        require(URI(url).scheme in setOf("http", "https") && URI(url).host != null) { "订阅地址必须是 HTTP(S) 链接" }
        val record = input.copy().put("url", url).put("type", 0)
            .put("id", input.getValue("id") ?: System.currentTimeMillis()).put("name", input.getString("name") ?: url)
            .put("updateInterval", input.getInteger("updateInterval", 24).coerceIn(1, 8760))
        saveRecord(user, record); record
    }
    suspend fun deleteBookSourceSubscription(context: RoutingContext) = operation(context) { user, input ->
        val id = input.getValue("id")?.toString() ?: error("订阅标识不能为空")
        val all = getUserStorage(user, "sourceSub")?.let(::JsonArray) ?: JsonArray()
        saveUserStorage(user, "sourceSub", JsonArray(all.filterNot { (it as? JsonObject)?.getValue("id")?.toString() == id })); true
    }
    suspend fun updateBookSourceSubscription(context: RoutingContext) = operation(context) { user, input ->
        val record = records(user).firstOrNull { it.getValue("id").toString() == input.getValue("id").toString() }
            ?: error("书源订阅不存在")
        try { update(user, record) } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { record.put("error", failure.message).put("lastAttempt", System.currentTimeMillis()); saveRecord(user, record); throw failure }
    }
    private suspend fun update(user: String, record: JsonObject, automatic: Boolean = false): Map<String, Any?> {
        val stored = sources.getUserBookSourceJson(user) ?: JsonArray()
        val source = record.getString("sourceUrl")?.takeIf(String::isNotBlank)?.let { target ->
            stored.mapNotNull { it as? JsonObject }.firstOrNull { it.getString("bookSourceUrl") == target }
                ?.let { BookSource.fromJson(it.encode(), user).getOrThrow() } ?: error("绑定书源不存在")
        } ?: BookSource(bookSourceUrl = record.getString("url")).apply { runtimeUserNamespace = user }
        val url = record.getString("js")?.takeIf(String::isNotBlank)?.let {
            SourceActions.evaluate(source, it, mapOf("result" to record.getString("url")))?.toString()
        } ?: record.getString("url")
        val response = AnalyzeUrl(url, source = source).getStrResponseAwait()
        require(response.isSuccessful()) { "订阅请求失败：HTTP ${response.code()}" }
        val incoming = SourceAnalyzer.jsonToBookSources(response.body ?: error("订阅内容为空")).getOrThrow()
        require(incoming.isNotEmpty()) { "订阅未返回书源" }
        if (automatic && !record.getBoolean("silentUpdate", false)) {
            record.put("pendingCount", incoming.size).put("lastAttempt", System.currentTimeMillis()).put("error", null)
            saveRecord(user, record)
            return mapOf("count" to incoming.size, "pending" to true, "subscription" to record.map)
        }
        // Preserve local enable/group/order preferences while refreshing rules.
        val merged = (sources.getUserBookSourceJson(user) ?: JsonArray()).mapNotNull { it as? JsonObject }.associateBy { it.getString("bookSourceUrl") }.toMutableMap()
        incoming.forEach { item ->
            val old = merged[item.bookSourceUrl]
            val next = JsonObject.mapFrom(item)
            listOf("enabled", "enabledExplore", "bookSourceGroup", "customOrder").forEach { name -> if (old?.containsKey(name) == true) next.put(name, old.getValue(name)) }
            merged[item.bookSourceUrl] = next
        }
        saveUserStorage(user, "bookSource", JsonArray(merged.values.toList()))
        record.put("update", System.currentTimeMillis()).put("lastAttempt", System.currentTimeMillis()).put("error", null).put("pendingCount", null)
        saveRecord(user, record)
        return mapOf("count" to incoming.size, "subscription" to record.map)
    }
}
