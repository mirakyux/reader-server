package com.htmake.reader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.htmake.reader.config.AppConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReaderApplication.class, properties = {
        "reader.app.storagePath=build/test-storage", "reader.app.secure=false", "reader.app.secureKey="
})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
class BrowserBridgeIntegrationTests {
    @Autowired private AppConfig config;
    @Autowired private org.springframework.core.env.Environment environment;

    @BeforeEach void waitForServer() throws Exception {
        int port = environment.getRequiredProperty("reader.server.port", Integer.class);
        for (int attempt = 0; attempt < 100; attempt++) {
            try { HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(), HttpResponse.BodyHandlers.discarding()); return; }
            catch (java.net.ConnectException error) { if (attempt == 99) throw error; Thread.sleep(100); }
        }
    }

    @Test void scriptBrowserBridgeRetainsHeadersCookiesAndReturnedContent() throws Exception {
        HttpServer browser = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        browser.createContext("/execute", exchange -> {
            assertEquals("Bearer fixture-private-token", exchange.getRequestHeaders().getFirst("Authorization"));
            captured.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            String result = "{\"body\":\"动态页面正文\",\"url\":\"https://browser-fixture.example/book\",\"storageState\":{\"cookies\":[{\"name\":\"session\",\"value\":\"from-browser\",\"domain\":\"browser-fixture.example\",\"path\":\"/\",\"expires\":2000000000,\"httpOnly\":true,\"secure\":true}],\"origins\":[]}}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        browser.start();
        String previousUrl = config.getBrowserUrl(), previousToken = config.getBrowserToken();
        config.setBrowserUrl("http://127.0.0.1:" + browser.getAddress().getPort()); config.setBrowserToken("fixture-private-token");
        String sourceUrl = "https://browser-fixture.example";
        JsonObject source = new JsonObject();
        source.addProperty("bookSourceUrl", sourceUrl); source.addProperty("bookSourceName", "Browser " + UUID.randomUUID());
        source.addProperty("enabledCookieJar", true); source.addProperty("header", "{\"X-Fixture\":\"present\"}");
        source.addProperty("mainJs", "var config={bookSourceUrl:'" + sourceUrl + "',bookSourceName:'Browser'};function search(key,page){return [{name:java.webView(null,'" + sourceUrl + "/book','document.body.textContent'),author:'fixture',bookUrl:'/book'}];}function getChapters(book){return [{title:'Chapter',url:'/chapter'}];}function getContent(chapter,book,next){return 'content';}");
        try {
            call("saveBookSource", source);
            JsonObject search = new JsonObject(); search.addProperty("bookSourceUrl", sourceUrl); search.addProperty("key", "fixture");
            var data = call("searchBook", search).getAsJsonArray("data");
            assertEquals("动态页面正文", data.get(0).getAsJsonObject().get("name").getAsString());
            assertEquals("document.body.textContent", captured.get().get("js").getAsString());
            assertEquals("present", captured.get().getAsJsonObject("headers").get("X-Fixture").getAsString());
            JsonObject request = new JsonObject(); request.addProperty("bookSourceUrl", sourceUrl);
            assertTrue(call("getBookSourceCookies", request).getAsJsonObject("data").get("cookie").getAsString().contains("session=from-browser"));
        } finally {
            config.setBrowserUrl(previousUrl); config.setBrowserToken(previousToken);
            call("deleteBookSource", source); browser.stop(0);
        }
    }

    @Test void webJsRulePassesHtmlAndPriorResultToBrowser() throws Exception {
        HttpServer browser = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        browser.createContext("/execute", exchange -> {
            captured.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] bytes = "{\"body\":\"DOM result\",\"url\":\"https://webjs.example/chapter\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); browser.start();
        String previousUrl = config.getBrowserUrl(), previousToken = config.getBrowserToken();
        config.setBrowserUrl("http://127.0.0.1:" + browser.getAddress().getPort()); config.setBrowserToken("fixture-private-token");
        try {
            var source = new io.legado.app.data.entities.BookSource(); source.setBookSourceUrl("https://webjs.example");
            source.setRuntimeUserNamespace(UUID.randomUUID().toString());
            var rule = new io.legado.app.model.analyzeRule.AnalyzeRule(new io.legado.app.data.entities.Book(), source).setContent("<main>prior result</main>", "https://webjs.example/chapter");
            assertEquals("DOM result", rule.getString("@css:main@text@webjs:result + document.title"));
            assertTrue(captured.get().get("js").getAsString().contains("var result = \"prior result\""));
            assertTrue(captured.get().get("html").getAsString().contains("<main>prior result</main>"));
        } finally { config.setBrowserUrl(previousUrl); config.setBrowserToken(previousToken); browser.stop(0); }
    }

    @Test void rssAndBookBrowserSessionsKeepSameUrlCookiesAndStateSeparate() throws Exception {
        String host = "browser-" + UUID.randomUUID() + ".example", sourceUrl = "https://" + host;
        var sessions = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var captured = new java.util.concurrent.CopyOnWriteArrayList<JsonObject>();
        HttpServer browser = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        browser.createContext("/", exchange -> {
            try {
                JsonObject input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                String id, scope;
                if (exchange.getRequestURI().getPath().equals("/session")) {
                    var headers = input.getAsJsonObject("headers");
                    if (headers == null || !headers.has("X-Scope")) throw new IllegalArgumentException("Browser session is missing X-Scope: " + input);
                    captured.add(input); id = UUID.randomUUID().toString(); scope = headers.get("X-Scope").getAsString(); sessions.put(id, scope);
                } else { id = input.get("id").getAsString(); scope = sessions.get(id); }
                String result = "{\"id\":\"" + id + "\",\"url\":\"" + sourceUrl + "/" + scope + "\",\"title\":\"" + scope + "\",\"screenshot\":\"fixture\",\"width\":1024,\"height\":768,\"body\":\"saved\",\"storageState\":{\"cookies\":[{\"name\":\"scope\",\"value\":\"" + scope + "\",\"domain\":\"" + host + "\",\"path\":\"/\",\"expires\":2000000000,\"secure\":true}],\"origins\":[]}}";
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
                JsonObject result = new JsonObject(); result.addProperty("error", failure.toString());
                byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(500, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        }); browser.start();
        String previousUrl = config.getBrowserUrl(), previousToken = config.getBrowserToken();
        config.setBrowserUrl("http://127.0.0.1:" + browser.getAddress().getPort()); config.setBrowserToken("fixture-private-token");
        JsonObject book = new JsonObject(); book.addProperty("bookSourceUrl", sourceUrl); book.addProperty("bookSourceName", "book"); book.addProperty("enabledCookieJar", true); book.addProperty("header", "{\"X-Scope\":\"book\"}");
        JsonObject rss = new JsonObject(); rss.addProperty("sourceUrl", sourceUrl); rss.addProperty("sourceName", "rss"); rss.addProperty("header", "{\"X-Scope\":\"rss\"}");
        var bookModel = new io.legado.app.data.entities.BookSource(); bookModel.setBookSourceUrl(sourceUrl);
        var rssModel = new io.legado.app.data.entities.RssSource(); rssModel.setSourceUrl(sourceUrl); rssModel.setUserNamespace("default");
        try {
            call("saveBookSource", book); call("saveRssSource", rss);
            JsonObject open = new JsonObject(); open.addProperty("bookSourceUrl", sourceUrl);
            var bookInteraction = io.legado.app.help.source.SourceInteractions.INSTANCE.request(bookModel, "browser", sourceUrl + "/book", "book verification", null, false, false, false);
            var rssInteraction = io.legado.app.help.source.SourceInteractions.INSTANCE.request(rssModel, "browser", sourceUrl + "/rss", "rss verification", null, false, true, false);
            JsonObject rssDrain = new JsonObject(); rssDrain.addProperty("bookSourceUrl", sourceUrl); rssDrain.addProperty("sourceType", "rss");
            var rssActions = call("drainSourceInteractions", rssDrain).getAsJsonObject("data").getAsJsonArray("actions");
            assertEquals(1, rssActions.size()); assertEquals(rssInteraction.getId(), rssActions.get(0).getAsJsonObject().get("id").getAsString());
            assertEquals("rss", rssActions.get(0).getAsJsonObject().get("sourceType").getAsString());
            var bookActions = call("drainSourceInteractions", open).getAsJsonObject("data").getAsJsonArray("actions");
            assertEquals(1, bookActions.size()); assertEquals(bookInteraction.getId(), bookActions.get(0).getAsJsonObject().get("id").getAsString());
            assertEquals("book", bookActions.get(0).getAsJsonObject().get("sourceType").getAsString());
            JsonObject bookSession = call("openSourceBrowser", open).getAsJsonObject("data");
            assertEquals("book", bookSession.get("title").getAsString());
            JsonObject bookClose = new JsonObject(); bookClose.add("id", bookSession.get("id")); call("closeSourceBrowser", bookClose);
            open.addProperty("sourceType", "rss");
            open.addProperty("interactionId", rssInteraction.getId());
            JsonObject rssSession = call("openSourceBrowser", open).getAsJsonObject("data");
            assertEquals("rss", rssSession.get("title").getAsString());
            assertTrue(captured.get(1).getAsJsonArray("cookies").isEmpty());
            assertTrue(captured.get(1).get("storageState").isJsonNull());
            JsonObject wrongType = new JsonObject(); wrongType.add("id", rssSession.get("id")); wrongType.addProperty("action", "refresh"); wrongType.addProperty("sourceType", "book");
            assertFalse(rawCall("sourceBrowserAction", wrongType).get("isSuccess").getAsBoolean());
            JsonObject close = new JsonObject(); close.add("id", rssSession.get("id")); call("closeSourceBrowser", close);
            assertEquals("saved", io.legado.app.help.source.SourceInteractions.INSTANCE.await(rssModel, rssInteraction, 1000).getBody());
            assertEquals("scope=book", io.legado.app.help.http.CookieStore.INSTANCE.forSource(bookModel).getCookie(sourceUrl));
            assertEquals("scope=rss", io.legado.app.help.http.CookieStore.INSTANCE.forSource(rssModel).getCookie(sourceUrl));
            assertNotEquals(bookModel.getStorageNamespace(), rssModel.getStorageNamespace());
        } finally {
            io.legado.app.help.http.CookieStore.INSTANCE.forSource(bookModel).clear(); io.legado.app.help.http.CookieStore.INSTANCE.forSource(rssModel).clear();
            call("deleteBookSource", book); call("deleteRssSource", rss); config.setBrowserUrl(previousUrl); config.setBrowserToken(previousToken); browser.stop(0);
        }
    }
    private JsonObject call(String path, JsonObject body) throws Exception {
        var result = rawCall(path, body); assertTrue(result.get("isSuccess").getAsBoolean(), result.toString()); return result;
    }
    private JsonObject rawCall(String path, JsonObject body) throws Exception {
        int port = environment.getRequiredProperty("reader.server.port", Integer.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reader3/" + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var result = JsonParser.parseString(response.body()).getAsJsonObject();
        return result;
    }
}
