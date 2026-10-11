package io.legado.app.model.jsSource

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.getProxyClient
import io.legado.app.utils.GSON
import okhttp3.Request
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.lc.type.TypeInfo
import java.security.SecureRandom
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.ensureActive

/** Fresh request scopes inherit FU-style library globals without retaining request bindings. */
object SourceScriptRuntime {
    private data class RequestScope(val scope: Scriptable, val names: Set<String>)
    private val activeRequest = ThreadLocal<RequestScope?>()
    internal fun libraryBinding(name: String): Any? {
        val request = activeRequest.get() ?: return Scriptable.NOT_FOUND
        return if (name in request.names) request.scope.get(name, request.scope) else Scriptable.NOT_FOUND
    }

    fun invalidateLibrary(source: BaseSource) {
        SourceSharedJsScope.invalidateLibrary(source)
        libraries(source).filter { it.trimStart().startsWith("http://") || it.trimStart().startsWith("https://") }
            .forEach { source.getScriptCache().delete(source.md5Encode16(it.trim())) }
    }
    private val activeJob = ThreadLocal<Job?>()
    fun cancellationContext(job: Job) = activeJob.asContextElement(job)
    private const val TIMEOUT_MILLIS = 30_000L
    private class Deadline(var value: Long)
    private val activeDeadlines = ThreadLocal.withInitial { mutableListOf<Deadline>() }
    /** Human verification has its own bounded timeout and does not consume script CPU time. */
    fun <T> pauseDeadline(block: () -> T): T {
        val started = System.nanoTime()
        try { return block() }
        finally { val elapsed = System.nanoTime() - started; activeDeadlines.get().forEach { it.value += elapsed } }
    }
    private val random = SecureRandom()
    private val bridgeClasses = setOf(
        "io.legado.app.data.entities.BaseSource", "io.legado.app.data.entities.BookSource",
        "io.legado.app.data.entities.RssSource", "io.legado.app.data.entities.Book",
        "io.legado.app.data.entities.BookChapter", "io.legado.app.data.entities.SearchBook",
        "io.legado.app.help.JsExtensions", "io.legado.app.help.CacheManager",
        "io.legado.app.help.SourceJsURL",
        "io.legado.app.help.SourceSymmetricCrypto", "io.legado.app.help.SourceAsymmetricCrypto",
        "io.legado.app.help.SourceSign",
        "io.legado.app.help.source.SourceCache", "io.legado.app.help.http.SourceCookieStore",
        "io.legado.app.help.source.SourceInfoMap",
        "io.legado.app.help.http.StrResponse", "io.legado.app.model.jsSource.JsSourceEngine",
        "io.legado.app.model.jsSource.LoginBridge",
        "io.legado.app.quickjs.QuickJsSandboxBridge",
        "io.legado.app.model.analyzeRule.AnalyzeRule", "io.legado.app.model.analyzeRule.AnalyzeUrl",
        "io.legado.app.model.analyzeRule.QueryTTF"
    )
    private val valueClasses = setOf(
        "java.lang.String", "java.lang.CharSequence", "java.lang.Boolean", "java.lang.Number",
        "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
        "java.lang.Byte", "java.lang.Short", "java.lang.Character", "java.lang.Object",
        "java.math.BigDecimal", "java.math.BigInteger", "java.util.Map", "java.util.List",
        "java.util.Set", "java.util.Collection", "java.util.Iterator", "java.util.ArrayList",
        "java.util.LinkedList", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
        "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.Collections",
        "java.util.Arrays", "java.util.Date", "java.util.Calendar", "java.util.GregorianCalendar",
        "java.util.TimeZone", "java.util.Locale", "java.util.UUID", "java.util.regex.Pattern",
        "java.util.regex.Matcher", "okhttp3.Response", "okhttp3.Headers", "okhttp3.HttpUrl",
        "okhttp3.Request", "okhttp3.Protocol", "org.jsoup.Jsoup", "org.jsoup.Connection",
        "com.jayway.jsonpath.JsonPath", "com.jayway.jsonpath.Configuration",
        "org.jsoup.helper.HttpConnection", "com.google.gson.internal.LinkedTreeMap",
        "kotlin.collections.EmptyMap", "kotlin.collections.EmptyList", "kotlin.collections.EmptySet"
    )
    private val sourceInternals = setOf(
        "runtimeUserNamespace", "userNamespace", "storageNamespace", "scriptCache",
        "getUserNamespace", "getStorageNamespace", "getScriptCache", "copy", "copy\$default",
        "setBookSourceUrl", "setRuntimeUserNamespace", "getRuntimeUserNamespace", "class", "getClass"
    )
    private fun componentClassName(name: String): String = name.trimStart('[').let {
        if (name.startsWith('[') && it.startsWith('L') && it.endsWith(';')) it.substring(1, it.length - 1) else it
    }
    private fun visibleClass(name: String): Boolean = if (name.startsWith('[')) {
        val component = componentClassName(name)
        component in setOf("B", "C", "I", "J", "S", "F", "D", "Z") || visibleClass(component)
    } else name in bridgeClasses || name in valueClasses ||
        name.startsWith("org.jsoup.nodes.") || name.startsWith("org.jsoup.select.") ||
        name.startsWith("io.legado.app.data.entities.rule.") ||
        name.startsWith("org.jsoup.parser.") || name.startsWith("org.jsoup.Connection\$") ||
        name.startsWith("org.jsoup.helper.HttpConnection\$") || name.startsWith("org.seimicrawler.xpath.") ||
        name.startsWith("com.jayway.jsonpath.") || name.startsWith("com.google.gson.") ||
        name.startsWith("cn.hutool.crypto.") || name.startsWith("java.util.Collections\$") ||
        name.startsWith("java.util.Arrays\$") || name.startsWith("java.util.HashMap\$") ||
        name.startsWith("java.util.LinkedHashMap\$") || name.startsWith("java.util.ImmutableCollections\$") ||
        name.startsWith("java.util.Map\$") || name in setOf("[B", "[C", "[I", "[J", "[S", "[F", "[D", "[Z")
    private val cryptoJs by lazy {
        SourceScriptRuntime::class.java.getResourceAsStream("/scripts/cryptojs.min.js")
            ?.bufferedReader()?.use { it.readText() } ?: error("内置 CryptoJS 资源缺失")
    }

