package io.legado.app.model.jsSource

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON

object SourceLogin {
    private val types = setOf("text", "password", "label", "select", "toggle", "button")
    private fun legacyEval(source: BookSource, script: String, values: Map<String, Any?>): Any? {
        val declarations = source.loginUrl?.takeIf { it.startsWith("@js:", true) || it.startsWith("<js>", true) }
            ?.let { source.getLoginJs() }.orEmpty()
        val body = when {
            script.startsWith("@js:", true) -> script.substring(4)
            script.startsWith("<js>", true) && script.endsWith("</js>", true) -> script.substring(4, script.length - 5)
            else -> script
        }
        return SourceActions.evaluate(source, "$declarations\n$body", values)
    }

    fun ui(source: BookSource, state: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val version = if (source.loginUi?.trimStart()?.startsWith("{") == true) 2 else 1
        val rows = if (version == 2) {
            val text = JsSourceEngine(source).call("loginUi", listOf(state)) ?: error("动态登录界面返回空结果")
            val value = JsonParser.parseString(text)
            require(value.isJsonObject && value.asJsonObject.get("rows")?.isJsonArray == true) { "loginUi 必须返回含 rows 的对象" }
            value.asJsonObject.getAsJsonArray("rows")
        } else {
            val raw = source.loginUi?.takeIf { it.isNotBlank() } ?: "[]"
            val value = if (raw.startsWith("@js:", true) || raw.startsWith("<js>", true)) {
                val result = legacyEval(source, raw, mapOf("result" to (source.getLoginInfoMap() ?: emptyMap<String, String>()), "state" to state))
                JsonParser.parseString(if (result is String) result else GSON.toJson(result))
            } else JsonParser.parseString(raw)
            require(value.isJsonArray) { "登录表单必须是数组" }
            value.asJsonArray
        }
        val keys = hashSetOf<String>()
        val normalized = rows.map { element ->
            require(element.isJsonObject) { "登录表单行必须是对象" }
            val row = element.asJsonObject
            val name = row.string("name") ?: error("登录表单行缺少名称")
            val type = row.string("type") ?: "text"
            require(type in types) { "不支持登录组件 $type" }
            val key = row.string("key") ?: name
            if (type in setOf("text", "password", "toggle", "select")) require(keys.add(key)) { "登录字段 key 重复：$key" }
            if (type == "button" && version == 2) require(!row.string("action").isNullOrBlank()) { "动态登录按钮缺少 action" }
            val options = (row.get("options") ?: row.get("chars"))?.takeIf { it.isJsonArray }?.asJsonArray?.filterNot { it.isJsonNull }?.map { option ->
                if (option.isJsonPrimitive) mapOf("label" to option.asString, "value" to option.asString)
                else mapOf("label" to (option.asJsonObject.string("label") ?: option.asJsonObject.string("name").orEmpty()),
                    "value" to option.asJsonObject.string("value").orEmpty())
            }
            if (type == "select") require(!options.isNullOrEmpty()) { "选择组件缺少 options" }
            val label = row.string("viewName")?.let { expression ->
                legacyEval(source, expression, mapOf("result" to (source.getLoginInfoMap() ?: emptyMap<String, String>()), "state" to state))?.toString()
            } ?: name
            mapOf("name" to name, "label" to label, "type" to type, "key" to key,
                "action" to row.string("action"), "placeholder" to (row.string("hint") ?: row.string("placeholder")),
                "value" to if (type == "password") null else (source.getLoginInfoMap()?.get(key) ?: row.string("value") ?: row.string("default") ?: options?.firstOrNull()?.get("value")),
                "options" to options, "countdown" to row.get("countdown")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 3600))
        }
        return mapOf("version" to version, "rows" to normalized, "state" to state)
    }

    fun login(source: BookSource, form: Map<String, Any?>, action: String?, state: Map<String, Any?>): Map<String, Any?> {
        val stored = form.mapValues { (_, value) -> value?.toString().orEmpty() }
        check(source.putLoginInfo(GSON.toJson(stored))) { "保存书源登录信息失败" }
        val dynamic = source.loginUi?.trimStart()?.startsWith("{") == true
        var newState = state
        var errors: Map<String, String> = emptyMap()
        var close = !dynamic && action == null
        var refreshExplore = false
        if (!source.mainJs.isNullOrBlank()) {
            val engine = JsSourceEngine(source)
            if (dynamic) {
                val text = engine.call("loginAction", listOf(action ?: "login", state, form))
                if (!text.isNullOrBlank()) {
                    val value = JsonParser.parseString(text)
                    require(value.isJsonObject) { "loginAction 必须返回对象" }
                    val obj = value.asJsonObject
                    obj.get("state")?.takeIf { !it.isJsonNull }?.let {
                        require(it.isJsonObject) { "loginAction.state 必须是对象" }
                        newState = objectMap(it.asJsonObject)
                    }
                    obj.get("error")?.takeIf { !it.isJsonNull }?.let {
                        require(it.isJsonObject) { "loginAction.error 必须是对象" }
                        errors = it.asJsonObject.entrySet().associate { entry -> entry.key to entry.value.asString }
                    }
                    obj.get("login")?.takeIf { !it.isJsonNull }?.let {
                        require(it.isJsonObject) { "loginAction.login 必须是对象" }
                        check(source.putLoginInfo(it.toString())) { "保存书源登录信息失败" }
                    }
                    close = obj.get("close")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                }
            } else engine.call("login")
        } else {
            require(source.loginUrl?.let { it.startsWith("@js:") || it.startsWith("<js>") } == true) {
                "该书源需要浏览器登录，请登录网站后在 Cookie 管理中导入 Cookie"
            }
            val declarations = source.getLoginJs().orEmpty()
            val code = if (action == null) "$declarations\nif(typeof login === 'function') login();" else {
                val rows = ui(source, state)["rows"] as List<*>
                val row = rows.map { it as Map<*, *> }.firstOrNull { it["action"] == action || it["name"] == action }
                    ?: error("登录控件不存在")
                val saved = row["action"]?.toString() ?: error("登录控件没有动作")
                if (saved.startsWith("http://") || saved.startsWith("https://")) {
                    source.startBrowser(saved, row["label"]?.toString() ?: "书源登录")
                    "true"
                } else "$declarations\n$saved"
            }
            val bridge = LoginBridge(source)
            SourceActions.evaluate(source, code, mapOf("java" to bridge, "result" to stored, "state" to state, "isLongClick" to false))
            refreshExplore = bridge.exploreRefreshRequested
        }
        val result = linkedMapOf<String, Any?>("success" to errors.isEmpty(), "close" to close, "state" to newState, "errors" to errors)
        if (!close) result["rows"] = ui(source, newState)["rows"]
        result["form"] = source.getLoginInfoMap()
        result["refreshExplore"] = SourceActions.consumeExploreRefresh(source) || refreshExplore
        return result
    }

    fun objectMap(obj: JsonObject): Map<String, Any?> = GSON.fromJson(obj, Map::class.java).entries.associate {
        it.key.toString() to it.value
    }
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

/** Legacy scripts refresh their form by java.upLoginData/reLoginView. HTTP uses the same scoped source. */
class LoginBridge(private val source: BookSource) : io.legado.app.help.JsExtensions by source {
    var renderRequested: Boolean = false
        private set
    var exploreRefreshRequested: Boolean = false
        private set
    fun upLoginData(data: Map<String, Any?>?) {
        if (data == null) source.removeLoginInfo() else check(source.putLoginInfo(GSON.toJson(data.mapValues { it.value?.toString().orEmpty() }))) { "保存登录信息失败" }
    }
    @JvmOverloads fun reLoginView(deltaUp: Boolean = false) { renderRequested = true }
    fun refreshExplore() { renderRequested = true; exploreRefreshRequested = true }
    @JvmOverloads fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        source.startBrowser(url, "书源登录", html)
    }
}
