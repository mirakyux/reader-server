package com.htmake.reader;

import me.ag2s.epublib.domain.EpubBook;
import me.ag2s.epublib.epub.EpubWriter;
import me.ag2s.epublib.util.ResourceUtil;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import me.ag2s.epublib.domain.Resource;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class EpubExportTests {
    @Test
    void productionEpubResourcesDoNotDependOnDeviceFontsOrFiles() throws Exception {
        String css = resource("/epub/main.css");
        assertFalse(css.contains("url("), "Default styles must not reference missing fonts or images");
        assertFalse(css.contains("font-family"), "The reading system must choose a font with available glyphs");
        assertFalse(css.contains("-3em"), "Chapter headings must not be clipped above the page");
        for (String name : new String[]{"chapter", "intro", "cover"}) {
            String template = resource("/epub/" + name + ".html");
            assertTrue(template.contains("-//W3C//DTD XHTML 1.1//EN"), "EPUB 2 needs XHTML 1.1: " + name);
            assertFalse(template.contains("fonts.css"));
        }
        assertTrue(resource("/epub/cover.html").contains("alt=\""));
    }

    @Test
    void booksWithoutGuideReferencesDoNotWriteAnInvalidEmptyGuideOrForceDuokanFonts() throws Exception {
        EpubBook book = new EpubBook();
        book.addSection("章节", ResourceUtil.createChapterResource("章节", "中文正文", resource("/epub/chapter.html"), "Text/chapter.xhtml"));
        var bytes = new ByteArrayOutputStream();
        new EpubWriter().write(book, bytes);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("OEBPS/content.opf")) {
                    String opf = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(opf.contains("<guide"));
                    assertFalse(opf.contains("duokan-body-font"));
                    return;
                }
            }
        }
        fail("Missing OPF");
    }

    private String resource(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void unreadableChapterStopsTheExportInsteadOfReturningIncompleteEpub() {
        EpubBook book = new EpubBook();
        book.getMetadata().addTitle("fixture");
        book.addSection("missing", new Resource(new byte[0], "Text/missing.xhtml") {
            @Override public InputStream getInputStream() throws IOException {
                throw new IOException("fixture chapter cannot be read");
            }
        });
        assertThrows(IOException.class, () -> new EpubWriter().write(book, new ByteArrayOutputStream()));
    }

    @Test
    void exportedEpubHasOrderedStoredMimetypeMetadataTocAndWellFormedChineseChapters() throws Exception {
        String template = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>{title}</title></head><body><h1>{title}</h1>{content}</body></html>";
        EpubBook book = new EpubBook();
        book.getMetadata().addTitle("中文 & <书籍>");
        book.addSection("第一章 & <开始>", ResourceUtil.createChapterResource("第一章 & <开始>", "中文正文 & 1 < 2\n第二段", template, "Text/chapter_0.xhtml"));
        book.addSection("第二章", ResourceUtil.createChapterResource("第二章", "完整的第二章内容\n<img src=\"../Images/a.jpg\" alt=\"图 &amp; 文\">", template, "Text/chapter_1.xhtml"));
        book.addSection("简介", ResourceUtil.createPublicResource("中文 & <书籍>", "作者 & 合著", "介绍 <文字>", null, null,
                template.replace("{title}", "{name}").replace("{content}", "<p>{author}</p>{intro}"), "Text/intro.xhtml"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new EpubWriter().write(book, output);
        var entries = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            var first = zip.getNextEntry();
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
        for (var entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".xml") || entry.getKey().endsWith(".opf") || entry.getKey().endsWith(".ncx") || entry.getKey().endsWith(".xhtml"))
                assertNotNull(factory.newDocumentBuilder().parse(new ByteArrayInputStream(entry.getValue())));
        }
        var chapter = factory.newDocumentBuilder().parse(new ByteArrayInputStream(entries.get("OEBPS/Text/chapter_0.xhtml")));
        assertTrue(chapter.getDocumentElement().getTextContent().contains("中文正文 & 1 < 2"));
        assertTrue(chapter.getDocumentElement().getTextContent().contains("第一章 & <开始>"));
        var illustrated = factory.newDocumentBuilder().parse(new ByteArrayInputStream(entries.get("OEBPS/Text/chapter_1.xhtml")));
        var image = (org.w3c.dom.Element) illustrated.getElementsByTagName("img").item(0);
        assertEquals("../Images/a.jpg", image.getAttribute("src"));
        assertEquals("图 & 文", image.getAttribute("alt"));
    }
}
