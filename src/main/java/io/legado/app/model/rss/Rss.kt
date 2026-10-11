package io.legado.app.model.rss

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.DebugLog
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

object Rss {
    suspend fun getArticles(
        sortName: String,
        sortUrl: String,
        rssSource: RssSource,
        page: Int,
        debugLog: DebugLog?,
        key: String? = null
    ): Pair<MutableList<RssArticle>, String?> {
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            sortUrl,
            page = page,
            key = key,
            baseUrl = rssSource.sourceUrl,
            source = rssSource,
            ruleData = ruleData,
            headerMapF = rssSource.getHeaderMap()
        )
        val response = checkedResponse(analyzeUrl, rssSource, debugLog)
        // debugLog?.log(rssSource.sourceUrl, "┌获取链接内容:${sortUrl}")
        // debugLog?.log(rssSource.sourceUrl, "└\n${body}")
        return RssParserByRule.parseXML(sortName, sortUrl, response.body, rssSource, ruleData, debugLog, response.url)
    }

    suspend fun getContent(
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
        debugLog: DebugLog?
    ): String {
        val analyzeUrl = AnalyzeUrl(
            rssArticle.link,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            headerMapF = rssSource.getHeaderMap()
        )
        val response = checkedResponse(analyzeUrl, rssSource, debugLog)
        // debugLog?.log(rssSource.sourceUrl, "┌获取链接内容:${rssArticle.link}")
        // debugLog?.log(rssSource.sourceUrl, "└\n${body}")
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(response.body)
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, rssArticle.link))
            .setRedirectUrl(response.url)
        val content = analyzeRule.getString(ruleContent)
        if (!content.contains('<')) return content
        val document = org.jsoup.Jsoup.parseBodyFragment(content, response.url)
        document.select("[src],a[href]").forEach { element ->
            val attribute = if (element.hasAttr("src")) "src" else "href"
            val absolute = element.absUrl(attribute)
            if (absolute.isNotBlank()) element.attr(attribute, absolute)
        }
        return document.body().html()
    }

    private suspend fun checkedResponse(url: AnalyzeUrl, source: RssSource, log: DebugLog?): io.legado.app.help.http.StrResponse {
        val script = source.loginCheckJs?.takeIf { it.isNotBlank() }
        val response = try { url.getStrResponseAwait(debugLog = log) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            if (script == null) throw error
            val failed = io.legado.app.help.http.StrResponse(okhttp3.Response.Builder().code(500).message(error.message ?: "网络请求失败")
                .protocol(okhttp3.Protocol.HTTP_1_1).request(okhttp3.Request.Builder().url(url.url).build()).build(), "")
            val replacement = runCatching { url.evalJS(script, failed) as io.legado.app.help.http.StrResponse }.getOrNull()
            if (replacement == null || !replacement.isSuccessful()) throw error
            return replacement
        }
        val checked = if (script == null) response else url.evalJS(script, response) as io.legado.app.help.http.StrResponse
        if (checked.url != response.url) log?.log(source.sourceUrl, "登录检查切换地址：${checked.url}")
        if (checked.raw.priorResponse != null) log?.log(source.sourceUrl, "重定向后地址：${checked.url}")
        return checked
    }
}