    fun <T> run(source: BaseSource?, values: Map<String, Any?> = emptyMap(), block: (Context, Scriptable) -> T): T {
        val deadline = Deadline(System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L)
        var wrappingHost = false
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
                instructionObserverThreshold = 10_000
                // A returned host object is not permission to import its class
                // and construct another user's source, cache or bridge.
                setClassShutter { name -> visibleClass(name) && (wrappingHost ||
                    !componentClassName(name).startsWith("io.legado.app.") ||
                    componentClassName(name) == "io.legado.app.help.http.StrResponse") }
                wrapFactory = object : WrapFactory() {
                    init { isJavaPrimitiveWrap = false }
                    override fun wrapAsJavaObject(cx: Context, scope: Scriptable, javaObject: Any, staticType: TypeInfo): Scriptable? {
                        // FU hides returned raw files as null; private file helpers remain usable.
                        if (javaObject is java.io.File) return null
                        val previous = wrappingHost
                        wrappingHost = true
                        try {
                            if (javaObject !is BaseSource) return super.wrapAsJavaObject(cx, scope, javaObject, staticType)
                            return object : NativeJavaObject(scope, javaObject, staticType) {
                                private fun blocked(name: String) = name == "class" || name == "getClass" ||
                                    name in sourceInternals || name.startsWith("setRuntimeUserNamespace")
                                override fun get(name: String, start: Scriptable): Any =
                                    if (blocked(name)) Scriptable.NOT_FOUND else super.get(name, start)
                                override fun has(name: String, start: Scriptable): Boolean = !blocked(name) && super.has(name, start)
                                override fun put(name: String, start: Scriptable, value: Any?) {
                                    require(!blocked(name) && name != "bookSourceUrl") { "书源运行时身份不可修改" }
                                    super.put(name, start, value)
                                }
                            }
                        } finally { wrappingHost = previous }
                    }
                }
            }
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                activeJob.get()?.ensureActive()
                check(System.nanoTime() < deadline.value && !Thread.currentThread().isInterrupted) { "书源脚本执行超时" }
            }
        }
        activeDeadlines.get().add(deadline)
        val previousRequest = activeRequest.get()
        activeRequest.remove()
        try { return factory.call { cx ->
            // FU templates import Jsoup, JSONPath and safe JDK utilities by name.
            // Class imports still pass the same policy; reflection stays denied.
            val shared = source?.let { current ->
                SourceSharedJsScope.getOrCreate(current, cx) { libraryScope ->
                    installCryptoJs(cx, libraryScope)
                    libraries(current).forEach { value ->
                        val script = if (value.trimStart().startsWith("http://") || value.trimStart().startsWith("https://")) {
                            loadRemoteLibrary(current, value.trim())
                        } else value
                        cx.evaluateString(libraryScope, script, "jsLib", 1, null)
                    }
                }
            }
            val scope = if (shared == null) cx.initStandardObjects().also { installCryptoJs(cx, it) }
                else (cx.newObject(shared) as ScriptableObject).apply { prototype = shared; parentScope = null }
            val bindings = linkedMapOf<String, Any?>()
            if (source != null) {
                bindings["java"] = source
                bindings["source"] = source
                bindings["sourceApi"] = source
                bindings["baseUrl"] = source.getKey()
                bindings["cookie"] = CookieStore.forSource(source)
                bindings["cache"] = io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace())
            }
            bindings.putAll(values)
            if (source != null) bindings["cache"] = io.legado.app.help.source.SourceStorage.sharedCache(source.getUserNamespace())
            bindings.forEach { (name, value) ->
                val normalized = if (value is Map<*, *> && value !is io.legado.app.help.source.SourceInfoMap) {
                    cx.evaluateString(scope, "JSON.parse(${GSON.toJson(GSON.toJson(value))})", "binding", 1, null)
                } else Context.javaToJS(value, scope)
                ScriptableObject.putProperty(scope, name, normalized)
            }
            activeRequest.set(RequestScope(scope, bindings.keys.toSet()))
            block(cx, scope)
        } } finally {
            if (previousRequest == null) activeRequest.remove() else activeRequest.set(previousRequest)
            activeDeadlines.get().remove(deadline)
            if (activeDeadlines.get().isEmpty()) activeDeadlines.remove()
        }
    }

    private fun libraries(source: BaseSource): List<String> = source.jsLib?.takeIf { it.isNotBlank() }?.let { library ->
        if (library.trimStart().startsWith("{")) com.google.gson.JsonParser.parseString(library).asJsonObject.entrySet().map { it.value.asString }
        else listOf(library)
    } ?: emptyList()

    private fun installCryptoJs(cx: Context, scope: ScriptableObject) {
        cx.evaluateString(scope, cryptoJs, "CryptoJS", 1, null)
        val secureWord = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any = random.nextInt().toDouble()
        }
        ScriptableObject.putProperty(scope, "__readerSecureWord", secureWord)
        cx.evaluateString(scope, """
            CryptoJS.lib.WordArray.random=(function(nextWord){
                return function(count){var words=[];for(var i=0;i<count;i+=4)words.push(nextWord());return CryptoJS.lib.WordArray.create(words,count);};
            })(__readerSecureWord);
        """.trimIndent(), "secureRandom", 1, null)
        scope.delete("__readerSecureWord")
    }

    private fun loadRemoteLibrary(source: BaseSource, url: String): String {
        val cache = source.getScriptCache()
        val key = source.md5Encode16(url)
        cache.getFile(key)?.takeIf { it.isNotBlank() }?.let { return it }
        // The library must load before a dynamic header can call its functions.
        // Bootstrap uses static and persisted login headers, then ordinary requests evaluate the full header.
        val headers = io.legado.app.help.source.BrowserBridge.bootstrapHeaders(source).toMutableMap()
        val proxy = headers.remove("proxy")
        val cookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return getProxyClient(proxy, source = source, customCookie = cookie, cookieUrl = url).newCall(request).execute().use { response ->
            check(response.isSuccessful) { "jsLib 下载失败: HTTP ${response.code}" }
            val content = response.body.string()
            require(content.isNotBlank()) { "jsLib 内容为空" }
            cache.putFile(key, content)
            content
        }
    }

    fun eval(source: BaseSource, script: String, bindings: Map<String, Any?>): Any? = run(source, bindings) { cx, scope ->
        unwrap(cx.evaluateString(scope, script, source.getTag(), 1, null))
    }

    fun unwrap(value: Any?): Any? = when (value) {
        null, is Undefined -> null
        is Wrapper -> value.unwrap()
        else -> value
    }

    fun json(cx: Context, scope: Scriptable, value: Any?): String? {
        if (value == null || value is Undefined) return null
        ScriptableObject.putProperty(scope, "__readerResult", value)
        val encoded = cx.evaluateString(scope, "JSON.stringify(__readerResult)", "result", 1, null)
        return if (encoded is Undefined) null else Context.toString(encoded)
    }
}
