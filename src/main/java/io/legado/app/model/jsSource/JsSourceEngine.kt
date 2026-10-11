package io.legado.app.model.jsSource

import com.google.gson.JsonElement
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensions
import io.legado.app.model.DebugLog
import io.legado.app.utils.GSON
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

class JsSourceEngine(private val source: BookSource, private val debugger: DebugLog? = null) : JsExtensions {
    private val quickJsSandboxBridge by lazy { io.legado.app.quickjs.QuickJsSandboxBridge() }
    fun getQuickJsSandbox(): io.legado.app.quickjs.QuickJsSandboxBridge = quickJsSandboxBridge

    override fun getSource() = source
    override fun log(msg: Any?): Any? { debugger?.log(source.getKey(), msg.toString()); return msg }

    fun call(name: String, args: List<Any?> = emptyList(), optional: Boolean = false, expectText: Boolean = false): String? =
        SourceScriptRuntime.run(source, mapOf("java" to this)) { cx, scope ->
            cx.evaluateString(scope, source.mainJs ?: error("mainJs 为空"), source.getTag(), 1, null)
            val function = ScriptableObject.getProperty(scope, name) as? Function
            if (function == null) {
                if (optional) return@run null
                error("JS书源缺少函数 $name")
            }
            val parameters = args.map { value ->
                when (value) {
                    null -> null
                    is String, is Number, is Boolean -> value
                    else -> cx.evaluateString(scope, "JSON.parse(${GSON.toJson(GSON.toJson(value))})", "argument", 1, null)
                }
            }.toTypedArray()
            val result = function.call(cx, scope, scope, parameters)
            val value = SourceScriptRuntime.unwrap(result)
            if (expectText) require(value is CharSequence) { "$name 返回值必须是正文字符串" }
            if (value is CharSequence) value.toString() else SourceScriptRuntime.json(cx, scope, result)
        }
}
