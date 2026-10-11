package io.legado.app.model.analyzeRule

import io.legado.app.help.http.StrResponse
import io.legado.app.model.DebugLog
import kotlinx.coroutines.CancellationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** FU login scripts may recover both ordinary responses and transport failures. */
suspend fun AnalyzeUrl.getCheckedResponse(jsStr: String? = null, sourceRegex: String? = null,
    debugLog: DebugLog? = null): StrResponse {
    val script = (getSource() as? io.legado.app.data.entities.BookSource)?.loginCheckJs?.takeIf { it.isNotBlank() }
    return try {
        val response = getStrResponseAwait(jsStr = jsStr, sourceRegex = sourceRegex, debugLog = debugLog)
        if (script == null) response else evalJS(script, response) as? StrResponse
            ?: error("loginCheckJs 必须返回 StrResponse")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        if (script == null) throw failure
        val raw = Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
            .code(500).message("Source request failed").build()
        val error = StrResponse(raw, failure.stackTraceToString())
        val recovered = try { evalJS(script, error) as? StrResponse } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (recovered == null || recovered.code() == 500) throw failure
        recovered
    }
}
