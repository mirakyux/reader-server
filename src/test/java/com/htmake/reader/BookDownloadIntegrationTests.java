package com.htmake.reader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.htmake.reader.api.controller.BookController;
import com.htmake.reader.api.service.BackupArchive;
import com.htmake.reader.utils.VertExtKt;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.legado.app.data.entities.Book;
import kotlin.coroutines.EmptyCoroutineContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// Reuse the existing test context: the production Vert.x instance is a lazy singleton.
@SpringBootTest(classes = ReaderApplication.class, properties = {
        "reader.app.storagePath=build/test-storage",
        "reader.app.secure=false",
        "reader.app.secureKey="
})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
class BookDownloadIntegrationTests {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Autowired
    private Environment environment;

    @BeforeEach
    void waitForServer() throws Exception {
        assertEquals("build/test-storage", environment.getRequiredProperty("reader.app.storagePath"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                var response = get("/health");
                assertEquals(200, response.statusCode());
                assertEquals("ok!", JsonParser.parseString(text(response)).getAsString());
                return;
            } catch (ConnectException error) {
                if (System.nanoTime() >= deadline) throw error;
                Thread.sleep(100);
            }
        }
    }

    @Test
    void sourceToCompleteDownloadRetriesOnlyMissingChaptersAndExportsAllChineseContent() throws Exception {
        try (var fixture = new BookFixture()) {
            JsonObject source = null;
            JsonObject savedBook = null;
            try {
                var input = new JsonObject();
                input.addProperty("content", fixture.script());
                var parsed = postJson("/reader3/parseBookSource", input).getAsJsonArray("data");
                assertEquals(1, parsed.size());
                source = parsed.get(0).getAsJsonObject();
                assertEquals(fixture.script(), source.get("mainJs").getAsString());
                postJson("/reader3/saveBookSource", source);

                var search = new JsonObject();
                search.addProperty("key", fixture.name);
                search.addProperty("bookSourceUrl", fixture.base);
                var results = postJson("/reader3/searchBook", search).getAsJsonArray("data");
                assertEquals(1, results.size());
                var book = results.get(0).getAsJsonObject();
                assertEquals(fixture.name, book.get("name").getAsString());
                assertEquals(fixture.base, book.get("origin").getAsString());
                var detailRequest = new JsonObject();
                detailRequest.addProperty("url", book.get("bookUrl").getAsString());
                detailRequest.addProperty("bookSourceUrl", fixture.base);
                book = postJson("/reader3/getBookInfo", detailRequest).getAsJsonObject("data");
                assertEquals(fixture.name, book.get("name").getAsString());
                savedBook = postJson("/reader3/saveBook", book).getAsJsonObject("data");
                String bookUrl = savedBook.get("bookUrl").getAsString();

                assertRepeatedGroupRemovalPreservesOtherGroups(savedBook);

                var chapters = successfulJson(get("/reader3/getChapterList?url=" + encode(bookUrl))).getAsJsonArray("data");
                assertEquals(3, chapters.size());
                for (int index = 0; index < 3; index++) {
                    assertEquals(index, chapters.get(index).getAsJsonObject().get("index").getAsInt());
                    assertEquals(fixture.titles.get(index), chapters.get(index).getAsJsonObject().get("title").getAsString());
                }
                assertExportRejected(bookUrl, 3);
                fixture.assertRequests(0, 0, 0);

                var firstEvents = cache(bookUrl);
                assertProgress(firstEvents.getLast().data(), 2, 2, 1, false);
                assertEquals(List.of(1), firstEvents.getLast().data().getAsJsonArray("failedChapters").asList()
                        .stream().map(JsonElement::getAsInt).toList());
                assertTrue(firstEvents.stream().anyMatch(event -> event.data().get("failedCount").getAsInt() == 1
                        && event.data().get("cachedCount").getAsInt() == 1), "The failed second chapter must not stop the third chapter");
                fixture.assertRequests(1, 1, 1);
                assertExportRejected(bookUrl, 1);
                fixture.assertRequests(1, 1, 1);

                var retryEvents = cache(bookUrl);
                assertProgress(retryEvents.getFirst().data(), 2, 0, 0, false);
                assertProgress(retryEvents.getLast().data(), 3, 1, 0, true);
                assertEquals(0, retryEvents.getLast().data().getAsJsonArray("failedChapters").size());
                fixture.assertRequests(1, 2, 1);

                var cachedEvents = cache(bookUrl);
                assertProgress(cachedEvents.getLast().data(), 3, 0, 0, true);
                fixture.assertRequests(1, 2, 1);

                var txt = get(exportPath(bookUrl, false));
                var details = successfulJson(get("/reader3/getBookContentDetails?url=" + encode(bookUrl) + "&index=0&cache=1")).getAsJsonObject("data");
                assertEquals(fixture.contents.getFirst(), details.get("content").getAsString());
                assertEquals(fixture.titles.getFirst(), details.getAsJsonObject("chapter").get("title").getAsString());
                assertDownloadHeaders(txt, fixture, "txt", "text/plain");
                String document = text(txt);
                assertTrue(document.contains(fixture.name));
                assertTrue(document.contains(fixture.author));
                for (int index = 0; index < 3; index++) {
                    assertTrue(document.contains(fixture.titles.get(index)), document);
                    assertTrue(document.contains(fixture.contents.get(index)), document);
                }
                assertTrue(document.indexOf(fixture.titles.get(0)) < document.indexOf(fixture.titles.get(1)));
                assertTrue(document.indexOf(fixture.titles.get(1)) < document.indexOf(fixture.titles.get(2)));

                var epub = get(exportPath(bookUrl, true));
                assertDownloadHeaders(epub, fixture, "epub", "application/epub+zip");
                assertCompleteEpub(epub.body(), fixture);

                fixture.assertRequests(1, 2, 1);
                assertReplacementExportsLeaveRawCacheIntact(bookUrl, fixture);
                assertVersionedBackupRestoresUserAndAdvancedConfiguration();
            } finally {
                try {
                    if (savedBook != null) postJson("/reader3/deleteBook", savedBook);
                } finally {
                    if (source != null) postJson("/reader3/deleteBookSource", source);
                }
            }
        }
    }

