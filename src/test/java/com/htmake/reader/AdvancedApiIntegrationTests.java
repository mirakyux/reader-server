package com.htmake.reader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.htmake.reader.api.service.advanced.AdvancedStore;
import com.htmake.reader.api.service.advanced.AdvancedStoreKt;
import com.htmake.reader.config.AppConfig;
import com.htmake.reader.utils.VertExtKt;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReaderApplication.class, properties = {
        "reader.app.storagePath=build/test-storage",
        "reader.app.secure=false",
        "reader.app.secureKey="
})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
class AdvancedApiIntegrationTests {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Autowired private Environment environment;
    @Autowired private AppConfig appConfig;

    @BeforeEach
    void waitForServerAndProtectRealStorage() throws Exception {
        assertEquals(Path.of("build/test-storage").toAbsolutePath().normalize(),
                Path.of(VertExtKt.getStoragePath()).toAbsolutePath().normalize());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                var response = get("/health");
                assertEquals(200, response.statusCode());
                assertEquals("ok!", JsonParser.parseString(response.body()).getAsString());
                return;
            } catch (ConnectException error) {
                if (System.nanoTime() >= deadline) throw error;
                Thread.sleep(100);
            }
        }
    }

    @Test
    void mcpUsesNativeJsonRpcAndDistinguishesProtocolErrorsFromToolExecutionFailures() throws Exception {
        var initializationParams = JsonParser.parseString("{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"http-fixture\",\"version\":\"1.0\"}}").getAsJsonObject();
        var initialization = rpc("initialize", initializationParams, "fixture-init");
        assertEquals("fixture-init", initialization.get("id").getAsString());
        assertEquals("2025-11-25", initialization.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertTrue(initialization.getAsJsonObject("result").getAsJsonObject("capabilities").has("tools"));

        var list = rpc("tools/list", new JsonObject(), "fixture-list");
        var tools = list.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(6, tools.size());
        assertTrue(tools.asList().stream().allMatch(tool -> tool.getAsJsonObject().getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean()));

        var stats = rpc("tools/call", params("reading_stats", new JsonObject()), "fixture-stats");
        assertFalse(stats.getAsJsonObject("result").get("isError").getAsBoolean());
        assertTrue(stats.getAsJsonObject("result").getAsJsonObject("structuredContent").has("days"));

        var unknown = rpc("tools/call", params("execute_shell", new JsonObject()), "fixture-unknown");
        assertEquals(-32602, unknown.getAsJsonObject("error").get("code").getAsInt());
        var malformedArguments = rpc("tools/call", params("reading_stats", new JsonArray()), "fixture-args");
        assertEquals(-32602, malformedArguments.getAsJsonObject("error").get("code").getAsInt());
        var unexpectedArgument = new JsonObject(); unexpectedArgument.addProperty("userNS", "other-user");
        assertEquals(-32602, rpc("tools/call", params("reading_stats", unexpectedArgument), "fixture-extra")
                .getAsJsonObject("error").get("code").getAsInt());
        var invalidLimit = new JsonObject(); invalidLimit.addProperty("limit", "not-a-number");
        assertEquals(-32602, rpc("tools/call", params("bookshelf_list", invalidLimit), "fixture-type")
                .getAsJsonObject("error").get("code").getAsInt());
        var invalidIndex = new JsonObject(); invalidIndex.addProperty("bookUrl", "fixture"); invalidIndex.addProperty("index", 4294967296L);
        assertEquals(-32602, rpc("tools/call", params("book_content", invalidIndex), "fixture-overflow")
                .getAsJsonObject("error").get("code").getAsInt());

        var missingBook = new JsonObject(); missingBook.addProperty("bookUrl", "missing-book-" + UUID.randomUUID()); missingBook.addProperty("index", 0);
        var failedExecution = rpc("tools/call", params("book_content", missingBook), "fixture-book");
        assertFalse(failedExecution.has("error"));
        assertTrue(failedExecution.getAsJsonObject("result").get("isError").getAsBoolean());

        var invalidParams = request("tools/list", "fixture-params"); invalidParams.addProperty("params", "invalid");
        assertEquals(-32602, rawJsonRpc(post("/reader3/mcp", invalidParams.toString())).getAsJsonObject("error").get("code").getAsInt());
        var invalidJson = rawJsonRpc(post("/reader3/mcp", "{\"jsonrpc\": "));
        assertEquals(-32700, invalidJson.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(invalidJson.has("id") && invalidJson.get("id").isJsonNull(), "JSON-RPC parse errors must retain id:null");
        assertEquals(-32700, rawJsonRpc(post("/reader3/mcp", "{jsonrpc:'2.0',method:'ping',id:1}"))
                .getAsJsonObject("error").get("code").getAsInt(), "MCP accepts strict JSON only");
        assertEquals(-32700, rawJsonRpc(post("/reader3/mcp", request("ping", "trailing").toString() + "{}"))
                .getAsJsonObject("error").get("code").getAsInt());
        var invalidRequest = rawJsonRpc(post("/reader3/mcp", "[]"));
        assertEquals(-32600, invalidRequest.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(invalidRequest.has("id") && invalidRequest.get("id").isJsonNull());

        var notification = new JsonObject(); notification.addProperty("jsonrpc", "2.0"); notification.addProperty("method", "notifications/initialized");
        var accepted = post("/reader3/mcp", notification.toString());
        assertEquals(202, accepted.statusCode()); assertEquals("", accepted.body());
        var unsupportedVersion = send(HttpRequest.newBuilder(uri("/reader3/mcp")).header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "unsupported").POST(HttpRequest.BodyPublishers.ofString(request("ping", "version-fixture").toString())));
        assertEquals(400, unsupportedVersion.statusCode());
        assertEquals(-32600, JsonParser.parseString(unsupportedVersion.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        assertEquals(405, get("/reader3/mcp").statusCode(), "JSON-only MCP servers reject GET event streams with 405");
    }

    @Test
    void privateProviderSecretsNeverAppearInHttpAndOpenModeDoesNotTrustUserNamespace() throws Exception {
        var store = store();
        var defaultAi = Snapshot.capture(store, "default", "ai-config.json");
        var defaultTts = Snapshot.capture(store, "default", "tts-config.json");
        String foreign = "foreign-" + UUID.randomUUID();
        String secret = "private-fixture-" + UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            byte[] error = ("{\"error\":{\"message\":\"" + secret + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, error.length);
            try (var output = exchange.getResponseBody()) { output.write(error); }
            finally { exchange.close(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var source = new JsonObject(); source.addProperty("bookSourceUrl", "https://fixture.example/" + UUID.randomUUID());
        source.addProperty("bookSourceName", "metadata fixture"); source.addProperty("header", "{\"Authorization\":\"Bearer " + secret + "\"}");
        try {
            store.saveConfig(foreign, config("foreign", base, "foreign-secret", false), false);
            store.saveConfig(foreign, config("foreign", base, "foreign-secret", true), true);
            var savedAi = successful(post("/reader3/ai/config?userNS=" + foreign, config("fixture", base, secret, false).toString())).getAsJsonObject("data");
            var savedTts = successful(post("/reader3/tts/config?userNS=" + foreign, config("fixture", base, secret, true).toString())).getAsJsonObject("data");
            assertMasked(savedAi, "providers", secret); assertMasked(savedTts, "engines", secret);
            assertMasked(successful(get("/reader3/ai/config?userNS=" + foreign)).getAsJsonObject("data"), "providers", secret);
            assertMasked(successful(get("/reader3/tts/config?userNS=" + foreign)).getAsJsonObject("data"), "engines", secret);
            assertEquals("fixture", store.provider("default", "", false).get("id").getAsString());
            assertEquals("foreign", store.provider(foreign, "", false).get("id").getAsString());
            assertEquals("foreign-secret", store.provider(foreign, "", true).get("apiKey").getAsString());

            // Reposting the redacted config preserves the stored key instead of clearing it.
            successful(post("/reader3/ai/config", savedAi.toString()));
            assertEquals(secret, store.provider("default", "", false).get("apiKey").getAsString());
            var failure = post("/reader3/ai/models", "{\"providerId\":\"fixture\"}");
            assertEquals(200, failure.statusCode()); assertFalse(JsonParser.parseString(failure.body()).getAsJsonObject().get("isSuccess").getAsBoolean());
            assertFalse(failure.body().contains(secret));
            var ttsFailure = post("/reader3/tts/synthesize", "{\"engineId\":\"fixture\",\"text\":\"测试正文\"}");
            assertEquals(200, ttsFailure.statusCode()); assertFalse(JsonParser.parseString(ttsFailure.body()).getAsJsonObject().get("isSuccess").getAsBoolean());
            assertFalse(ttsFailure.body().contains(secret));
            successful(post("/reader3/saveBookSource", source.toString()));
            var metadata = rpc("tools/call", params("book_sources_list", new JsonObject()), "fixture-metadata");
            assertFalse(metadata.toString().contains(secret));
            assertFalse(metadata.toString().contains("Authorization"));
            assertTrue(metadata.getAsJsonObject("result").getAsJsonObject("structuredContent").getAsJsonArray("sources").asList()
                    .stream().anyMatch(item -> source.get("bookSourceUrl").equals(item.getAsJsonObject().get("bookSourceUrl"))));
        } finally {
            try { successful(post("/reader3/deleteBookSource", source.toString())); }
            finally {
                server.stop(0); defaultAi.restore(store); defaultTts.restore(store);
                store.delete(foreign, "ai-config.json"); store.delete(foreign, "tts-config.json");
            }
        }
    }

    @Test
    void secureModeRejectsUnauthenticatedPrivateCapabilitiesBeforeReadingUserData() throws Exception {
        boolean previous = appConfig.getSecure();
        try {
            appConfig.setSecure(true);
            for (String path : List.of("/reader3/ai/config", "/reader3/tts/config", "/reader3/assistant/skills", "/reader3/ai/sessions", "/reader3/reading/stats")) {
                var response = get(path + "?userNS=default&secureKey=");
                assertEquals(200, response.statusCode());
                var rejected = JsonParser.parseString(response.body()).getAsJsonObject();
                assertFalse(rejected.get("isSuccess").getAsBoolean(), path);
                assertFalse(rejected.has("data") && !rejected.get("data").isJsonNull(), path);
            }
            var mcp = post("/reader3/mcp", request("tools/list", "anonymous").toString());
            assertEquals(401, mcp.statusCode());
            var rejected = JsonParser.parseString(mcp.body()).getAsJsonObject();
            assertEquals("2.0", rejected.get("jsonrpc").getAsString());
            assertEquals(-32001, rejected.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(rejected.has("id") && rejected.get("id").isJsonNull());
            assertEquals(401, get("/reader3/tts/audio/" + "0".repeat(64)).statusCode());
        } finally { appConfig.setSecure(previous); }
    }

    @Test
    void backupHashMatchesAdvancedDirectoriesForAsciiUnicodeAndNegativeDigestBytes() throws Exception {
        for (String user : List.of("default", "alice", "测试用户")) {
            var bytes = MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.UTF_8));
            var expected = new StringBuilder();
            for (byte value : bytes) expected.append(String.format("%02x", value & 255));
            assertEquals(expected.toString(), AdvancedStoreKt.digest(user));
            assertEquals(64, AdvancedStoreKt.digest(user).length());
            assertEquals(expected.toString(), store().directory(user).getFileName().toString());
        }
    }

    private void assertMasked(JsonObject config, String listKey, String secret) {
        assertFalse(config.toString().contains(secret));
        var provider = config.getAsJsonArray(listKey).get(0).getAsJsonObject();
        assertFalse(provider.has("apiKey")); assertTrue(provider.get("apiKeyConfigured").getAsBoolean());
        assertEquals("fixture", provider.get("id").getAsString());
    }

    private JsonObject config(String id, String base, String secret, boolean tts) {
        var provider = new JsonObject(); provider.addProperty("id", id); provider.addProperty("baseUrl", base);
        provider.addProperty("name", "fixture"); provider.addProperty("model", "fixture-model"); provider.addProperty("apiKey", secret);
        if (tts) provider.addProperty("voice", "fixture-voice");
        var values = new JsonArray(); values.add(provider);
        var config = new JsonObject(); config.add(tts ? "engines" : "providers", values); config.addProperty(tts ? "defaultEngineId" : "defaultProviderId", id);
        return config;
    }

    private JsonObject request(String method, String id) {
        var value = new JsonObject(); value.addProperty("jsonrpc", "2.0"); value.addProperty("method", method); value.addProperty("id", id); return value;
    }
    private JsonObject params(String name, JsonElement arguments) {
        var value = new JsonObject(); value.addProperty("name", name); value.add("arguments", arguments); return value;
    }
    private JsonObject rpc(String method, JsonObject params, String id) throws Exception {
        var value = request(method, id); value.add("params", params); return rawJsonRpc(post("/reader3/mcp", value.toString()));
    }
    private JsonObject rawJsonRpc(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var value = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("2.0", value.get("jsonrpc").getAsString()); assertFalse(value.has("isSuccess"), response.body()); return value;
    }
    private JsonObject successful(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body()); var value = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(value.get("isSuccess").getAsBoolean(), response.body()); return value;
    }
    private HttpResponse<String> get(String path) throws Exception { return send(HttpRequest.newBuilder(uri(path)).GET()); }
    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("reader.server.port", Integer.class) + path); }
    private AdvancedStore store() { return new AdvancedStore(Path.of(VertExtKt.getStoragePath(), "advanced")); }
    private record Snapshot(String user, String name, boolean existed, JsonObject value) {
        static Snapshot capture(AdvancedStore store, String user, String name) {
            return new Snapshot(user, name, Files.exists(store.file(user, name)), store.read(user, name));
        }
        void restore(AdvancedStore store) {
            if (existed) store.write(user, name, value); else store.delete(user, name);
        }
    }
}
