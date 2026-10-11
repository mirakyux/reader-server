package com.htmake.reader.api.service.advanced

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/** MCP Streamable HTTP's JSON response mode; tool operations share the reader's user identity. */
class AdvancedMcp(private val tools: AdvancedTools) {
    companion object {
        val supportedVersions = setOf("2025-03-26", "2025-06-18", "2025-11-25")
    }
    suspend fun handle(user: String, request: JsonObject): JsonObject? {
        val id = request.get("id") ?: JsonNull.INSTANCE
        if ((!id.isJsonNull && (!id.isJsonPrimitive || !(id.asJsonPrimitive.isString || id.asJsonPrimitive.isNumber))) ||
            request.get("jsonrpc")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } != true ||
            request.get("method")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } != true)
            return error(JsonNull.INSTANCE, -32600, "Invalid Request")
        if (request.string("jsonrpc") != "2.0" || request.string("method").isBlank()) return error(id, -32600, "Invalid Request")
        if (!request.has("id")) return null
        return try {
            val parameters = request.getAsJsonObject("params") ?: JsonObject()
            when (request.string("method")) {
                "initialize" -> {
                    require(parameters.get("protocolVersion")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() } == true)
                    require(parameters.get("capabilities")?.isJsonObject == true)
                    val client = parameters.getAsJsonObject("clientInfo") ?: error("clientInfo is required")
                    require(client.get("name")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true &&
                        client.get("version")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true)
                    require(client.string("name").length in 1..200 && client.string("version").length in 1..200)
                    val version = parameters.string("protocolVersion").takeIf { it in supportedVersions } ?: "2025-11-25"
                    result(id, jsonObject("protocolVersion" to version, "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                        "serverInfo" to mapOf("name" to "reader", "version" to "2.5.4"), "instructions" to "工具仅可读取已认证用户的书架、书源与阅读统计。"))
                }
                "ping" -> result(id, JsonObject())
                "tools/list" -> {
                    require(parameters.string("cursor").isEmpty()) { "不支持此分页游标" }
                    result(id, jsonObject("tools" to tools.definitions()))
                }
                "tools/call" -> {
                    val name = parameters.string("name")
                    val definition = tools.definitions().firstOrNull { it.string("name") == name }
                        ?: return error(id, -32602, "Unknown tool")
                    val supplied = parameters.get("arguments")
                    require(supplied == null || supplied.isJsonObject) { "arguments must be an object" }
                    val arguments = supplied?.asJsonObject ?: JsonObject()
                    validate(definition.getAsJsonObject("inputSchema"), arguments)
                    try {
                        val data = tools.execute(user, name, arguments)
                        result(id, jsonObject("content" to listOf(mapOf("type" to "text", "text" to advancedJson.toJson(data))),
                            "structuredContent" to data, "isError" to false))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { result(id, jsonObject("content" to listOf(mapOf("type" to "text", "text" to "工具执行失败，请检查当前用户的书籍、书源和参数")), "isError" to true)) }
                }
                else -> error(id, -32601, "Method not found")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error(id, -32602, "Invalid params") }
    }
    private fun validate(schema: JsonObject, arguments: JsonObject) {
        val properties = schema.getAsJsonObject("properties") ?: JsonObject()
        if (!schema.flag("additionalProperties", true)) require(arguments.keySet().all { it in properties.keySet() })
        schema.array("required").forEach { require(arguments.has(it.asString) && !arguments.get(it.asString).isJsonNull) }
        for ((name, value) in arguments.entrySet()) {
            val rule = properties.getAsJsonObject(name) ?: continue
            when (rule.string("type")) {
                "string" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                "integer", "number" -> {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                    val number = value.asBigDecimal
                    if (rule.string("type") == "integer") require(number.stripTrailingZeros().scale() <= 0 &&
                        number >= Int.MIN_VALUE.toBigDecimal() && number <= Int.MAX_VALUE.toBigDecimal())
                    rule.get("minimum")?.let { require(number >= it.asBigDecimal) }
                    rule.get("maximum")?.let { require(number <= it.asBigDecimal) }
                }
                "boolean" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                "object" -> require(value.isJsonObject)
                "array" -> require(value.isJsonArray)
            }
        }
    }
    private fun result(id: JsonElement, result: JsonObject) = jsonObject("jsonrpc" to "2.0", "id" to id, "result" to result)
    fun error(id: JsonElement = JsonNull.INSTANCE, code: Int, message: String) = jsonObject("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))
}
