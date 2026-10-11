package com.htmake.reader;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReaderApplication.class, properties = {
    "reader.app.storagePath=build/test-storage", "reader.app.secure=false", "reader.app.secureKey="
})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
class FuReadingIntegrationTests {
    @Autowired Environment environment;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void ready() throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            try { assertEquals(200, get("/health").statusCode()); return; }
            catch (ConnectException failure) { if (System.nanoTime() >= deadline) throw failure; Thread.sleep(100); }
        }
    }

    @Test void progressIsPreciseAndPassiveDownloadsDoNotMoveIt() throws Exception {
        try (var book = new Fixture()) {
            long now = System.currentTimeMillis() + 100;
            var request = json("url", book.url, "index", 1, "chapterPos", 7, "time", now);
            var saved = post("saveBookProgress", request).getAsJsonObject("data");
            assertEquals(7, saved.get("durChapterPos").getAsInt());
            success(get("/reader3/getBookContent?url=" + encode(book.url) + "&index=0&cache=1"));
            success(get("/reader3/getBookContentDetails?url=" + encode(book.url) + "&index=0"));
            var stale = post("saveBookProgress", json("url", book.url, "index", 0, "chapterPos", 1, "time", now - 1000)).getAsJsonObject("data");
            assertEquals(1, stale.get("durChapterIndex").getAsInt());
            assertEquals(7, stale.get("durChapterPos").getAsInt());
            var rewind = post("saveBookProgress", json("url", book.url, "index", 0, "chapterPos", 3, "time", now + 1)).getAsJsonObject("data");
            assertEquals(0, rewind.get("durChapterIndex").getAsInt());
            assertEquals(3, rewind.get("durChapterPos").getAsInt());
            var invalid = envelope(send("/reader3/saveBookProgress", json("url", book.url, "index", -1)));
            assertFalse(invalid.get("isSuccess").getAsBoolean());
        }
    }

    @Test void editedContentAndTitleSurviveReloadAndExportAndRestoreOriginal() throws Exception {
        try (var book = new Fixture()) {
            var original = post("getChapterEdit", json("url", book.url, "index", 0)).getAsJsonObject("data").get("content").getAsString();
            post("saveChapterEdit", json("url", book.url, "index", 0, "title", "手动修正标题", "content", "编辑后的独立正文。"));
            var details = success(get("/reader3/getBookContentDetails?url=" + encode(book.url) + "&index=0")).getAsJsonObject("data");
            assertEquals("编辑后的独立正文。", details.get("content").getAsString());
            assertEquals("手动修正标题", details.getAsJsonObject("chapter").get("title").getAsString());
            var chapters = success(get("/reader3/getChapterList?url=" + encode(book.url))).getAsJsonArray("data");
            assertEquals("手动修正标题", chapters.get(0).getAsJsonObject().get("title").getAsString());
            var refreshed = get("/reader3/cacheBookSSE?url=" + encode(book.url) + "&refresh=1&concurrentCount=1");
            assertTrue(refreshed.body().contains("\"complete\":true"), refreshed.body());
            var controller = new com.htmake.reader.api.controller.BookController(kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            var savedBook = new io.vertx.core.json.JsonObject(book.book.toString()).mapTo(io.legado.app.data.entities.Book.class);
            var directory = controller.getChapterCacheDir(savedBook, "default");
            assertEquals("编辑后的独立正文。", java.nio.file.Files.readString(directory.toPath().resolve("0.txt")));
            post("deleteBookCache", json("url", book.url));
            assertEquals("编辑后的独立正文。", post("getChapterEdit", json("url", book.url, "index", 0)).getAsJsonObject("data").get("content").getAsString());
            success(get("/reader3/getBookContentDetails?url=" + encode(book.url) + "&index=0"));
            success(get("/reader3/getBookContentDetails?url=" + encode(book.url) + "&index=1&cache=1"));
            var exported = get("/reader3/exportBook?url=" + encode(book.url) + "&isEpub=0");
            assertEquals(200, exported.statusCode());
            assertTrue(exported.body().contains("手动修正标题"));
            assertTrue(exported.body().contains("编辑后的独立正文。"));
            assertEquals(original, post("restoreChapterEdit", json("url", book.url, "index", 0)).getAsJsonObject("data").get("content").getAsString());
            assertEquals(original, success(get("/reader3/getBookContentDetails?url=" + encode(book.url) + "&index=0")).getAsJsonObject("data").get("content").getAsString());
        }
    }

    @Test void delayedBookInfoSaveMergesLatestShelfAndReadingProgress() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                byte[] text = "ready".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, text.length);
                exchange.getResponseBody().write(text);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var first = new Fixture()) {
            // Prime the catalog before delaying only the book-info branch.
            post("getChapterList", json("url", first.url));
            var updated = first.source.deepCopy();
            updated.addProperty("mainJs", first.source.get("mainJs").getAsString() +
                "\nfunction getBookInfo(book){java.ajax('http://127.0.0.1:" + server.getAddress().getPort() +
                "/slow');return {name:'" + first.book.get("name").getAsString() + "',author:'fixture',tocUrl:book.bookUrl};}");
            post("saveBookSource", updated);
            var delayed = first.book.deepCopy();
            delayed.remove("tocUrl");
            var response = client.sendAsync(HttpRequest.newBuilder(uri("/reader3/saveBook"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(delayed.toString())).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try (var second = new Fixture()) {
                post("saveBookProgress", json("url", first.url, "index", 1, "chapterPos", 17, "time", System.currentTimeMillis() + 100));
                release.countDown();
                success(response.get(10, java.util.concurrent.TimeUnit.SECONDS));
                var saved = success(get("/reader3/getShelfBook?url=" + encode(first.url))).getAsJsonObject("data");
                assertEquals(1, saved.get("durChapterIndex").getAsInt());
                assertEquals(17, saved.get("durChapterPos").getAsInt());
                assertEquals(second.url, success(get("/reader3/getShelfBook?url=" + encode(second.url))).getAsJsonObject("data").get("bookUrl").getAsString());
            }
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void fuProgressWithEmptyAuthorMatchesTheExistingBook() throws Exception {
        try (var book = new Fixture("")) {
            var controller = new com.htmake.reader.api.controller.BookController(kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            var incoming = new io.legado.app.data.entities.Book();
            incoming.setName(book.book.get("name").getAsString());
            incoming.setAuthor("");
            controller.editShelfBook(incoming, "default", saved -> { saved.setDurChapterIndex(1); saved.setDurChapterPos(11); return saved; });
            var saved = success(get("/reader3/getShelfBook?url=" + encode(book.url))).getAsJsonObject("data");
            assertEquals(1, saved.get("durChapterIndex").getAsInt());
            assertEquals(11, saved.get("durChapterPos").getAsInt());
        }
    }

    @Test void dictionaryUsesFuUrlAndShowRulesAndSupportsImportDelete() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dict", exchange -> {
            String word = URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring(5), StandardCharsets.UTF_8);
            byte[] bytes = ("<html><div class='definition'>释义：" + word + "</div><div>忽略</div></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        String name = "dict-" + UUID.randomUUID();
        try {
            var saved = post("saveDictRule", json("name", name, "urlRule", "http://127.0.0.1:" + server.getAddress().getPort() + "/dict?word={{key}}", "showRule", ".definition@text")).getAsJsonArray("data");
            assertTrue(saved.asList().stream().anyMatch(value -> value.getAsJsonObject().has("name") && value.getAsJsonObject().get("name").getAsString().equals(name)));
            assertTrue(success(get("/reader3/getDictRules")).getAsJsonArray("data").asList().stream().anyMatch(value -> value.getAsJsonObject().get("name").getAsString().equals(name)));
            var results = post("lookupDictionary", json("word", "测试 词", "ruleId", name)).getAsJsonObject("data").getAsJsonArray("results");
            assertEquals("释义：测试 词", results.get(0).getAsJsonObject().get("content").getAsString());
        } finally { post("deleteDictRule", json("name", name)); server.stop(0); }
    }

    @Test void alternateChapterAndWholeBookSwitchMatchTitlesAcrossInsertedVolumes() throws Exception {
        try (var original = new Fixture()) {
            String sourceUrl = "https://alternate-source/" + UUID.randomUUID();
            String newUrl = "https://alternate-book/" + UUID.randomUUID();
            String script = "function search(key,page){return [];}\n" +
                "function getBookInfo(book){return {name:'" + original.book.get("name").getAsString() + "',author:'fixture',bookUrl:book.bookUrl,tocUrl:book.bookUrl,origin:'" + sourceUrl + "'};}\n" +
                "function getChapters(book){return [{title:'第一卷',url:book.bookUrl+'/volume',isVolume:true},{title:'第1章 初见',url:book.bookUrl+'/0'},{title:'第2章 重逢',url:book.bookUrl+'/1'}];}\n" +
                "function getContent(chapter,book){return '备用书源：'+chapter.title;}";
            var source = json("bookSourceUrl", sourceUrl, "bookSourceName", "alternate fixture", "mainJs", script, "enabled", true);
            post("saveBookSource", source);
            try {
                post("saveBookProgress", json("url", original.url, "index", 1, "chapterPos", 8, "time", System.currentTimeMillis()));
                var alternate = post("getAlternateChapter", json("url", original.url, "index", 1, "alternateBookUrl", newUrl, "bookSourceUrl", sourceUrl)).getAsJsonObject("data");
                assertEquals("备用书源：第2章 重逢", alternate.get("content").getAsString());
                assertEquals(2, alternate.getAsJsonObject("chapter").get("index").getAsInt());
                var shelf = success(get("/reader3/getShelfBook?url=" + encode(original.url))).getAsJsonObject("data");
                assertEquals(original.sourceUrl, shelf.get("origin").getAsString());
                assertEquals(1, shelf.get("durChapterIndex").getAsInt());
                var switched = post("setBookSource", json("bookUrl", original.url, "newUrl", newUrl, "bookSourceUrl", sourceUrl)).getAsJsonObject("data");
                original.book.addProperty("bookUrl", newUrl);
                assertEquals(sourceUrl, switched.get("origin").getAsString());
                assertEquals(2, switched.get("durChapterIndex").getAsInt());
                assertEquals(0, switched.get("durChapterPos").getAsInt());
                assertEquals(3, switched.get("totalChapterNum").getAsInt());
            } finally {
                post("deleteBookSource", source);
            }
        }
    }

    @Test void alternateSourceCacheKeepsSharedUrlsAndUpdatesOnlyTheSameSourceCandidate() throws Exception {
        try (var original = new Fixture()) {
            var controller = new com.htmake.reader.api.controller.BookController(kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            var book = new io.vertx.core.json.JsonObject(original.book.toString()).mapTo(io.legado.app.data.entities.Book.class);
            var alternate = book.toSearchBook();
            alternate.setOrigin("https://alternate-source/" + original.id);
            alternate.setOriginName("备用规则");
            alternate.setLatestChapterTitle("旧章节");
            controller.saveBookSources(book, java.util.List.of(alternate), "default", false);

            var differentUrl = book.toSearchBook();
            differentUrl.setBookUrl(original.url + "/another-edition");
            controller.saveBookSources(book, java.util.List.of(differentUrl), "default", false);
            alternate.setLatestChapterTitle("最新章节");
            controller.saveBookSources(book, java.util.List.of(alternate), "default", false);

            var candidates = post("getAvailableBookSource", json("url", original.url)).getAsJsonArray("data");
            assertEquals(3, candidates.size(), "相同 URL 不同书源规则以及同一书源不同 URL 都应保留");
            var originalCandidates = candidates.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(candidate -> candidate.get("origin").getAsString().equals(original.sourceUrl)).toList();
            assertEquals(2, originalCandidates.size());
            assertTrue(originalCandidates.stream().anyMatch(candidate -> candidate.get("bookUrl").getAsString().equals(original.url)));
            assertTrue(originalCandidates.stream().anyMatch(candidate -> candidate.get("bookUrl").getAsString().equals(differentUrl.getBookUrl())));
            var alternateCandidates = candidates.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(candidate -> candidate.get("origin").getAsString().equals(alternate.getOrigin())).toList();
            assertEquals(1, alternateCandidates.size(), "同一 origin 和 URL 的更新不能产生重复候选");
            assertEquals("最新章节", alternateCandidates.getFirst().get("latestChapterTitle").getAsString());
        }
    }

    @Test void sharedUrlWholeBookSwitchUsesTheNewSourceBodyAndKeepsTheOriginalCache() throws Exception {
        try (var original = new Fixture()) {
            var alternateSource = sharedUrlAlternateSource(original);
            post("saveBookSource", alternateSource);
            try {
                post("getBookInfo", json("url", original.url, "bookSourceUrl", original.sourceUrl));
                var first = post("getBookContentDetails", json("url", original.url, "index", 0, "cache", 1)).getAsJsonObject("data");
                var second = post("getBookContentDetails", json("url", original.url, "index", 1, "cache", 1)).getAsJsonObject("data");
                assertTrue(second.get("content").getAsString().contains("原始正文第1章"));
                post("saveBookProgress", json("url", original.url, "index", 1, "chapterPos", 8, "time", System.currentTimeMillis()));
                var temporary = post("getAlternateChapter", json("url", original.url, "index", 1, "alternateBookUrl", original.url,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString())).getAsJsonObject("data");
                assertEquals("备用书源：第2章 重逢", temporary.get("content").getAsString());
                assertEquals(2, temporary.getAsJsonObject("chapter").get("index").getAsInt());

                var switched = post("setBookSource", json("bookUrl", original.url, "newUrl", original.url,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString())).getAsJsonObject("data");
                assertEquals(2, switched.get("durChapterIndex").getAsInt());
                assertEquals(3, switched.get("totalChapterNum").getAsInt());
                var explicit = post("getBookContentDetails", json("url", original.url, "index", 2, "cache", 1,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString())).getAsJsonObject("data");
                assertEquals("备用书源：第2章 重逢", explicit.get("content").getAsString(), "共享章节 URL 也不能复用旧书源正文");
                post("deleteBookCache", json("url", original.url));
                var current = post("getBookContentDetails", json("url", original.url, "index", 2, "cache", 1)).getAsJsonObject("data");
                assertEquals("备用书源：第2章 重逢", current.get("content").getAsString(), "旧书籍信息缓存不能覆盖书架上的当前源");
                assertEquals(3, post("getChapterList", json("url", original.url)).getAsJsonArray("data").size());

                post("setBookSource", json("bookUrl", original.url, "newUrl", original.url, "bookSourceUrl", original.sourceUrl));
                var originalFirst = post("getBookContentDetails", json("url", original.url, "index", 0, "cache", 1)).getAsJsonObject("data");
                assertEquals(first.get("content"), originalFirst.get("content"));
                assertEquals(2, post("getChapterList", json("url", original.url)).getAsJsonArray("data").size());
            } finally { post("deleteBookSource", alternateSource); }
        }
    }

    @Test void sharedUrlSourcesKeepEditsAndExportsSeparateAndClearingOneKeepsTheOther() throws Exception {
        try (var original = new Fixture()) {
            var alternateSource = sharedUrlAlternateSource(original);
            post("saveBookSource", alternateSource);
            try {
                post("saveChapterEdit", json("url", original.url, "index", 1, "title", "仅属于A的标题", "content", "仅属于A的手动正文"));
                post("setBookSource", json("bookUrl", original.url, "newUrl", original.url,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString()));
                var details = post("getBookContentDetails", json("url", original.url, "index", 2, "cache", 1,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString())).getAsJsonObject("data");
                assertEquals("备用书源：第2章 重逢", details.get("content").getAsString(), "A 的编辑不应进入 B 的正文");
                assertFalse(details.has("edited") && details.get("edited").getAsBoolean());
                var cache = get("/reader3/cacheBookSSE?url=" + encode(original.url) + "&concurrentCount=1");
                assertTrue(cache.body().contains("\"complete\":true"), cache.body());
                var exported = get("/reader3/exportBook?url=" + encode(original.url) + "&isEpub=0");
                assertEquals(200, exported.statusCode());
                assertTrue(exported.body().contains("备用书源：第1章 初见"));
                assertTrue(exported.body().contains("备用书源：第2章 重逢"));
                assertFalse(exported.body().contains("原始正文"));
                assertFalse(exported.body().contains("仅属于A"));
                var epub = client.send(HttpRequest.newBuilder(uri("/reader3/exportBook?url=" + encode(original.url) + "&isEpub=1"))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, epub.statusCode());
                var html = new StringBuilder();
                try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(epub.body()))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.getName().endsWith(".html") || entry.getName().endsWith(".xhtml"))
                            html.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                assertTrue(html.toString().contains("备用书源：第2章 重逢"));
                assertFalse(html.toString().contains("原始正文"));
                assertFalse(html.toString().contains("仅属于A"));

                post("setBookSource", json("bookUrl", original.url, "newUrl", original.url, "bookSourceUrl", original.sourceUrl));
                var restoredEdit = post("getChapterEdit", json("url", original.url, "index", 1)).getAsJsonObject("data");
                assertTrue(restoredEdit.get("edited").getAsBoolean());
                assertEquals("仅属于A的手动正文", restoredEdit.get("content").getAsString());
                post("deleteBookCache", json("url", original.url));
                assertEquals("仅属于A的手动正文", post("getChapterEdit", json("url", original.url, "index", 1)).getAsJsonObject("data").get("content").getAsString());
                post("setBookSource", json("bookUrl", original.url, "newUrl", original.url,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString()));
                var bookshelf = success(get("/reader3/getShelfBookWithCacheInfo")).getAsJsonArray("data");
                var saved = bookshelf.asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(value -> value.get("bookUrl").getAsString().equals(original.url)).findFirst().orElseThrow();
                assertEquals(3, saved.get("cachedChapterCount").getAsInt(), "清 A 的缓存不能删除 B 已缓存的章节");
            } finally { post("deleteBookSource", alternateSource); }
        }
    }

    @Test void sharedUrlUpgradeDoesNotAssignUnmarkedLegacyBodiesToTheCurrentSource() throws Exception {
        try (var original = new Fixture()) {
            var alternateSource = sharedUrlAlternateSource(original);
            post("saveBookSource", alternateSource);
            try {
                var old = post("getBookContentDetails", json("url", original.url, "index", 1, "cache", 1)).getAsJsonObject("data");
                var controller = new com.htmake.reader.api.controller.BookController(kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
                var book = new io.vertx.core.json.JsonObject(original.book.toString()).mapTo(io.legado.app.data.entities.Book.class);
                var legacy = controller.getChapterCacheDir(book, "default").toPath();
                java.nio.file.Files.delete(legacy.resolve(".source.json"));
                // Simulate upgrading an old deployment whose shelf switched to B but cache still contains A.
                controller.editShelfBook(book, "default", saved -> {
                    saved.setOrigin(alternateSource.get("bookSourceUrl").getAsString());
                    return saved;
                });
                assertEquals(3, post("getChapterList", json("url", original.url, "refresh", 1,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString())).getAsJsonArray("data").size());
                var current = post("getBookContentDetails", json("url", original.url, "index", 2, "cache", 1,
                    "bookSourceUrl", alternateSource.get("bookSourceUrl").getAsString())).getAsJsonObject("data");
                assertEquals("备用书源：第2章 重逢", current.get("content").getAsString(), "无归属的旧缓存不能绑定到当前 B 源");
                assertEquals(old.get("content").getAsString(), java.nio.file.Files.readString(legacy.resolve("1.txt")), "旧文件应保留供恢复");
            } finally { post("deleteBookSource", alternateSource); }
        }
    }

    @Test void replacingAlternateSourceCachePreservesSourceIdentityAndDeduplicatesUpdates() throws Exception {
        try (var original = new Fixture()) {
            var controller = new com.htmake.reader.api.controller.BookController(kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            var book = new io.vertx.core.json.JsonObject(original.book.toString()).mapTo(io.legado.app.data.entities.Book.class);
            var alternate = book.toSearchBook();
            alternate.setOrigin("https://alternate-source/" + original.id);
            alternate.setLatestChapterTitle("旧章节");
            var updated = book.toSearchBook();
            updated.setOrigin(alternate.getOrigin());
            updated.setLatestChapterTitle("新章节");
            controller.saveBookSources(book, java.util.List.of(alternate, book.toSearchBook(), updated), "default", true);

            var candidates = post("getAvailableBookSource", json("url", original.url)).getAsJsonArray("data");
            assertEquals(2, candidates.size(), "刷新候选缓存应按 origin 和 URL 去重");
            var origins = candidates.asList().stream().map(JsonElement::getAsJsonObject)
                .map(candidate -> candidate.get("origin").getAsString()).toList();
            assertTrue(origins.contains(original.sourceUrl));
            assertTrue(origins.contains(alternate.getOrigin()));
            var savedAlternate = candidates.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(candidate -> candidate.get("origin").getAsString().equals(alternate.getOrigin())).findFirst().orElseThrow();
            assertEquals("新章节", savedAlternate.get("latestChapterTitle").getAsString());
        }
    }

    @Test void videoSubtitleUrlsUseTheBookSourceHeadersAndChapterMetadata() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/captions", exchange -> {
            boolean authorized = "subtitle-token".equals(exchange.getRequestHeaders().getFirst("X-Source-Token"));
            byte[] bytes = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n视频字幕".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authorized ? 200 : 403, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var book = new Fixture()) {
            String captions = "http://127.0.0.1:" + server.getAddress().getPort() + "/captions";
            book.source.addProperty("header", "{\"X-Source-Token\":\"subtitle-token\"}");
            post("saveBookSource", book.source);
            success(get("/reader3/getBookContentDetails?url=" + encode(book.url) + "&index=0"));
            var controller = new com.htmake.reader.api.controller.BookController(kotlin.coroutines.EmptyCoroutineContext.INSTANCE);
            var savedBook = new io.vertx.core.json.JsonObject(book.book.toString()).mapTo(io.legado.app.data.entities.Book.class);
            var chapter = new io.legado.app.data.entities.BookChapter(); chapter.setIndex(0); chapter.setUrl(book.url + "/0"); chapter.putLyric(captions);
            com.htmake.reader.api.service.ChapterMetadata.INSTANCE.save(controller.getChapterCacheDir(savedBook, "default"), chapter);
            assertTrue(post("getChapterMediaText", json("url", book.url, "index", 0, "field", "lyric")).get("data").getAsString().contains("视频字幕"));
            var invalid = envelope(send("/reader3/getChapterMediaText", json("url", book.url, "index", 0, "field", "private")));
            assertFalse(invalid.get("isSuccess").getAsBoolean());
        } finally { server.stop(0); }
    }

    private class Fixture implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final String url = "https://reading-fixture/" + id;
        final String sourceUrl = "https://reading-source/" + id;
        final JsonObject source;
        final JsonObject book;
        Fixture() throws Exception { this("fixture"); }
        Fixture(String author) throws Exception {
            String script = "function search(key,page){return [];}\nfunction getBookInfo(book){return {name:'阅读联测" + id + "',author:'" + author + "',bookUrl:book.bookUrl,tocUrl:book.bookUrl,origin:'" + sourceUrl + "'};}\n" +
                "function getChapters(book){return [{title:'第一章 初见',url:book.bookUrl+'/0',index:0},{title:'第二章 重逢',url:book.bookUrl+'/1',index:1}];}\n" +
                "function getContent(chapter,book){return '原始正文第'+chapter.index+'章。\\n逐章完整内容。';}";
            source = json("bookSourceUrl", sourceUrl, "bookSourceName", "reading fixture", "mainJs", script, "enabled", true);
            post("saveBookSource", source);
            book = json("name", "阅读联测" + id, "author", author, "bookUrl", url, "tocUrl", url, "origin", sourceUrl, "originName", "reading fixture");
            post("saveBook", book);
        }
        public void close() throws Exception {
            post("deleteBook", book);
            post("deleteBookSource", source);
        }
    }

    private static JsonObject sharedUrlAlternateSource(Fixture original) {
        String sourceUrl = "https://alternate-source/" + original.id;
        String script = "function search(key,page){return [];}\n" +
            "function getBookInfo(book){return {name:'" + original.book.get("name").getAsString() + "',author:'fixture',bookUrl:book.bookUrl,tocUrl:book.bookUrl,origin:'" + sourceUrl + "'};}\n" +
            "function getChapters(book){return [{title:'序章 前言',url:book.bookUrl+'/prologue'},{title:'第1章 初见',url:book.bookUrl+'/0'},{title:'第2章 重逢',url:book.bookUrl+'/1'}];}\n" +
            "function getContent(chapter,book){return '备用书源：'+chapter.title;}";
        return json("bookSourceUrl", sourceUrl, "bookSourceName", "alternate fixture", "mainJs", script, "enabled", true);
    }

    private JsonObject post(String action, JsonObject body) throws Exception { return success(send("/reader3/" + action, body)); }
    private HttpResponse<String> send(String path, JsonObject body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("reader.server.port", Integer.class) + path); }
    private JsonObject envelope(HttpResponse<String> response) { assertEquals(200, response.statusCode(), response.body()); return JsonParser.parseString(response.body()).getAsJsonObject(); }
    private JsonObject success(HttpResponse<String> response) { var result = envelope(response); assertTrue(result.get("isSuccess").getAsBoolean(), response.body()); return result; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static JsonObject json(Object... entries) { var value = new JsonObject(); for (int i=0; i<entries.length; i+=2) value.add(entries[i].toString(), new Gson().toJsonTree(entries[i+1])); return value; }
}
