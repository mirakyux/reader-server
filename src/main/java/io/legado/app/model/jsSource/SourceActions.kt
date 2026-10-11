package io.legado.app.model.jsSource

import com.google.gson.JsonParser
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.SourceInfoMap
import io.legado.app.help.source.SourceInteractions
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleAnalyzer
import io.legado.app.utils.GSON
import io.legado.app.utils.isTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import java.net.URLDecoder
import java.util.Base64

/** Server implementations of persisted source actions; clients never provide executable script. */
object SourceActions {
    fun consumeExploreRefresh(source: BookSource): Boolean {
        val cache = source.getScriptCache()
        val requested = cache.get("exploreRefreshRequested") == "true"
        if (requested) cache.delete("exploreRefreshRequested")
        return requested
    }
    val events = setOf("clickAuthor", "longClickAuthor", "clickBookName", "longClickBookName",
        "clickCustomButton", "longClickCustomButton", "clickShareBook", "clickClearCache",
        "clickCopyBookUrl", "clickCopyTocUrl", "clickCopyPlayUrl", "clickBookLabel", "longClickBookLabel",
        "addBookShelf", "delBookShelf", "saveRead", "startRead", "endRead", "startShelfRefresh", "endShelfRefresh")

    fun capabilities(source: BookSource) = mapOf(
        "bookSourceType" to source.bookSourceType, "checkKeyWord" to source.getCheckKeyword(),
        "fileSource" to (source.bookSourceType == 3), "coverDecode" to !source.coverDecodeJs.isNullOrBlank(),
        "imageDecode" to !source.getContentRule().imageDecode.isNullOrBlank(),
        "pay" to !source.getContentRule().payAction.isNullOrBlank(), "eventListener" to source.eventListener,
        "customButton" to source.customButton, "review" to !source.ruleReview?.reviewUrl.isNullOrBlank(),
        "reviewQuote" to !source.ruleReview?.reviewQuoteUrl.isNullOrBlank(), "events" to events,
        "exploreControls" to (!source.exploreUrl.isNullOrBlank() || !source.exploreScreen.isNullOrBlank()))

    fun evaluate(source: BookSource, script: String, values: Map<String, Any?> = emptyMap()): Any? =
        SourceScriptRuntime.run(source, values) { cx, scope ->
            source.mainJs?.takeIf { it.isNotBlank() }?.let { cx.evaluateString(scope, it, "mainJs", 1, null) }
            val value = cx.evaluateString(scope, stripScript(script), "sourceAction", 1, null)
            when (val plain = SourceScriptRuntime.unwrap(value)) {
                null -> null
                is CharSequence -> plain.toString()
                is Number, is Boolean -> plain
                else -> SourceScriptRuntime.json(cx, scope, value)?.let { GSON.fromJson(it, Any::class.java) }
            }
        }

    private fun stripScript(value: String): String = when {
        value.startsWith("@js:", true) -> value.substring(4)
        value.startsWith("<js>", true) && value.endsWith("</js>", true) -> value.substring(4, value.length - 5)
        else -> value
    }

    fun action(source: BookSource, kind: String, book: Book?, chapter: BookChapter?, event: String?, result: Any?, confirm: Boolean): Map<String, Any?> {
        val bindings = mapOf("book" to book, "chapter" to chapter, "title" to chapter?.title,
            "baseUrl" to (chapter?.getAbsoluteURL() ?: book?.bookUrl ?: source.getKey()), "result" to result, "src" to null)
        val response = when (kind) {
            "pay" -> {
                require(confirm) { "购买章节需要明确确认" }
                require(book != null && chapter != null) { "购买操作缺少书籍或章节" }
                val script = source.getContentRule().payAction?.takeIf { it.isNotBlank() } ?: error("书源没有购买规则")
                val value = if (script.startsWith("http", true) || script.startsWith("/")) expandUrl(source, script, bindings)
                    else evaluate(source, script, bindings)
                val url = value?.toString()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                mapOf("handled" to true, "result" to value, "url" to url, "success" to (url == null && value?.toString().isTrue()))
            }
            "event" -> {
                require(event != null && event in events) { "不支持书源事件 $event" }
                if (event!!.contains("CustomButton")) require(source.customButton) { "书源未启用自定义按钮" }
                val script = source.getContentRule().callBackJs?.takeIf { it.isNotBlank() }
                if (!source.eventListener || script == null) mapOf("handled" to false, "result" to null)
                else {
                    val value = evaluate(source, script, bindings + ("event" to event))
                    mapOf("handled" to value?.toString().isTrue(), "result" to value)
                }
            }
            else -> throw IllegalArgumentException("不支持书源操作 $kind")
        }
        return response + mapOf("actions" to SourceInteractions.drain(source), "refreshExplore" to consumeExploreRefresh(source))
    }

