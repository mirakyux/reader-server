package io.legado.app.model.jsSource

import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

object JsSourceConfig {
    private val required = listOf("search", "getChapters", "getContent")
    private val strippedKeys = listOf("mainJs", "ruleSearch", "ruleExplore", "ruleBookInfo", "ruleToc", "ruleContent", "ruleReview")

    fun extract(script: String): BookSource = SourceScriptRuntime.run(null) { cx, scope ->
        cx.evaluateString(scope, script, "import.js", 1, null)
        val primary = cx.evaluateString(scope, "typeof config !== 'undefined' ? config : null", "config", 1, null)
        val legacy = cx.evaluateString(scope, "typeof source !== 'undefined' ? source : null", "source", 1, null)
        val hasPrimary = primary != null && primary !is Undefined
        val hasLegacy = legacy != null && legacy !is Undefined
        // FU falls back to the legacy object when an unrelated config is incomplete.
        val config = if (hasPrimary && (!hasLegacy || isCompleteConfig(cx, scope, primary))) primary else legacy
        val json = SourceScriptRuntime.json(cx, scope, config) ?: error("JS书源缺少顶层 config 配置对象")
        val obj = JsonParser.parseString(json).asJsonObject
        strippedKeys.forEach(obj::remove)
        listOf("exploreUrl", "loginUi", "exploreScreen", "jsLib").forEach { field ->
            obj.get(field)?.takeIf { it.isJsonArray || it.isJsonObject }?.let { value ->
                if (value.isJsonArray && value.asJsonArray.isEmpty) obj.remove(field)
                else obj.addProperty(field, GSON.toJson(value))
            }
        }
        val source = GSON.fromJson(obj, BookSource::class.java)
        io.legado.app.help.SourceAnalyzer.preserveModernFields(source, obj)
        source.mainJs = script
        validateFunctions(source) { name ->
            Context.toBoolean(cx.evaluateString(scope, "typeof $name === 'function'", "contract", 1, null))
        }
        source
    }

    private fun isCompleteConfig(cx: Context, scope: Scriptable, value: Any?): Boolean = runCatching {
        val json = SourceScriptRuntime.json(cx, scope, value) ?: return@runCatching false
        val obj = JsonParser.parseString(json).asJsonObject
        obj.get("bookSourceUrl")?.asString?.isNotBlank() == true &&
            obj.get("bookSourceName")?.asString?.isNotBlank() == true
    }.getOrDefault(false)

    fun validate(source: BookSource) {
        if (source.mainJs.isNullOrBlank()) return
        SourceScriptRuntime.run(source) { cx, scope ->
            cx.evaluateString(scope, source.mainJs, source.bookSourceName, 1, null)
            validateFunctions(source) { name ->
                Context.toBoolean(cx.evaluateString(scope, "typeof $name === 'function'", "contract", 1, null))
            }
        }
    }

    private fun validateFunctions(source: BookSource, has: (String) -> Boolean) {
        require(source.bookSourceUrl.isNotBlank() && source.bookSourceName.isNotBlank()) { "JS书源名称和地址不能为空" }
        val functions = if (source.bookSourceType == 3) listOf("search", "getBookInfo") else required
        functions.forEach { require(has(it)) { "JS书源缺少必备函数 $it" } }
        if (!source.exploreUrl.isNullOrBlank() && source.exploreUrl!!.trim() != "[]") {
            require(has("explore")) { "JS书源声明了 exploreUrl，缺少 explore 函数" }
        }
        if (has("loginUi")) {
            require(source.loginUi.isNullOrBlank() || source.loginUi == "{\"version\":2}") { "loginUi 函数与静态表单不能同时声明" }
            require(has("loginAction")) { "动态登录表单缺少 loginAction 函数" }
            source.loginUi = "{\"version\":2}"
        } else if (!source.loginUi.isNullOrBlank() && source.loginUi!!.trim() != "[]") {
            require(has("login")) { "JS书源声明了 loginUi，缺少 login 函数" }
        }
    }
}