    @Test
    void emptyVolumeChaptersCountAsCachedAndExportWhileAnEmptyBodyCacheIsRetried() throws Exception {
        try (var fixture = new BookFixture(true)) {
            JsonObject source = null;
            JsonObject savedBook = null;
            try {
                var input = new JsonObject(); input.addProperty("content", fixture.script());
                source = postJson("/reader3/parseBookSource", input).getAsJsonArray("data").get(0).getAsJsonObject();
                postJson("/reader3/saveBookSource", source);
                var details = new JsonObject(); details.addProperty("url", fixture.base + "/book"); details.addProperty("bookSourceUrl", fixture.base);
                var book = postJson("/reader3/getBookInfo", details).getAsJsonObject("data");
                savedBook = postJson("/reader3/saveBook", book).getAsJsonObject("data");
                String bookUrl = savedBook.get("bookUrl").getAsString();
                var chapters = successfulJson(get("/reader3/getChapterList?url=" + encode(bookUrl))).getAsJsonArray("data");
                assertEquals(4, chapters.size()); assertTrue(chapters.get(0).getAsJsonObject().get("isVolume").getAsBoolean());

                var downloaded = cache(bookUrl).getLast().data();
                assertEquals(4, downloaded.get("totalCount").getAsInt());
                assertEquals(4, downloaded.get("cachedCount").getAsInt());
                assertTrue(downloaded.get("complete").getAsBoolean());
                assertCachedCount(bookUrl, 4);
                var volume = successfulJson(get("/reader3/getBookContentDetails?url=" + encode(bookUrl) + "&index=0&cache=1")).getAsJsonObject("data");
                assertEquals("", volume.get("content").getAsString());
                assertEquals(fixture.titles.getFirst(), volume.getAsJsonObject("chapter").get("title").getAsString());
                var reused = cache(bookUrl).getLast().data();
                assertTrue(reused.get("complete").getAsBoolean()); assertEquals(0, reused.get("successCount").getAsInt());

                var txt = get(exportPath(bookUrl, false));
                assertDownloadHeaders(txt, fixture, "txt", "text/plain");
                for (String title : fixture.titles) assertTrue(text(txt).contains(title), text(txt));
                for (String content : fixture.contents) assertTrue(text(txt).contains(content), text(txt));
                var epub = get(exportPath(bookUrl, true));
                assertDownloadHeaders(epub, fixture, "epub", "application/epub+zip");
                assertCompleteEpub(epub.body(), fixture);

                var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                var toc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(unzip(epub.body()).get("OEBPS/toc.ncx")));
                var points = toc.getElementsByTagNameNS("*", "navPoint");
                var volumePoint = java.util.stream.IntStream.range(0, points.getLength()).mapToObj(points::item)
                        .filter(node -> ((org.w3c.dom.Element) node).getElementsByTagNameNS("*", "content").item(0)
                                .getAttributes().getNamedItem("src").getNodeValue().equals("Text/chapter_0.html"))
                        .findFirst().orElseThrow();
                assertEquals(3, ((org.w3c.dom.Element) volumePoint).getElementsByTagNameNS("*", "navPoint").getLength(), "Chapters belong under their volume in the TOC");

                var controller = new BookController(EmptyCoroutineContext.INSTANCE);
                var saved = new io.vertx.core.json.JsonObject(savedBook.toString()).mapTo(Book.class);
                var directory = controller.getChapterCacheDir(saved, "default");
                Files.writeString(directory.toPath().resolve("1.txt"), " \n\t", StandardCharsets.UTF_8);
                assertCachedCount(bookUrl, 3);
                assertExportRejected(bookUrl, 1);
                var refreshed = successfulJson(get("/reader3/getBookContentDetails?url=" + encode(bookUrl) + "&index=1&cache=1")).getAsJsonObject("data");
                assertEquals(fixture.contents.get(1), refreshed.get("content").getAsString());
                assertEquals(2, fixture.requests.get(1), "A whitespace-only body cache must be fetched again");
                assertCachedCount(bookUrl, 4);
                assertTrue(cache(bookUrl).getLast().data().get("complete").getAsBoolean());
                assertCompleteEpub(get(exportPath(bookUrl, true)).body(), fixture);
                assertEquals(0, fixture.requests.get(0), "A volume marker must not request a body URL");
            } finally {
                try { if (savedBook != null) postJson("/reader3/deleteBook", savedBook); }
                finally { if (source != null) postJson("/reader3/deleteBookSource", source); }
            }
        }
    }

    private void assertCachedCount(String bookUrl, int expected) throws Exception {
        var shelf = successfulJson(get("/reader3/getShelfBookWithCacheInfo")).getAsJsonArray("data");
        var book = shelf.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> bookUrl.equals(item.get("bookUrl").getAsString())).findFirst().orElseThrow();
        assertEquals(expected, book.get("cachedChapterCount").getAsInt());
    }

    @Test
    void epubConvertsPngCoverDecodesAllInlineImagesAndRepairsMissingOldImageCache() throws Exception {
        assertEquals(Path.of("build/test-storage").toAbsolutePath().normalize(), Path.of(VertExtKt.getStoragePath()).toAbsolutePath().normalize());
        try (var fixture = new BookFixture(false, fixtureImage())) {
            FixtureBook saved = null;
            try {
                saved = saveFixtureBook(fixture, true);
                String bookUrl = saved.book().get("bookUrl").getAsString();
                var progress = cache(bookUrl).getLast().data();
                if (!progress.get("complete").getAsBoolean()) progress = cache(bookUrl).getLast().data();
                assertTrue(progress.get("complete").getAsBoolean(), progress.toString());
                assertEquals(List.of(1, 1, 1), fixture.imageRequestCounts(), "All three same-line images must be cached and decoded");
                var controller = new BookController(EmptyCoroutineContext.INSTANCE);
                var book = new io.vertx.core.json.JsonObject(saved.book().toString()).mapTo(Book.class);
                Path cache = controller.getChapterCacheDir(book, "default").toPath();
                String raw = Files.readString(cache.resolve("0.txt"), StandardCharsets.UTF_8);
                assertEquals(fixture.contents.getFirst(), raw);
                try (var images = Files.list(cache.resolve("images"))) {
                    var files = images.toList(); assertEquals(3, files.size());
                    for (var image : files) {
                        assertArrayEquals(fixture.image, Files.readAllBytes(image), "Cache must contain decoded PNG bytes");
                        Files.delete(image); // Reproduce old text-only caches whose images are missing.
                    }
                }

                var filtered = get(exportPath(bookUrl, true) + "&filterInteractiveImages=true");
                assertDownloadHeaders(filtered, fixture, "epub", "application/epub+zip");
                retainEpubFixture("http-images-filtered.epub", filtered.body());
                assertDecodedInlineImages(filtered.body(), fixture.image, 2);
                assertEquals(List.of(2, 2, 1), fixture.imageRequestCounts(), "Export repairs both missing regular images without requesting the filtered interaction");
                var entries = unzip(filtered.body());
                byte[] cover = entries.get("OEBPS/Images/cover.jpg");
                assertNotNull(cover);
                assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, java.util.Arrays.copyOf(cover, 3), "PNG covers must be encoded as actual JPEG");
                assertNotNull(javax.imageio.ImageIO.read(new ByteArrayInputStream(cover)));
                String opf = new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
                assertTrue(opf.contains("href=\"Images/cover.jpg\""));
                assertTrue(org.jsoup.Jsoup.parse(opf, "", org.jsoup.parser.Parser.xmlParser()).select("item").stream()
                        .anyMatch(item -> item.attr("href").equals("Images/cover.jpg") && item.attr("media-type").equals("image/jpeg")));
                assertTrue(fixture.coverRequests.get() > 0, "The fixture supplied a real PNG cover");

                var all = get(exportPath(bookUrl, true) + "&filterInteractiveImages=false");
                assertDownloadHeaders(all, fixture, "epub", "application/epub+zip");
                retainEpubFixture("http-images-all.epub", all.body());
                assertDecodedInlineImages(all.body(), fixture.image, 3);
                assertEquals(List.of(2, 2, 2), fixture.imageRequestCounts(), "The explicit option includes and repairs the interactive image");
                assertEquals(raw, Files.readString(cache.resolve("0.txt"), StandardCharsets.UTF_8), "Export must never rewrite the raw chapter cache");
                fixture.assertRequests(1, 2, 1);
            } finally { deleteFixtureBook(saved); }
        }
    }

    @Test
    void epubTemplateHttpUploadStatusOptOutAndResetUseTheSameDownloadedChapters() throws Exception {
        FixtureBook saved = null;
        postJson("/reader3/deleteEpubTemplate", new JsonObject());
        try (var fixture = new BookFixture(true)) {
            try {
                assertEquals(0, successfulJson(get("/reader3/getEpubTemplate")).getAsJsonObject("data").getAsJsonArray("files").size());
                saved = saveFixtureBook(fixture, false);
                String bookUrl = saved.book().get("bookUrl").getAsString();
                assertTrue(cache(bookUrl).getLast().data().get("complete").getAsBoolean());
                String marker = "http-fu-template-marker";
                String template = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title>
                        <link rel="stylesheet" type="text/css" href="../Styles/custom.css" /></head>
                        <body><div id="%s"><h2>{title}</h2>{content}<p><img src="../Images/template-badge.png" alt="模板插图" /></p></div></body></html>
                        """.formatted(marker).strip();
                var templateFiles = new LinkedHashMap<String, byte[]>();
                templateFiles.put("Asset/Text/chapter.html", template.getBytes(StandardCharsets.UTF_8));
                templateFiles.put("Asset/Styles/custom.css", "body { color: #000; background: #fff; } h2 { margin: 1em; }".getBytes(StandardCharsets.UTF_8));
                templateFiles.put("Asset/Images/template-badge.png", fixtureImage());
                var installed = successfulJson(uploadZip("/reader3/saveEpubTemplate", "fu-asset-template.zip", templateZip(templateFiles)))
                        .getAsJsonObject("data").getAsJsonArray("files");
                assertEquals(List.of("Images/template-badge.png", "Styles/custom.css", "Text/chapter.html"),
                        installed.asList().stream().map(JsonElement::getAsString).toList());
                assertEquals(installed, successfulJson(get("/reader3/getEpubTemplate")).getAsJsonObject("data").getAsJsonArray("files"));

                var custom = get(exportPath(bookUrl, true));
                assertDownloadHeaders(custom, fixture, "epub", "application/epub+zip");
                retainEpubFixture("http-custom-template.epub", custom.body());
                assertTemplateExport(custom.body(), fixture, marker, true);

                var builtin = get(exportPath(bookUrl, true) + "&useTemplate=false");
                assertDownloadHeaders(builtin, fixture, "epub", "application/epub+zip");
                retainEpubFixture("http-custom-template-disabled.epub", builtin.body());
                assertTemplateExport(builtin.body(), fixture, marker, false);
                assertCompleteEpub(builtin.body(), fixture);
                assertEquals(installed, successfulJson(get("/reader3/getEpubTemplate")).getAsJsonObject("data").getAsJsonArray("files"), "Opting out must leave the stored template intact");

                var invalid = uploadZip("/reader3/saveEpubTemplate", "invalid-template.zip", templateZip(Map.of(
                        "Text/chapter.html", template.replace("{content}", "missing content placeholder").getBytes(StandardCharsets.UTF_8))));
                assertFalse(JsonParser.parseString(text(invalid)).getAsJsonObject().get("isSuccess").getAsBoolean(), "Invalid templates must be rejected via the upload API");
                assertEquals(installed, successfulJson(get("/reader3/getEpubTemplate")).getAsJsonObject("data").getAsJsonArray("files"), "A rejected upload must preserve the working template");

                assertEquals(0, postJson("/reader3/deleteEpubTemplate", new JsonObject()).getAsJsonObject("data").getAsJsonArray("files").size());
                assertEquals(0, successfulJson(get("/reader3/getEpubTemplate")).getAsJsonObject("data").getAsJsonArray("files").size());
                var reset = get(exportPath(bookUrl, true));
                assertDownloadHeaders(reset, fixture, "epub", "application/epub+zip");
                retainEpubFixture("http-template-reset.epub", reset.body());
                assertTemplateExport(reset.body(), fixture, marker, false);
                assertCompleteEpub(reset.body(), fixture);
                assertEquals(List.of(0, 1, 1, 1), java.util.stream.IntStream.range(0, 4).map(fixture.requests::get).boxed().toList(), "Changing the export template must reuse all cached chapters");
            } finally {
                try { postJson("/reader3/deleteEpubTemplate", new JsonObject()); }
                finally { deleteFixtureBook(saved); }
            }
        }
    }

    private FixtureBook saveFixtureBook(BookFixture fixture, boolean decodeImages) throws Exception {
        var input = new JsonObject(); input.addProperty("content", fixture.script());
        var source = postJson("/reader3/parseBookSource", input).getAsJsonArray("data").get(0).getAsJsonObject();
        if (decodeImages) {
            var rule = new JsonObject();
            rule.addProperty("imageDecode", "if (!chapter || chapter.index !== 0) throw new Error('missing chapter'); var decoded=[]; for(var i=0;i<result.length;i++) decoded.push((result[i]&255)^85); decoded;");
            source.add("ruleContent", rule);
        }
        postJson("/reader3/saveBookSource", source);
        JsonObject saved = null;
        try {
            var details = new JsonObject(); details.addProperty("url", fixture.base + "/book"); details.addProperty("bookSourceUrl", fixture.base);
            var book = postJson("/reader3/getBookInfo", details).getAsJsonObject("data");
            saved = postJson("/reader3/saveBook", book).getAsJsonObject("data");
            assertEquals(fixture.titles.size(), successfulJson(get("/reader3/getChapterList?url=" + encode(saved.get("bookUrl").getAsString()))).getAsJsonArray("data").size());
            return new FixtureBook(source, saved);
        } catch (Exception | AssertionError failure) {
            try { if (saved != null) postJson("/reader3/deleteBook", saved); }
            finally { postJson("/reader3/deleteBookSource", source); }
            throw failure;
        }
    }

    private void deleteFixtureBook(FixtureBook saved) throws Exception {
        if (saved == null) return;
        try { postJson("/reader3/deleteBook", saved.book()); }
        finally { postJson("/reader3/deleteBookSource", saved.source()); }
    }

    private void assertDecodedInlineImages(byte[] epub, byte[] expected, int count) throws Exception {
        var entries = unzip(epub);
        var chapter = org.jsoup.Jsoup.parse(new String(entries.get("OEBPS/Text/chapter_0.html"), StandardCharsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser());
        assertEquals(count, chapter.select("img").size());
        var manifest = org.jsoup.Jsoup.parse(new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser());
        for (var image : chapter.select("img")) {
            assertTrue(image.attr("src").startsWith("../Images/"), image.toString());
            String path = URI.create("OEBPS/Text/chapter_0.html").resolve(image.attr("src")).getPath();
            assertArrayEquals(expected, entries.get(path), "Every chapter image must refer to bundled decoded bytes");
            assertTrue(manifest.select("item").stream().anyMatch(item -> item.attr("href").equals(path.substring("OEBPS/".length()))
                    && item.attr("media-type").equals("image/png")), "Every image must be registered with the real PNG media type");
        }
    }

    private void assertTemplateExport(byte[] epub, BookFixture fixture, String marker, boolean custom) throws Exception {
        var entries = unzip(epub);
        assertEquals(custom, entries.containsKey("OEBPS/Styles/custom.css"));
        assertEquals(custom, entries.containsKey("OEBPS/Images/template-badge.png"));
        for (int index = 0; index < fixture.titles.size(); index++) {
            String chapter = new String(entries.get("OEBPS/Text/chapter_" + index + ".html"), StandardCharsets.UTF_8);
            assertEquals(custom, chapter.contains(marker));
            var document = org.jsoup.Jsoup.parse(chapter, "", org.jsoup.parser.Parser.xmlParser());
            assertEquals(fixture.titles.get(index), document.selectFirst("h2").text());
            assertEquals(fixture.titles.get(index), document.selectFirst("title").text());
            for (String paragraph : fixture.contents.get(index).split("\\n")) assertTrue(document.text().contains(paragraph), chapter);
        }
    }

    private void retainEpubFixture(String name, byte[] bytes) throws IOException {
        Path output = Path.of("build", "test-epub-fixtures"); Files.createDirectories(output); Files.write(output.resolve(name), bytes);
    }

    private byte[] templateZip(Map<String, byte[]> files) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return output.toByteArray();
    }

    private record FixtureBook(JsonObject source, JsonObject book) {}

    @Test
    void localEpubAndCbzReturnEncodedPublicResourcesAndKeepChapterAnchors() throws Exception {
        assertEquals(Path.of("build/test-storage").toAbsolutePath().normalize(), Path.of(VertExtKt.getStoragePath()).toAbsolutePath().normalize());
        for (String extension : List.of("epub", "cbz")) {
            String unique = UUID.randomUUID().toString();
            String filename = "local-resource-" + unique + "." + extension;
            JsonObject preview = null, saved = null;
            try {
                byte[] archive = localArchiveFixture(extension, unique);
                var imported = successfulJson(uploadArchive(filename, archive)).getAsJsonArray("data");
                assertEquals(1, imported.size()); preview = imported.get(0).getAsJsonObject().getAsJsonObject("book");
                assertTrue(preview.get("bookUrl").getAsString().startsWith("/assets/default/book/"));
                saved = postJson("/reader3/saveBook", preview).getAsJsonObject("data");
                String bookUrl = saved.get("bookUrl").getAsString();
                var chapters = successfulJson(get("/reader3/getChapterList?url=" + encode(bookUrl))).getAsJsonArray("data");
                assertEquals(1, chapters.size(), chapters.toString());
                String path = "/reader3/getBookContentDetails?url=" + encode(bookUrl) + "&index=0&cache=1";
                String content = successfulJson(get(path)).getAsJsonObject("data").get("content").getAsString();
                assertEquals(content, successfulJson(get(path.replace("getBookContentDetails", "getBookContent"))).get("data").getAsString());
                String resourceUrl = archiveContentUrl(content, extension);
                assertTrue(resourceUrl.startsWith("/epub/default/"), resourceUrl);
                assertFalse(resourceUrl.contains("\\"), resourceUrl);
                assertFalse(resourceUrl.contains(" "), resourceUrl);
                var resource = get(resourceUrl);
                assertEquals(200, resource.statusCode(), text(resource));
                if (extension.equals("epub")) {
                    assertTrue(text(resource).contains("本地 EPUB 正文 " + unique), text(resource));
                    String image = org.jsoup.Jsoup.parse(text(resource)).selectFirst("img").attr("src");
                    var imageUrl = URI.create(resourceUrl).resolve(new URI(null, null, image, null).toASCIIString()).toString();
                    assertArrayEquals(fixtureImage(), get(imageUrl).body());

                    // A stored TOC entry may include a fragment; the filesystem lookup must use only its path.
                    var controller = new BookController(EmptyCoroutineContext.INSTANCE);
                    var toc = new io.vertx.core.json.JsonArray(chapters.toString());
                    toc.getJsonObject(0).put("url", toc.getJsonObject(0).getString("url") + "#intro 空格+一");
                    String folder = saved.get("name").getAsString() + "_" + saved.get("author").getAsString();
                    String hash = io.legado.app.utils.MD5Utils.INSTANCE.md5Encode(bookUrl);
                    controller.saveUserStorage("default", VertExtKt.getRelativePath(new String[]{folder, hash}), toc);
                    String anchored = successfulJson(get(path)).getAsJsonObject("data").get("content").getAsString();
                    assertEquals("intro 空格+一", URI.create(anchored).getFragment());
                    assertEquals(resourceUrl, anchored.substring(0, anchored.indexOf('#')));
                    assertEquals(200, get(anchored.substring(0, anchored.indexOf('#'))).statusCode());
                } else {
                    assertArrayEquals(fixtureImage(), resource.body());
                    assertTrue(resourceUrl.contains("%2B"), resourceUrl);
                    assertTrue(resourceUrl.contains("%20"), resourceUrl);
                }
            } finally {
                if (saved != null) postJson("/reader3/deleteBook", saved);
                if (preview != null) {
                    var cleanup = new JsonObject(); cleanup.addProperty("url", preview.get("bookUrl").getAsString()); postJson("/reader3/deleteFile", cleanup);
                    if (preview.has("coverUrl") && !preview.get("coverUrl").isJsonNull()) {
                        String cover = preview.get("coverUrl").getAsString().replace('\\', '/');
                        if (cover.startsWith("/assets/default/")) { cleanup.addProperty("url", cover); postJson("/reader3/deleteFile", cleanup); }
                    }
                }
            }
        }
    }

    private String archiveContentUrl(String content, String extension) {
        return extension.equals("epub") ? content : org.jsoup.Jsoup.parse(content).selectFirst("img").attr("src").replace("__API_ROOT__", "");
    }

    private HttpResponse<byte[]> uploadArchive(String filename, byte[] archive) throws Exception {
        return uploadZip("/reader3/importBookPreview", filename, archive);
    }

    private HttpResponse<byte[]> uploadZip(String path, String filename, byte[] archive) throws Exception {
        String boundary = "reader-fixture-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(archive); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    private byte[] localArchiveFixture(String extension, String unique) throws Exception {
        var bytes = new ByteArrayOutputStream();
        if (extension.equals("epub")) {
            var book = new me.ag2s.epublib.domain.EpubBook();
            book.getMetadata().addTitle("本地 EPUB + " + unique); book.getMetadata().addAuthor(new me.ag2s.epublib.domain.Author("测试作者"));
            String template = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>{title}</title></head><body><h1 id=\"intro 空格+一\">{title}</h1>{content}</body></html>";
            book.addSection("测试章节", me.ag2s.epublib.util.ResourceUtil.createChapterResource("测试章节", "本地 EPUB 正文 " + unique + "\n<img src=\"../Images/illustration.png\" />", template, "Text/one.xhtml"));
            book.getResources().add(new me.ag2s.epublib.domain.Resource(fixtureImage(), "Images/illustration.png"));
            new me.ag2s.epublib.epub.EpubWriter().write(book, bytes);
        } else {
            try (var zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("pages/01 + 蓝.png")); zip.write(fixtureImage()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] fixtureImage() throws IOException {
        try (var image = BookDownloadIntegrationTests.class.getResourceAsStream("/epub/logo.png")) {
            assertNotNull(image); return image.readAllBytes();
        }
    }

    @Test
    void webdavRestoreRebindsDeclaredReadingAssetsToTheRestoringUser(@TempDir Path temporary) throws Exception {
        assertEquals(Path.of("build/test-storage").toAbsolutePath().normalize(), Path.of(VertExtKt.getStoragePath()).toAbsolutePath().normalize());
        String originalUser = "backup-original-fixture";
        String fontName = UUID.randomUUID() + ".woff2", backgroundName = UUID.randomUUID() + ".png";
        String originalFont = "/assets/" + originalUser + "/fonts/" + fontName;
        String originalBackground = "/assets/" + originalUser + "/background/" + backgroundName;
        String fontUrl = "/assets/default/fonts/" + fontName, backgroundUrl = "/assets/default/background/" + backgroundName;
        var reading = new JsonObject(); reading.addProperty("customFontUrl", originalFont); reading.addProperty("backgroundImage", originalBackground);
        reading.addProperty("note", originalFont);
        var font = new JsonObject(); font.addProperty("id", originalFont); font.addProperty("name", "font fixture"); font.addProperty("url", originalFont);
        var image = new JsonObject(); image.addProperty("name", "background fixture"); image.addProperty("url", originalBackground);
        var profile = new JsonObject(); profile.addProperty("id", "fixture-profile"); profile.add("settings", reading.deepCopy());
        var appearance = new JsonObject();
        var fonts = new JsonArray(); fonts.add(font); appearance.add("fonts", fonts);
        var backgrounds = new JsonArray(); backgrounds.add(image); appearance.add("backgrounds", backgrounds);
        var profiles = new JsonArray(); profiles.add(profile); appearance.add("profiles", profiles);
        var config = new JsonObject(); config.add("reactReadingSettings", reading); config.add("reactAppearance", appearance);
        config.addProperty("unknownField", originalFont);
        var legacy = new JsonObject(); legacy.addProperty("contentBGImg", originalBackground);
        var fontMap = new JsonObject(); fontMap.addProperty("custom-system", originalFont); legacy.add("customFontsMap", fontMap);
        var legacyBackgrounds = new JsonArray(); legacyBackgrounds.add(originalBackground); legacy.add("customBGImgList", legacyBackgrounds);
        config.add("config", legacy);
        var legacyProfiles = new JsonArray(); legacyProfiles.add(legacy.deepCopy()); config.add("customConfigList", legacyProfiles);
        var external = new JsonObject(); external.addProperty("customFontUrl", "https://example.org" + originalFont);
        external.addProperty("backgroundImage", "/assets/" + originalUser + "-other/background/paper.png");
        var externalProfile = new JsonObject(); externalProfile.add("settings", external); profiles.add(externalProfile);

        var sourceData = temporary.resolve("data"); Files.createDirectories(sourceData);
        Files.writeString(sourceData.resolve("userConfig.json"), config.toString(), StandardCharsets.UTF_8);
        var sourceAssets = temporary.resolve("assets"); Files.createDirectories(sourceAssets.resolve("fonts")); Files.createDirectories(sourceAssets.resolve("background"));
        Files.writeString(sourceAssets.resolve("fonts").resolve(fontName), "font-bytes", StandardCharsets.UTF_8);
        Files.writeString(sourceAssets.resolve("background").resolve(backgroundName), "background-bytes", StandardCharsets.UTF_8);
        var controller = new BookController(EmptyCoroutineContext.INSTANCE);
        String archiveName = "appearance-fixture-" + UUID.randomUUID() + ".zip";
        var archive = new File(controller.getUserWebdavHome("default"), archiveName);
        var metadata = new io.vertx.core.json.JsonObject().put("userNamespace", originalUser);
        BackupArchive.INSTANCE.create(archive, Map.of("data", sourceData.toFile(), "assets", sourceAssets.toFile()), metadata);
        var configFile = Path.of(VertExtKt.getStoragePath(), "data", "default", "userConfig.json");
        byte[] previous = Files.exists(configFile) ? Files.readAllBytes(configFile) : null;
        var restoredFont = Path.of(VertExtKt.getWorkDir(new String[]{"storage", "assets", "default", "fonts", fontName}));
        var restoredBackground = Path.of(VertExtKt.getWorkDir(new String[]{"storage", "assets", "default", "background", backgroundName}));
        try {
            var restore = new JsonObject(); restore.addProperty("path", "/" + archiveName); postJson("/reader3/restoreFromWebdav", restore);
            var restored = successfulJson(get("/reader3/getUserConfig")).getAsJsonObject("data");
            var restoredSettings = restored.getAsJsonObject("reactReadingSettings");
            assertEquals(fontUrl, restoredSettings.get("customFontUrl").getAsString());
            assertEquals(backgroundUrl, restoredSettings.get("backgroundImage").getAsString());
            var restoredAppearance = restored.getAsJsonObject("reactAppearance");
            assertEquals(fontUrl, restoredAppearance.getAsJsonArray("fonts").get(0).getAsJsonObject().get("url").getAsString());
            assertEquals(backgroundUrl, restoredAppearance.getAsJsonArray("backgrounds").get(0).getAsJsonObject().get("url").getAsString());
            assertEquals(fontUrl, restoredAppearance.getAsJsonArray("profiles").get(0).getAsJsonObject().getAsJsonObject("settings").get("customFontUrl").getAsString());
            assertEquals(fontUrl, restored.getAsJsonObject("config").getAsJsonObject("customFontsMap").get("custom-system").getAsString());
            assertEquals(backgroundUrl, restored.getAsJsonArray("customConfigList").get(0).getAsJsonObject().get("contentBGImg").getAsString());
            assertEquals(originalFont, restoredSettings.get("note").getAsString());
            assertEquals(originalFont, restored.get("unknownField").getAsString());
            assertEquals(originalFont, restoredAppearance.getAsJsonArray("fonts").get(0).getAsJsonObject().get("id").getAsString());
            assertEquals(external, restoredAppearance.getAsJsonArray("profiles").get(1).getAsJsonObject().getAsJsonObject("settings"));
            assertEquals("font-bytes", text(get(fontUrl))); assertEquals("background-bytes", text(get(backgroundUrl)));
        } finally {
            Files.deleteIfExists(archive.toPath()); Files.deleteIfExists(restoredFont); Files.deleteIfExists(restoredBackground);
            if (previous == null) Files.deleteIfExists(configFile); else Files.write(configFile, previous);
        }
    }

    private void assertReplacementExportsLeaveRawCacheIntact(String bookUrl, BookFixture fixture) throws Exception {
        JsonObject rule = new JsonObject();
        rule.addProperty("id", 79101);
        rule.addProperty("name", "export-rule-" + fixture.name);
        rule.addProperty("scope", fixture.name);
        rule.addProperty("pattern", fixture.contents.getFirst());
        rule.addProperty("replacement", "净化导出正文");
        try {
            postJson("/reader3/saveReplaceRule", rule);
            String replaced = text(get(exportPath(bookUrl, false) + "&useReplace=true"));
            assertTrue(replaced.contains("净化导出正文"));
            assertFalse(replaced.contains(fixture.contents.getFirst()));
            assertTrue(text(get(exportPath(bookUrl, false))).contains(fixture.contents.getFirst()));
            var processed = get(exportPath(bookUrl, true) + "&useReplace=true");
            boolean found = false;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(processed.body()))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (entry.getName().endsWith("chapter_0.html")) found = new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("净化导出正文");
                }
            }
            assertTrue(found, "EPUB uses the same content rules as TXT");
            var book = successfulJson(get("/reader3/getBookshelf")).getAsJsonArray("data").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(value -> bookUrl.equals(value.get("bookUrl").getAsString())).findFirst().orElseThrow();
            book.addProperty("useReplaceRule", false); postJson("/reader3/saveBook", book);
            try {
                assertTrue(text(get(exportPath(bookUrl, false) + "&useReplace=true")).contains(fixture.contents.getFirst()), "A disabled book must not use export replacement rules");
                var unchanged = unzip(get(exportPath(bookUrl, true) + "&useReplace=true").body());
                String html = new String(unchanged.get("OEBPS/Text/chapter_0.html"), StandardCharsets.UTF_8);
                assertFalse(html.contains("净化导出正文"));
                assertTrue(org.jsoup.Jsoup.parse(html).body().text().contains("第一章正文：中文"));
            } finally { book.addProperty("useReplaceRule", true); postJson("/reader3/saveBook", book); }
        } finally { postJson("/reader3/deleteReplaceRule", rule); }
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    private void assertVersionedBackupRestoresUserAndAdvancedConfiguration() throws Exception {
        JsonObject preferences = new JsonObject();
        preferences.addProperty("retainedLegacyField", "preserved");
        preferences.add("reactReadingSettings", JsonParser.parseString("{\"fontSize\":23,\"fontFamily\":\"fixture\"}"));
        postJson("/reader3/saveUserConfig", preferences);
        JsonObject patch = new JsonObject();
        patch.addProperty("@merge", true);
        patch.add("reactReadingSettings", JsonParser.parseString("{\"fontSize\":23,\"fontFamily\":\"fixture\",\"theme\":\"night\"}"));
        postJson("/reader3/saveUserConfig", patch);
        var merged = successfulJson(get("/reader3/getUserConfig")).getAsJsonObject("data");
        assertEquals("preserved", merged.get("retainedLegacyField").getAsString());
        assertEquals("night", merged.getAsJsonObject("reactReadingSettings").get("theme").getAsString());
        assertFalse(merged.has("@merge"));
        var config = successfulJson(get("/reader3/ai/config")).getAsJsonObject("data");
        JsonObject provider = new JsonObject();
        provider.addProperty("id", "backup-fixture"); provider.addProperty("name", "Fixture");
        provider.addProperty("baseUrl", "http://127.0.0.1:1/v1"); provider.addProperty("model", "fixture"); provider.addProperty("apiKey", "fixture-private-key");
        JsonArray providers = new JsonArray(); providers.add(provider);
        config.add("providers", providers); config.addProperty("defaultProviderId", "backup-fixture");
        postJson("/reader3/ai/config", config);
        var saved = postJson("/reader3/backupToWebdav", new JsonObject()).getAsJsonObject("data");
        String path = saved.get("path").getAsString();
        assertEquals(2, saved.get("version").getAsInt());
        try {
            var zip = get("/reader3/getWebdavFile?path=" + encode(path));
            assertEquals(200, zip.statusCode());
            boolean manifest = false, appearance = false, advanced = false;
            try (var entries = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
                for (ZipEntry entry; (entry = entries.getNextEntry()) != null;) {
                    if (entry.getName().equals("manifest.json")) manifest = true;
                    if (entry.getName().equals("data/userConfig.json")) appearance = true;
                    if (entry.getName().equals("advanced/ai-config.json")) advanced = true;
                }
            }
            assertTrue(manifest && appearance && advanced);
            // The live configuration remains available immediately after backup.
            assertEquals("preserved", successfulJson(get("/reader3/getUserConfig")).getAsJsonObject("data").get("retainedLegacyField").getAsString());
            postJson("/reader3/saveUserConfig", new JsonObject());
            config.add("providers", new JsonArray()); config.addProperty("defaultProviderId", "");
            postJson("/reader3/ai/config", config);
            JsonObject restore = new JsonObject(); restore.addProperty("path", path);
            postJson("/reader3/restoreFromWebdav", restore);
            assertEquals("preserved", successfulJson(get("/reader3/getUserConfig")).getAsJsonObject("data").get("retainedLegacyField").getAsString());
            var restored = successfulJson(get("/reader3/ai/config")).getAsJsonObject("data").getAsJsonArray("providers").get(0).getAsJsonObject();
            assertTrue(restored.get("apiKeyConfigured").getAsBoolean()); assertFalse(restored.has("apiKey"));
        } finally {
            JsonObject cleanup = new JsonObject(); cleanup.addProperty("path", path); postJson("/reader3/deleteWebdavFile", cleanup);
            config.add("providers", new JsonArray()); config.addProperty("defaultProviderId", ""); postJson("/reader3/ai/config", config);
        }
    }

    private void assertRepeatedGroupRemovalPreservesOtherGroups(JsonObject book) throws Exception {
        var selected = new JsonArray();
        selected.add(book);
        var body = new JsonObject();
        body.add("bookList", selected);
        body.addProperty("groupId", 1);
        postJson("/reader3/addBookGroupMulti", body);
        body.addProperty("groupId", 2);
        postJson("/reader3/addBookGroupMulti", body);
        body.addProperty("groupId", 1);
        for (int attempt = 0; attempt < 2; attempt++) {
            postJson("/reader3/removeBookGroupMulti", body);
            var shelf = successfulJson(get("/reader3/getBookshelf")).getAsJsonArray("data");
            var matching = shelf.asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(value -> book.get("bookUrl").equals(value.get("bookUrl"))).toList();
            assertEquals(1, matching.size());
            assertEquals(2, matching.getFirst().get("group").getAsInt(), "Removing group 1 repeatedly must preserve group 2");
        }
    }

    private void assertExportRejected(String bookUrl, int missing) throws Exception {
        for (boolean epub : List.of(false, true)) {
            var response = get(exportPath(bookUrl, epub));
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Disposition").isEmpty());
            var result = JsonParser.parseString(text(response)).getAsJsonObject();
            assertFalse(result.get("isSuccess").getAsBoolean(), text(response));
            assertTrue(result.get("errorMsg").getAsString().contains("尚有 " + missing + " 章"), text(response));
        }
    }

    private List<SseEvent> cache(String bookUrl) throws Exception {
        var response = get("/reader3/cacheBookSSE?url=" + encode(bookUrl) + "&concurrentCount=1");
        assertEquals(200, response.statusCode(), text(response));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        var events = new ArrayList<SseEvent>();
        for (String block : text(response).split("\\r?\\n\\r?\\n")) {
            String kind = "message";
            String data = null;
            for (String line : block.split("\\r?\\n")) {
                if (line.startsWith("event:")) kind = line.substring(6).trim();
                if (line.startsWith("data:")) data = line.substring(5).trim();
            }
            if (data != null) events.add(new SseEvent(kind, JsonParser.parseString(data).getAsJsonObject()));
        }
        assertFalse(events.isEmpty(), text(response));
        assertFalse(events.stream().anyMatch(event -> event.kind().equals("error")), text(response));
        assertEquals("end", events.getLast().kind(), text(response));
        return events;
    }

    private void assertProgress(JsonObject progress, int cached, int successes, int failures, boolean complete) {
        assertEquals(3, progress.get("totalCount").getAsInt(), progress.toString());
        assertEquals(cached, progress.get("cachedCount").getAsInt(), progress.toString());
        assertEquals(successes, progress.get("successCount").getAsInt(), progress.toString());
        assertEquals(failures, progress.get("failedCount").getAsInt(), progress.toString());
        assertEquals(complete, progress.get("complete").getAsBoolean(), progress.toString());
    }

    private void assertDownloadHeaders(HttpResponse<byte[]> response, BookFixture fixture, String extension, String mime) {
        assertEquals(200, response.statusCode(), text(response));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(mime));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        String disposition = response.headers().firstValue("Content-Disposition").orElseThrow();
        assertTrue(disposition.startsWith("attachment; filename*=UTF-8''"), disposition);
        assertEquals("《" + fixture.name + "》作者：" + fixture.author + "." + extension,
                URLDecoder.decode(disposition.substring("attachment; filename*=UTF-8''".length()), StandardCharsets.UTF_8));
    }

    private void assertCompleteEpub(byte[] bytes, BookFixture fixture) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var first = zip.getNextEntry();
            assertNotNull(first);
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertEquals("application/epub+zip", new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertTrue(entries.containsKey("META-INF/container.xml"));
        assertTrue(entries.containsKey("OEBPS/content.opf"));
        assertTrue(entries.containsKey("OEBPS/toc.ncx"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (var entry : entries.entrySet()) {
            if (entry.getKey().matches(".*\\.(xml|opf|ncx|html|xhtml)$"))
                assertNotNull(factory.newDocumentBuilder().parse(new ByteArrayInputStream(entry.getValue())), entry.getKey());
        }
        var metadata = factory.newDocumentBuilder().parse(new ByteArrayInputStream(entries.get("OEBPS/content.opf")));
        assertEquals(fixture.name, metadata.getElementsByTagNameNS("*", "title").item(0).getTextContent());
        assertEquals(fixture.author, metadata.getElementsByTagNameNS("*", "creator").item(0).getTextContent());
        var guide = metadata.getElementsByTagNameNS("*", "reference");
        assertTrue(guide.getLength() > 0, "The cover page must be registered in the OPF guide");
        assertEquals("Text/cover.html", ((org.w3c.dom.Element) guide.item(0)).getAttribute("href"));
        for (int index = 0; index < fixture.titles.size(); index++) {
            String path = "OEBPS/Text/chapter_" + index + ".html";
            assertTrue(entries.containsKey(path), path);
            var chapter = factory.newDocumentBuilder().parse(new ByteArrayInputStream(entries.get(path)));
            assertEquals(fixture.titles.get(index), chapter.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "h2").item(0).getTextContent());
            assertEquals(fixture.titles.get(index), chapter.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "title").item(0).getTextContent());
            var paragraphs = chapter.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "p");
            StringBuilder body = new StringBuilder();
            for (int p = 0; p < paragraphs.getLength(); p++) body.append(paragraphs.item(p).getTextContent()).append('\n');
            assertEquals(fixture.contents.get(index).strip(), body.toString().strip(), "Body must contain exactly the cached text, without a duplicate title");
            String content = chapter.getDocumentElement().getTextContent();
            assertTrue(content.contains(fixture.titles.get(index)), content);
            for (String paragraph : fixture.contents.get(index).split("\\n")) assertTrue(content.contains(paragraph), content);
        }
        assertEquals(fixture.titles.size(), entries.keySet().stream().filter(path -> path.matches("OEBPS/Text/chapter_\\d+\\.html")).count());
    }

    private JsonObject postJson(String path, JsonElement body) throws Exception {
        return successfulJson(send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))));
    }

    private JsonObject successfulJson(HttpResponse<byte[]> response) {
        assertEquals(200, response.statusCode(), text(response));
        var result = JsonParser.parseString(text(response)).getAsJsonObject();
        assertTrue(result.get("isSuccess").getAsBoolean(), text(response));
        return result;
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("reader.server.port", Integer.class) + path);
    }

    private String exportPath(String bookUrl, boolean epub) {
        return "/reader3/exportBook?url=" + encode(bookUrl) + "&isEpub=" + (epub ? 1 : 0);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private record SseEvent(String kind, JsonObject data) {}

    private static class BookFixture implements AutoCloseable {
        final String name = "中文导出回归 " + UUID.randomUUID();
        final String author = "作者甲";
        final List<String> titles;
        final List<String> contents;
        final AtomicIntegerArray requests;
        final AtomicIntegerArray imageRequests = new AtomicIntegerArray(3);
        final AtomicInteger coverRequests = new AtomicInteger();
        final byte[] image;
        final HttpServer server;
        final String base;

        BookFixture() throws IOException {
            this(false);
        }

        BookFixture(boolean includeVolume) throws IOException {
            this(includeVolume, null);
        }

        BookFixture(boolean includeVolume, byte[] image) throws IOException {
            this.image = image;
            var chapterTitles = new ArrayList<>(List.of("第一章 & <开始>", "第二章：重试", "第三章：继续"));
            var chapterContents = new ArrayList<>(List.of("第一章正文：中文 & 符号 1 < 2。\n第一章第二段。",
                    "第二章正文：失败重试后的完整内容。\n第二章第二段。", "第三章正文：前章失败仍继续下载。\n第三章第二段。"));
            if (includeVolume) { chapterTitles.addFirst("卷一 & <起>"); chapterContents.addFirst(""); }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            if (image != null) {
                chapterContents.set(0, "图片正文：两张普通图和一张互动图同一行。 <img src=\"" + imageUrl(0) + "\" alt=\"第一张\" /><img src=\"" + imageUrl(1) + "\" alt=\"第二张\" /><img src=\"" + imageUrl(2) + "\" alt=\"互动\" />");
                byte[] encoded = image.clone(); for (int index = 0; index < encoded.length; index++) encoded[index] ^= 85;
                for (int index = 0; index < 3; index++) {
                    final int imageIndex = index;
                    server.createContext("/images/" + (char) ('a' + index) + ".png", exchange -> {
                        imageRequests.incrementAndGet(imageIndex); respondBytes(exchange, encoded, "application/octet-stream");
                    });
                }
                server.createContext("/cover.png", exchange -> { coverRequests.incrementAndGet(); respondBytes(exchange, image, "image/png"); });
            }
            titles = List.copyOf(chapterTitles); contents = List.copyOf(chapterContents);
            requests = new AtomicIntegerArray(titles.size());
            var info = new JsonObject();
            info.addProperty("bookUrl", base + "/book");
            info.addProperty("name", name);
            info.addProperty("author", author);
            info.addProperty("tocUrl", base + "/toc");
            info.addProperty("intro", "中文简介 & 特殊字符 <正文>");
            if (image != null) info.addProperty("coverUrl", base + "/cover.png");
            var search = new JsonArray();
            search.add(info);
            server.createContext("/search", exchange -> respond(exchange, search.toString(), "application/json"));
            server.createContext("/book", exchange -> respond(exchange, info.toString(), "application/json"));
            var chapters = new JsonArray();
            for (int index = 0; index < titles.size(); index++) {
                var chapter = new JsonObject();
                chapter.addProperty("title", titles.get(index));
                chapter.addProperty("url", includeVolume && index == 0 ? titles.get(index) : base + "/chapter/" + index);
                chapter.addProperty("isVolume", includeVolume && index == 0);
                chapters.add(chapter);
                final int chapterIndex = index;
                server.createContext("/chapter/" + index, exchange -> {
                    int attempt = requests.incrementAndGet(chapterIndex);
                    String content = !includeVolume && chapterIndex == 1 && attempt == 1 ? "\n  \t" : contents.get(chapterIndex);
                    respond(exchange, content, "text/plain");
                });
            }
            server.createContext("/toc", exchange -> respond(exchange, chapters.toString(), "application/json"));
            server.start();
        }

        String script() {
            return """
                    var config={bookSourceUrl:'%s',bookSourceName:'下载回归书源'};
                    function page(url){return JSON.parse(java.ajax(url));}
                    function search(key,pageNumber){return page(config.bookSourceUrl+'/search?key='+encodeURIComponent(key));}
                    function getBookInfo(book){return page(book.bookUrl);}
                    function getChapters(book){return page(book.tocUrl);}
                    function getContent(chapter,book,nextChapterUrl){return java.ajax(chapter.url);}
                    """.formatted(base);
        }

        void assertRequests(int first, int second, int third) {
            assertEquals(List.of(first, second, third), List.of(requests.get(0), requests.get(1), requests.get(2)),
                    "Completed chapters must be reused without another source request");
        }

        String imageUrl(int index) {
            return base + "/images/" + (char) ('a' + index) + ".png?token=fixture" + (index == 2 ? ",{\"click\":\"fixture-interaction\"}" : "");
        }

        List<Integer> imageRequestCounts() { return List.of(imageRequests.get(0), imageRequests.get(1), imageRequests.get(2)); }

        private static void respond(HttpExchange exchange, String content, String mime) throws IOException {
            respondBytes(exchange, content.getBytes(StandardCharsets.UTF_8), mime + "; charset=UTF-8");
        }

        private static void respondBytes(HttpExchange exchange, byte[] bytes, String mime) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        }

        @Override
        public void close() { server.stop(0); }
    }
}