    private fun kinds(source: BookSource, text: String?, info: SourceInfoMap): List<ExploreKind> {
        if (text.isNullOrBlank()) return emptyList()
        val normalized = if (text.startsWith("@js:", true) || text.startsWith("<js>", true)) {
            val value = evaluate(source, text, mapOf("infoMap" to info))
            if (value is String) value else GSON.toJson(value)
        } else text
        if (!normalized.trimStart().startsWith("[")) return normalized.split("(&&|\r?\n)+".toRegex()).filter { it.isNotBlank() }
            .map { item -> ExploreKind(title = item.substringBefore("::"), url = item.substringAfter("::", "").takeIf { it.isNotBlank() }) }
        val array = JsonParser.parseString(normalized).asJsonArray
        return array.map { item ->
            require(item.isJsonObject) { "发现控件必须是对象" }
            GSON.fromJson(item, ExploreKind::class.java).also { row ->
                row.preserveFields(item.asJsonObject)
                require(row.type in setOf("url", "text", "button", "toggle", "select")) { "不支持发现控件 ${row.type}" }
                require(row.title.isNotBlank()) { "发现控件缺少标题" }
            }
        }
    }

    fun exploreUi(source: BookSource): Map<String, Any?> {
        val info = SourceInfoMap(source)
        // Defaults must be present before evaluating category URLs or dynamic labels.
        kinds(source, source.exploreScreen, info).forEach { row ->
            if (row.type in setOf("text", "toggle", "select") && !info.containsKey(row.title))
                info[row.title] = row.default ?: row.chars?.firstOrNull().orEmpty()
        }
        fun rows(text: String?) = kinds(source, text, info).mapIndexed { index, row ->
            if (row.type in setOf("text", "toggle", "select") && !info.containsKey(row.title))
                info[row.title] = row.default ?: row.chars?.firstOrNull().orEmpty()
            val label = row.viewName?.takeIf { it.isNotBlank() }?.let { evaluate(source, it, mapOf("infoMap" to info))?.toString() } ?: row.title
            linkedMapOf<String, Any?>("index" to index, "title" to row.title, "label" to label, "type" to row.type,
                "url" to row.url, "chars" to row.chars, "default" to row.default, "viewName" to row.viewName,
                "style" to row.style, "value" to info[row.title]).apply { putAll(row.extraFields()) }
        }
        val categories = rows(source.exploreUrl)
        val filters = rows(source.exploreScreen)
        info.saveNow()
        return mapOf("categories" to categories, "filters" to filters, "values" to info.toMap(),
            "eventListener" to source.eventListener, "customButton" to source.customButton)
    }

    fun exploreAction(source: BookSource, group: String, index: Int, value: String?): Map<String, Any?> {
        val info = SourceInfoMap(source)
        val text = when (group) { "categories" -> source.exploreUrl; "filters" -> source.exploreScreen; else -> error("发现控件分组无效") }
        val row = kinds(source, text, info).getOrNull(index) ?: error("发现控件不存在")
        require(row.type in setOf("text", "toggle", "select", "button")) { "分类链接不能作为控件执行" }
        if (row.type != "button") {
            require(value != null) { "缺少发现控件值" }
            if (row.type == "select" || row.type == "toggle") require(!row.chars.isNullOrEmpty() && value in row.chars!!) { "发现控件选项无效" }
            info[row.title] = value
        }
        val result = row.action?.takeIf { it.isNotBlank() }?.let { evaluate(source, it, mapOf("infoMap" to info)) }
        info.saveNow()
        return exploreUi(source) + mapOf("result" to result, "actions" to SourceInteractions.drain(source),
            "refreshExplore" to consumeExploreRefresh(source))
    }

    private fun expandUrl(source: BookSource, rule: String, values: Map<String, Any?>): String {
        return RuleAnalyzer(rule).innerRule("{{", "}}") { expression ->
            evaluate(source, expression, values)?.let { if (it is Number) Context.toString(it) else it.toString() }.orEmpty()
        }
            .takeIf { it.isNotBlank() } ?: rule
    }

    suspend fun reviews(source: BookSource, book: Book, chapter: BookChapter?, quote: Boolean, review: Map<String, Any?>,
        paragraph: String?, index: Int?): Map<String, Any?> {
        val rule = source.ruleReview ?: error("书源没有段评规则")
        val target = (if (quote) rule.reviewQuoteUrl else rule.reviewUrl)?.takeIf { it.isNotBlank() } ?: error("段评请求规则为空")
        val url = expandUrl(source, target, mapOf("book" to book, "chapter" to chapter, "review" to review,
            "paragraph" to paragraph, "index" to index, "result" to paragraph))
        val response = AnalyzeUrl(url, baseUrl = chapter?.getAbsoluteURL() ?: book.bookUrl, source = source, ruleData = book, chapter = chapter).getStrResponseAwait()
        val body = response.body ?: error("段评响应为空")
        val analyzer = AnalyzeRule(book, source).setContent(body, response.url).apply { this.chapter = chapter; setRedirectUrl(response.url) }
        val contents = analyzer.getStringList(rule.contentRule).orEmpty()
        val avatars = analyzer.getStringList(rule.avatarRule, isUrl = true).orEmpty()
        val times = analyzer.getStringList(rule.postTimeRule).orEmpty()
        val reviews = contents.mapIndexed { i, content -> mapOf("content" to content, "avatar" to avatars.getOrNull(i), "postTime" to times.getOrNull(i)) }
        return mapOf("url" to response.url, "body" to body, "reviews" to reviews, "quote" to quote)
    }

