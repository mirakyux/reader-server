package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import com.htmake.reader.utils.asJsonArray
import io.legado.app.data.entities.ReplaceRule
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.coroutines.CoroutineContext

class ReplaceRuleController(coroutineContext: CoroutineContext) : BaseController(coroutineContext) {
    companion object { private val sequence = AtomicLong(System.currentTimeMillis()) }
    private fun load(namespace: String) = asJsonArray(getUserStorage(namespace, "replaceRule")) ?: JsonArray()
    private fun identity(saved: JsonObject, incoming: JsonObject): Boolean =
        if (incoming.containsKey("id")) saved.getLong("id") == incoming.getLong("id")
        else saved.getString("name") == incoming.getString("name")

    suspend fun getReplaceRules(context: RoutingContext): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return ReturnData().setData(load(getUserNameSpace(context)).list)
    }
    suspend fun saveReplaceRule(context: RoutingContext) = save(context, JsonArray().add(context.body().asJsonObject()))
    suspend fun saveReplaceRules(context: RoutingContext) = save(context, context.body().asJsonArray())
    private suspend fun save(context: RoutingContext, incoming: JsonArray): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val namespace = getUserNameSpace(context)
        val saved = load(namespace)
        for (index in 0 until incoming.size()) {
            val json = incoming.getJsonObject(index)
            val rule = json.mapTo(ReplaceRule::class.java)
            require(rule.name.isNotBlank() && rule.pattern.isNotEmpty()) { "名称和替换规则不能为空" }
            require(rule.timeoutMillisecond in 1..30000) { "规则超时必须在 1 到 30000 毫秒之间" }
            if (rule.isRegex) Pattern.compile(rule.pattern)
            val existing = (0 until saved.size()).firstOrNull { identity(saved.getJsonObject(it), json) }
            if (!json.containsKey("id")) rule.id = existing?.let { saved.getJsonObject(it).getLong("id") } ?: sequence.incrementAndGet()
            if (existing == null) saved.add(JsonObject.mapFrom(rule)) else saved.set(existing, JsonObject.mapFrom(rule))
        }
        saveUserStorage(namespace, "replaceRule", saved)
        return ReturnData().setData("")
    }
    suspend fun deleteReplaceRule(context: RoutingContext) = delete(context, JsonArray().add(context.body().asJsonObject()))
    suspend fun deleteReplaceRules(context: RoutingContext) = delete(context, context.body().asJsonArray())
    private suspend fun delete(context: RoutingContext, incoming: JsonArray): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val namespace = getUserNameSpace(context)
        val saved = load(namespace)
        for (index in 0 until incoming.size()) {
            val json = incoming.getJsonObject(index)
            (0 until saved.size()).firstOrNull { identity(saved.getJsonObject(it), json) }?.let { saved.remove(it) }
        }
        saveUserStorage(namespace, "replaceRule", saved)
        return ReturnData().setData("")
    }
}
