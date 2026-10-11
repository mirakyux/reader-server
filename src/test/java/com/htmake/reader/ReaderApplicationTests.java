package com.htmake.reader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReaderApplication.class, properties = {
        "reader.app.storagePath=build/test-storage",
        "reader.app.secure=false",
        "reader.app.secureKey="
})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
public class ReaderApplicationTests {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    public void contextLoads() {
    }

    @BeforeEach
    public void waitForServer() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                assertEquals("ok!", getJson("/health").getAsString());
                break;
            } catch (ConnectException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    @Test
    public void serverRespondsWithoutDesktop() throws Exception {
        assertEquals("ok!", getJson("/health").getAsString());
        JsonObject response = getJson("/reader3/getUserInfo").getAsJsonObject();
        assertTrue(response.get("isSuccess").getAsBoolean());
        JsonObject data = response.getAsJsonObject("data");
        assertFalse(data.get("secure").getAsBoolean());
        assertFalse(data.get("secureKey").getAsBoolean());
        assertFalse(data.has("userInfo"));
    }

    @Test
    public void frontendIsDeployedSeparatelyAndAllowedOriginCanPreflight() throws Exception {
        getJson("/health");
        HttpURLConnection root = connection("/");
        try {
            assertEquals(404, root.getResponseCode());
        } finally {
            root.disconnect();
        }
        HttpRequest request = HttpRequest.newBuilder(connection("/reader3/saveBookSource").getURL().toURI())
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,mcp-protocol-version,accept").build();
        HttpResponse<String> preflight = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("http://localhost:5173", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("true", preflight.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"));
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase().contains("mcp-protocol-version"));
    }

    @Test
    public void arbitraryOriginsAreNotGrantedCredentials() throws Exception {
        getJson("/health");
        HttpRequest request = HttpRequest.newBuilder(connection("/reader3/getUserInfo").getURL().toURI())
                .header("Origin", "https://untrusted.example").GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
    }

    @Test
    public void oneBookCanKeepSeveralBookmarksAndChangeOnlyTheSelectedOne() throws Exception {
        String bookName = "bookmark-regression-" + UUID.randomUUID();
        String author = "fixture";
        JsonObject first = bookmark(bookName, author, 101, "first");
        JsonObject second = bookmark(bookName, author, 102, "second");
        JsonObject third = bookmark(bookName, author, 103, "third");
        JsonObject otherBook = bookmark(bookName + "-other", author, 101, "other book");
        try {
            postJson("/reader3/saveBookmark", first);
            postJson("/reader3/saveBookmark", second);
            postJson("/reader3/saveBookmark", otherBook);
            assertEquals(2, bookmarksFor(bookName, author).size());

            first.addProperty("content", "first updated");
            postJson("/reader3/saveBookmark", first);
            List<JsonObject> saved = bookmarksFor(bookName, author);
            assertEquals(2, saved.size());
            assertEquals("first updated", saved.get(0).get("content").getAsString());
            assertEquals("second", saved.get(1).get("content").getAsString());

            second.addProperty("content", "second updated");
            JsonArray batch = new JsonArray();
            batch.add(second);
            batch.add(third);
            postJson("/reader3/saveBookmarks", batch);
            saved = bookmarksFor(bookName, author);
            assertEquals(3, saved.size());
            assertEquals("second updated", saved.get(1).get("content").getAsString());

            postJson("/reader3/deleteBookmark", first);
            saved = bookmarksFor(bookName, author);
            assertEquals(List.of(102L, 103L), saved.stream().map(value -> value.get("time").getAsLong()).toList());
            assertEquals(1, bookmarksFor(bookName + "-other", author).size());

            JsonArray selected = new JsonArray();
            selected.add(second);
            postJson("/reader3/deleteBookmarks", selected);
            saved = bookmarksFor(bookName, author);
            assertEquals(1, saved.size());
            assertEquals(103, saved.getFirst().get("time").getAsLong());
        } finally {
            JsonArray cleanup = new JsonArray();
            cleanup.add(first);
            cleanup.add(second);
            cleanup.add(third);
            cleanup.add(otherBook);
            postJson("/reader3/deleteBookmarks", cleanup);
        }
    }

    @Test
    public void replacementIdentityUsesIdsAndKeepsScopeFields() throws Exception {
        String name = "rule-regression-" + UUID.randomUUID();
        JsonObject first = new JsonObject();
        first.addProperty("id", 78101);
        first.addProperty("name", name);
        first.addProperty("pattern", "original");
        first.addProperty("replacement", "changed");
        first.addProperty("scopeTitle", true);
        first.addProperty("scopeContent", false);
        first.addProperty("excludeScope", "excluded");
        JsonObject second = first.deepCopy();
        second.addProperty("id", 78102);
        JsonArray batch = new JsonArray(); batch.add(first); batch.add(second);
        try {
            postJson("/reader3/saveReplaceRules", batch);
            JsonArray saved = getJson("/reader3/getReplaceRules").getAsJsonObject().getAsJsonArray("data");
            List<JsonObject> matches = new ArrayList<>();
            for (JsonElement item : saved) if (name.equals(item.getAsJsonObject().get("name").getAsString())) matches.add(item.getAsJsonObject());
            assertEquals(2, matches.size());
            assertTrue(matches.getFirst().get("scopeTitle").getAsBoolean());
            assertFalse(matches.getFirst().get("scopeContent").getAsBoolean());
            assertEquals("excluded", matches.getFirst().get("excludeScope").getAsString());
            postJson("/reader3/deleteReplaceRule", first);
            saved = getJson("/reader3/getReplaceRules").getAsJsonObject().getAsJsonArray("data");
            long count = 0; for (JsonElement item : saved) if (name.equals(item.getAsJsonObject().get("name").getAsString())) count++;
            assertEquals(1, count);
        } finally { postJson("/reader3/deleteReplaceRules", batch); }
    }

    @Test
    public void highlightRangeAndStyleRoundTripWithoutChangingPositionBookmarks() throws Exception {
        String name = "highlight-regression-" + UUID.randomUUID();
        JsonObject range = bookmark(name, "fixture", 78103, "annotation");
        range.addProperty("bookmarkType", 1);
        range.addProperty("chapterIndex", 2);
        range.addProperty("chapterPos", 7);
        range.addProperty("endChapterIndex", 4);
        range.addProperty("endChapterPos", 9);
        range.addProperty("highlightStyle", 2);
        range.addProperty("highlightColor", -32885);
        try {
            postJson("/reader3/saveBookmark", range);
            JsonObject saved = bookmarksFor(name, "fixture").getFirst();
            assertEquals(1, saved.get("bookmarkType").getAsInt());
            assertEquals(4, saved.get("endChapterIndex").getAsInt());
            assertEquals(9, saved.get("endChapterPos").getAsInt());
            assertEquals(2, saved.get("highlightStyle").getAsInt());
        } finally { postJson("/reader3/deleteBookmark", range); }
    }

    private JsonObject bookmark(String bookName, String author, long time, String content) {
        JsonObject bookmark = new JsonObject();
        bookmark.addProperty("bookName", bookName);
        bookmark.addProperty("bookAuthor", author);
        bookmark.addProperty("time", time);
        bookmark.addProperty("content", content);
        return bookmark;
    }

    private List<JsonObject> bookmarksFor(String bookName, String author) throws Exception {
        JsonArray data = getJson("/reader3/getBookmarks").getAsJsonObject().getAsJsonArray("data");
        List<JsonObject> matches = new ArrayList<>();
        for (JsonElement element : data) {
            JsonObject value = element.getAsJsonObject();
            if (bookName.equals(value.get("bookName").getAsString()) && author.equals(value.get("bookAuthor").getAsString())) {
                matches.add(value);
            }
        }
        matches.sort(Comparator.comparingLong(value -> value.get("time").getAsLong()));
        return matches;
    }

    private JsonObject postJson(String path, JsonElement body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(connection(path).getURL().toURI())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(result.get("isSuccess").getAsBoolean(), response.body());
        return result;
    }

    private HttpURLConnection connection(String path) throws Exception {
        int port = environment.getRequiredProperty("reader.server.port", Integer.class);
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(3000);
        return connection;
    }

    private JsonElement getJson(String path) throws Exception {
        HttpURLConnection connection = connection(path);
        try {
            assertEquals(200, connection.getResponseCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                return JsonParser.parseString(reader.lines().collect(Collectors.joining()));
            }
        } finally {
            connection.disconnect();
        }
    }

    public static class ServerPortInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try (ServerSocket socket = new ServerSocket(0)) {
                TestPropertyValues.of("reader.server.port=" + socket.getLocalPort()).applyTo(context);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot allocate a test port", e);
            }
        }
    }

}
