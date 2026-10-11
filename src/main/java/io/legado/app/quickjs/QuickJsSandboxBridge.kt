package io.legado.app.quickjs

import io.legado.app.help.source.BrowserBridge
import io.vertx.core.json.JsonObject

/** FU's string-only sandbox API, backed by a fresh QuickJS WASM worker. */
class QuickJsSandboxBridge {
    fun evalString(script: String): String {
        if (script.length > 384_000) fail("INPUT_TOO_LARGE")
        val response = try {
            BrowserBridge.call("/quickjs", JsonObject().put("script", script))
        } catch (error: Exception) {
            throw IllegalStateException("QuickJS sandbox failed: BIND_FAILED", error)
        }
        when (response.getValue("success")) {
            true -> {
                val value = response.getValue("value") as? String ?: fail("INVALID_RESPONSE")
                if (value.length > 65_536) fail("OUTPUT_TOO_LARGE")
                return value
            }
            false -> fail(response.getValue("error") as? String ?: "INVALID_RESPONSE")
            else -> fail("INVALID_RESPONSE")
        }
    }

    private fun fail(code: String): Nothing = throw IllegalStateException("QuickJS sandbox failed: $code")
}