    private fun imageRequest(source: BookSource?, url: String, book: Book?, chapter: BookChapter?): AnalyzeUrl =
        AnalyzeUrl(url, baseUrl = chapter?.getAbsoluteURL() ?: book?.bookUrl ?: source?.getKey().orEmpty(),
            source = source, ruleData = book, chapter = chapter)

    /** Downloads and decodes the exact bytes that the reader, cache and export should use. */
    suspend fun imageBytes(source: BookSource?, url: String, isCover: Boolean, book: Book?,
        chapter: BookChapter? = null): ByteArray = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        imageData(url)?.let { input ->
            decodeImage(source, url, input, isCover, book, chapter).also { currentCoroutineContext().ensureActive() }
        } ?: imageBytes(source, imageRequest(source, url, book, chapter), isCover, book, chapter)
    }

    private fun imageData(url: String): ByteArray? {
        if (!url.startsWith("data:", true)) return null
        val separator = url.indexOf(',')
        require(separator > 5) { "图片 data URI 格式无效" }
        val metadata = url.substring(5, separator)
        val mediaType = metadata.substringBefore(';')
        require(mediaType.startsWith("image/", true) && mediaType.length > 6) { "data URI 必须是图片" }
        // Decode arbitrary binary octets and preserve '+'; form URL decoding would turn it into space.
        val payload = URLDecoder.decode(url.substring(separator + 1).replace("+", "%2B"), Charsets.ISO_8859_1)
        val bytes = if (metadata.substringAfterLast(';').equals("base64", true)) Base64.getDecoder().decode(payload)
            else payload.toByteArray(Charsets.ISO_8859_1)
        require(bytes.isNotEmpty()) { "图片 data URI 内容为空" }
        return bytes
    }

    private suspend fun imageBytes(source: BookSource?, request: AnalyzeUrl, isCover: Boolean, book: Book?,
        chapter: BookChapter?): ByteArray {
        val input = request.getByteArrayAwait()
        currentCoroutineContext().ensureActive()
        return decodeImage(source, request.url, input, isCover, book, chapter).also {
            currentCoroutineContext().ensureActive()
        }
    }

    /** Also accepts already downloaded cover bytes, so a source decoder never forces another request. */
    fun decodeImage(source: BookSource?, src: String, input: ByteArray, isCover: Boolean, book: Book?,
        chapter: BookChapter? = null): ByteArray {
        val script = (if (isCover) source?.coverDecodeJs else source?.getContentRule()?.imageDecode)?.takeIf { it.isNotBlank() }
        val values = mapOf("book" to book, "chapter" to chapter, "title" to chapter?.title,
            "baseUrl" to (chapter?.getAbsoluteURL() ?: book?.bookUrl ?: source?.getKey()), "src" to src, "result" to input)
        val bytes = if (script == null) input else SourceScriptRuntime.run(source, values) { cx, scope ->
            source?.mainJs?.takeIf { it.isNotBlank() }?.let { cx.evaluateString(scope, it, "mainJs", 1, null) }
            when (val value = SourceScriptRuntime.unwrap(cx.evaluateString(scope, stripScript(script), "imageDecode", 1, null))) {
                is ByteArray -> value
                is NativeArray -> ByteArray(value.length.toInt()) { i ->
                    val number = Context.toNumber(value.get(i, value))
                    require(number.isFinite() && number % 1.0 == 0.0 && number >= -128 && number <= 255) { "图片解码数组必须包含字节" }
                    number.toInt().toByte()
                }
                else -> throw IllegalArgumentException("图片解码必须返回字节数组")
            }
        }
        require(bytes.isNotEmpty()) { "图片解码结果为空" }
        return bytes
    }

    suspend fun image(source: BookSource, url: String, isCover: Boolean, book: Book?, chapter: BookChapter? = null): Map<String, Any?> = withContext(Dispatchers.IO) {
        // Keep the resolved URL response without evaluating dynamic URL rules a second time.
        val (resolvedUrl, bytes) = if (url.startsWith("data:", true)) url to imageBytes(source, url, isCover, book, chapter) else {
            val request = imageRequest(source, url, book, chapter)
            request.url to imageBytes(source, request, isCover, book, chapter)
        }
        val type = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            bytes.size >= 3 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF" -> "image/gif"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "application/octet-stream"
        }
        mapOf("url" to resolvedUrl, "base64" to Base64.getEncoder().encodeToString(bytes), "contentType" to type)
    }
}
