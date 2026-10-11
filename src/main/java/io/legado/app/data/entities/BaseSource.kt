package io.legado.app.data.entities

import javax.script.SimpleBindings
import io.legado.app.utils.Base64
import io.legado.app.constant.AppConst
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.*

/**
 * 可在js里调用,source.xxx()
 */
@Suppress("unused")
interface BaseSource : JsExtensions {

    var concurrentRate: String? // 并发率
    var loginUrl: String?       // 登录地址
    // var loginUi: String?   // 登录UI
    var header: String?         // 请求头
    var jsLib: String?
        get() = null
        set(value) {}
    var loginUi: String?
        get() = null
        set(value) {}
    var enabledCookieJar: Boolean?
        get() = false
        set(value) {}

    fun getUserNamespace(): String = "default"
    fun getStorageNamespace(): String = io.legado.app.help.source.SourceStorage.namespace(getUserNamespace(), getKey())
    fun getScriptCache() = io.legado.app.help.source.SourceCache(getStorageNamespace())

    fun evalScript(script: String, bindings: Map<String, Any?> = emptyMap()): Any? =
        io.legado.app.model.jsSource.SourceScriptRuntime.eval(this, script, bindings)

    override fun getTag(): String

    fun getKey(): String

    override fun getSource(): BaseSource? {
        return this
    }

    fun getLoginJs(): String? {
        val loginJs = loginUrl
        return when {
            loginJs == null -> null
            loginJs.startsWith("@js:") -> loginJs.substring(4)
            loginJs.startsWith("<js>") ->
                loginJs.substring(4, loginJs.lastIndexOf("<"))
            else -> loginJs
        }
    }

    fun login() {
        getLoginJs()?.let {
            evalJS(it)
        }
    }

    /**
     * 解析header规则
     */
    fun getHeaderMap(hasLoginHeader: Boolean = false) = HashMap<String, String>().apply {
        this[AppConst.UA_NAME] = AppConst.userAgent
        header?.let {
            GSON.fromJsonObject<Map<String, String>>(
                when {
                    it.startsWith("@js:", true) ->
                        evalJS(it.substring(4)).toString()
                    it.startsWith("<js>", true) ->
                        evalJS(it.substring(4, it.lastIndexOf("<"))).toString()
                    else -> it
                }
            ).getOrNull()?.let { map ->
                putAll(map)
            }
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let {
                putAll(it)
            }
        }
    }

    /**
     * 获取用于登录的头部信息
     */
    fun getLoginHeader(): String? {
        return getScriptCache().get("loginHeader")
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return GSON.fromJsonObject<Map<String, String>>(cache).getOrNull()
    }

    /**
     * 保存登录头部信息,map格式,访问时自动添加
     */
    fun putLoginHeader(header: String) {
        getScriptCache().put("loginHeader", header)
    }

    fun removeLoginHeader() {
        getScriptCache().delete("loginHeader")
    }

    /**
     * 获取用户信息,可以用来登录
     * 用户信息采用aes加密存储
     */
    fun getLoginInfo(): String? {
        try {
            return getScriptCache().get("loginInfo")?.let(io.legado.app.help.source.SourceSecrets::decrypt)
        } catch (e: Exception) {
            log("获取登陆信息出错 " + e.localizedMessage)
            return null
        }
    }

    fun getLoginInfoMap(): MutableMap<String, String> {
        getLoginInfo()?.let { return GSON.fromJsonObject<MutableMap<String, String>>(it).getOrNull() ?: mutableMapOf() }
        val ui = loginUi?.takeIf { it.isNotBlank() } ?: return mutableMapOf()
        val json = when {
            ui.startsWith("@js:") -> evalJS("${getLoginJs().orEmpty()}\n${ui.substring(4)}") {
                put("result", mutableMapOf<String, String>()); put("book", null); put("chapter", null)
            }.toString()
            ui.startsWith("<js>") -> evalJS("${getLoginJs().orEmpty()}\n${ui.substring(4, ui.lastIndexOf('<'))}") {
                put("result", mutableMapOf<String, String>()); put("book", null); put("chapter", null)
            }.toString()
            else -> ui
        }
        val defaults = runCatching {
            val rows = com.google.gson.JsonParser.parseString(json)
            if (!rows.isJsonArray) return@runCatching mutableMapOf<String, String>()
            linkedMapOf<String, String>().apply {
                rows.asJsonArray.forEach { value ->
                    if (value.isJsonObject) {
                        val row = value.asJsonObject
                        if (row["type"]?.asString != "button") row["name"]?.takeUnless { it.isJsonNull }?.asString?.let { name ->
                            put(name, row["default"]?.takeUnless { it.isJsonNull }?.asString ?: "")
                        }
                    }
                }
            }
        }.getOrNull() ?: mutableMapOf()
        if (defaults.isNotEmpty()) putLoginInfo(GSON.toJson(defaults))
        return defaults
    }

    /**
     * 保存用户信息,aes加密
     */
    fun putLoginInfo(info: String): Boolean {
        return try {
            getScriptCache().put("loginInfo", io.legado.app.help.source.SourceSecrets.encrypt(info))
            true
        } catch (e: Exception) {
            log("保存登陆信息出错 " + e.localizedMessage)
            false
        }
    }

    fun removeLoginInfo() {
        getScriptCache().delete("loginInfo")
    }

    fun setVariable(variable: String?) {
        if (variable != null) {
            getScriptCache().put("sourceVariable", variable)
        } else {
            getScriptCache().delete("sourceVariable")
        }
    }

    fun putVariable(variable: String?) = setVariable(variable)

    fun getVariable(): String = getScriptCache().get("sourceVariable") ?: ""

    fun put(key: String, value: String): String {
        getScriptCache().put("v_$key", value)
        return value
    }
    fun get(key: String): String = getScriptCache().get("v_$key") ?: ""

    /** Discovery is recalculated by the server; tell the caller to reload its controls. */
    fun refreshExplore() { getScriptCache().put("exploreRefreshRequested", "true") }
    fun refreshJSLib() { io.legado.app.model.jsSource.SourceScriptRuntime.invalidateLibrary(this) }

    fun putConcurrent(value: String) {
        val parts = value.split('/')
        if ((parts.size == 1 || parts.size == 2) && parts.all { it.toIntOrNull()?.let { number -> number > 0 } == true }) {
            concurrentRate = value
            getScriptCache().put("concurrentRateOverride", value)
        }
    }

    /**
     * 执行JS
     */
    @Throws(Exception::class)
    fun evalJS(jsStr: String, bindingsConfig: SimpleBindings.() -> Unit = {}): Any? {
        val bindings = SimpleBindings()
        bindings.apply(bindingsConfig)
        bindings["java"] = this
        bindings["source"] = this
        bindings["baseUrl"] = getKey()
        bindings["cookie"] = CookieStore.forSource(this)
        bindings["cache"] = getScriptCache()
        return evalScript(jsStr, bindings)
    }
}
