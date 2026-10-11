package com.htmake.reader

import com.htmake.reader.config.ReaderJsonFactory
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.DecodeException
import io.vertx.core.json.EncodeException
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.spi.JsonFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter

class Jackson3RuntimeTests {
    data class KotlinRecord(val name: String, val enabled: Boolean = true)

    @Test
    fun `public JSON APIs select configured Jackson 3 without Jackson 2 core or databind`() {
        val factory = JsonFactory.load()
        assertInstanceOf(ReaderJsonFactory::class.java, factory)
        assertSame(factory.codec(), Json.CODEC)
        assertEquals(3, JsonMapper.builder().build().version().majorVersion)
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.fasterxml.jackson.core.JsonFactory") }
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.fasterxml.jackson.databind.ObjectMapper") }
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.fasterxml.jackson.module.kotlin.KotlinModule") }
    }

    @Test
    fun `Kotlin constructor requirements defaults and unknown fields work before Spring starts`() {
        val record = Json.decodeValue("""{"name":"中文","futureFlag":true}""", KotlinRecord::class.java)
        assertEquals(KotlinRecord("中文"), record)
        assertEquals(KotlinRecord("正文", false), JsonObject().put("name", "正文").put("enabled", false)
            .mapTo(KotlinRecord::class.java))
        assertThrows(DecodeException::class.java) { Json.decodeValue("{}", KotlinRecord::class.java) }
        assertThrows(DecodeException::class.java) { Json.decodeValue("""{"name":null}""", KotlinRecord::class.java) }
    }

    @Test
    fun `malformed and trailing JSON are rejected for strings buffers and readers`() {
        for (input in listOf("", "{", "[1,", "{} []", "[1] true", "null false", "1 2")) {
            assertThrows(DecodeException::class.java, { Json.decodeValue(input) }, input)
            assertThrows(DecodeException::class.java, { Json.decodeValue(Buffer.buffer(input)) }, input)
            assertThrows(DecodeException::class.java, { Json.CODEC.fromReader(StringReader(input)) }, input)
        }
    }

    @Test
    fun `untyped values preserve Vertx wrappers and scalars across all decoder paths`() {
        assertInstanceOf(JsonObject::class.java, Json.decodeValue("""{"chapters":["一",null]}"""))
        assertInstanceOf(JsonArray::class.java, Json.decodeValue(Buffer.buffer("[1,true]")))
        assertInstanceOf(JsonObject::class.java, Json.CODEC.fromValue(mapOf("name" to "一"), Any::class.java))
        assertInstanceOf(JsonArray::class.java, Json.CODEC.fromValue(listOf(1, false), Any::class.java))
        assertEquals("正文", Json.decodeValue("\"正文\""))
        assertEquals(42, Json.decodeValue("42"))
        assertEquals(true, Json.decodeValue("true"))
        assertNull(Json.decodeValue("null"))
        assertEquals("null", Json.encode(null))
    }

    @Test
    fun `pretty and buffer output preserve JSON contents and encoding errors retain Vertx types`() {
        val value = JsonObject().put("name", "中文 📚").put("data", JsonArray().add(true).add(null))
        assertTrue(Json.encodePrettily(value).contains('\n'))
        assertEquals(value, JsonObject(Json.encodePrettily(value)))
        assertEquals(value, JsonObject(Json.encodeToBuffer(value)))
        assertEquals(value, JsonObject(Json.CODEC.toBuffer(value, true)))
        val failing = object {
            val value: String get() = error("fixture getter failure")
        }
        val exception = assertThrows(EncodeException::class.java) { Json.encode(failing) }
        assertNotNull(exception.cause)
        assertThrows(DecodeException::class.java) { JsonObject().put("name", JsonObject()).mapTo(KotlinRecord::class.java) }
    }

    @Test
    fun `stream and reader APIs preserve caller ownership without closing or flushing`() {
        val value = JsonObject().put("name", "中文 📚")
        val input = object : ByteArrayInputStream(value.toBuffer().bytes) {
            var closed = false
            override fun close() { closed = true }
        }
        val reader = object : StringReader(value.encode()) {
            var closed = false
            override fun close() { closed = true }
        }
        val output = object : ByteArrayOutputStream() {
            var closed = false
            var flushed = false
            override fun close() { closed = true }
            override fun flush() { flushed = true }
        }
        val writer = object : StringWriter() {
            var closed = false
            var flushed = false
            override fun close() { closed = true }
            override fun flush() { flushed = true }
        }
        assertEquals(value, Json.CODEC.fromStream(input))
        assertEquals(value, Json.CODEC.fromReader(reader))
        Json.CODEC.toStream(value, output)
        Json.CODEC.toWriter(value, writer)
        assertEquals(value, JsonObject(Buffer.buffer(output.toByteArray())))
        assertEquals(value, JsonObject(writer.toString()))
        assertFalse(input.closed)
        assertFalse(reader.closed)
        assertFalse(output.closed || output.flushed)
        assertFalse(writer.closed || writer.flushed)
    }
}
