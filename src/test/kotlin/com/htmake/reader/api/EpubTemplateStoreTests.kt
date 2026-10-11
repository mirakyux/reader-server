package com.htmake.reader.api

import com.htmake.reader.api.service.EpubTemplateStore
import io.legado.app.data.entities.Book
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.util.ResourceUtil
import me.ag2s.epublib.util.StringUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class EpubTemplateStoreTests {
    @TempDir lateinit var temp: Path

    private val chapter = page("章节", "<h2>{title}</h2><p>{ori_title}</p>{content}")
    private fun store() = EpubTemplateStore(temp.resolve("user/template").toFile())
    private fun page(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body>$body</body></html>"""
    private fun archive(files: Map<String, ByteArray>): File {
        val output = temp.resolve("template.zip").toFile()
        ZipOutputStream(output.outputStream()).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output
    }
    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        assertTrue(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output))
        output.toByteArray()
    }

    @Test fun absenceAndClearAllowBuiltinFallback() {
        val store = store()
        assertTrue(store.files().isEmpty())
        assertNull(store.apply(Book(), EpubBook()))
        store.clear()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray())))
        assertEquals(listOf("Text/chapter.html"), store.files())
        store.clear()
        assertTrue(store.files().isEmpty())
        assertNull(store.apply(Book(), EpubBook()))
    }

    @Test fun fuAssetLayoutLoadsChinesePagesFontsAndPreservesGeneratedCover() {
        val files = linkedMapOf(
            "Asset/Text/chapter.html" to chapter.toByteArray(),
            "Asset/Text/cover.html" to page("封面", "<p>{name}</p><img src=\"../Images/cover.jpg\" alt=\"封面\" />").toByteArray(),
            "Asset/Text/简介.xhtml" to page("简介", "<h1>{author}</h1>{intro}<p>{kind} {wordCount}</p>").toByteArray(),
            "Asset/Styles/main.css" to "@font-face { font-family: custom; src: url('../Fonts/中文 字体.ttf'); }".toByteArray(),
            "Asset/Fonts/中文 字体.ttf" to byteArrayOf(0, 1, 2),
            "Asset/Images/cover.jpg" to jpeg()
        )
        val store = store()
        assertEquals(files.keys.map { it.removePrefix("Asset/") }.sorted(), store.install(archive(files)))
        val generated = Resource(jpeg(), "Images/cover.jpg")
        val epub = EpubBook().apply { coverImage = generated }
        val book = Book(name = "书名 & <特殊>", author = "张 & 李", intro = "第一段\n第二段", kind = "小说", wordCount = "十万字")
        assertEquals(chapter, store.apply(book, epub))
        assertSame(generated, epub.coverImage)
        assertSame(generated, epub.resources.getByHref("Images/cover.jpg"))
        assertEquals("Text/cover.html", epub.guide.coverPage.href)
        val intro = String(epub.resources.getByHref("Text/简介.xhtml").data, Charsets.UTF_8)
        assertTrue(intro.contains("张 &amp; 李"))
        assertTrue(intro.contains("第一段"))
        assertTrue(intro.contains("第二段"))
        assertFalse(epub.resources.containsByHref("Text/chapter.html"), "The reusable chapter template must not become a reading section")
        assertNotNull(epub.resources.getByHref("Fonts/中文 字体.ttf").mediaType)
        assertEquals(listOf("cover", "简介"), epub.tableOfContents.tocReferences.map { it.title })
        assertEquals(epub.resources.size(), epub.resources.all.map { it.id }.distinct().size)
        assertTrue(epub.resources.all.all { it.id.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*")) })
    }

    @Test fun flatArchiveUsesExternalCoverOrRewritesCoverToGeneratedPng() {
        val store = store()
        val external = jpeg()
        store.install(archive(mapOf(
            "Text/chapter.xhtml" to chapter.toByteArray(),
            "Text/cover.xhtml" to page("封面", "<img src=\"../Images/cover.jpg\" alt=\"封面\" />").toByteArray(),
            "Images/cover.jpg" to external,
            "Fonts/regular.woff2" to byteArrayOf(1, 2, 3)
        )))
        val standalone = EpubBook()
        assertEquals(chapter, store.apply(Book(), standalone))
        assertEquals("Images/cover.jpg", standalone.coverImage.href)
        assertArrayEquals(external, standalone.coverImage.data)
        assertEquals("font/woff2", standalone.resources.getByHref("Fonts/regular.woff2").mediaType.name)

        val generated = Resource(byteArrayOf(9, 8), "Images/generated.png")
        val withGenerated = EpubBook().apply { coverImage = generated }
        store.apply(Book(), withGenerated)
        assertSame(generated, withGenerated.coverImage)
        assertTrue(String(withGenerated.guide.coverPage.data, Charsets.UTF_8).contains("../Images/generated.png"))
        assertFalse(withGenerated.resources.containsByHref("Images/cover.jpg"), "Generated cover has priority over the template's default image")
    }

    @Test fun fallbackCoverIsUsedOnlyWithoutBookOrTemplateCover() {
        val store = store()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray(),
            "Text/cover.html" to page("封面", "<img src=\"../Images/cover.jpg\" alt=\"封面\" />").toByteArray())))
        val fallback = Resource(byteArrayOf(8, 9), "Images/logo.png")
        val epub = EpubBook()
        store.apply(Book(), epub, fallback)
        assertSame(fallback, epub.coverImage)
        assertTrue(String(epub.guide.coverPage.data, Charsets.UTF_8).contains("../Images/logo.png"))
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray(), "Images/cover.jpg" to jpeg())))
        val withTemplate = EpubBook()
        store.apply(Book(), withTemplate, fallback)
        assertEquals("Images/cover.jpg", withTemplate.coverImage.href)
        assertNotSame(fallback, withTemplate.coverImage)
    }

    @Test fun rawArchivePathsAndFragmentsUseUriEncodingRatherThanFormEncoding() {
        assertEquals("Text/%E7%AC%AC%E4%B8%80%20%E7%AB%A0.xhtml", StringUtil.encodeHref("Text/第一 章.xhtml"))
        assertEquals("Images/a%2Bb%2520%23c.jpg", StringUtil.encodeHref("Images/a+b%20#c.jpg"))
        assertEquals("Text/a.xhtml#%E6%AE%B5%E8%90%BD%201", StringUtil.encodeHref("Text/a.xhtml", "段落 1"))
    }

    @Test fun exportedTemplatePathsKeepOriginalZipNamesAndEncodeAllDocumentReferences() {
        val store = store()
        fun standardDoctype(html: String) = html.replace("?><html", "?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\"><html")
        val linkedChapter = standardDoctype(chapter
            .replace("</head>", "<link href=\"../Styles/主题 样式.css\" rel=\"stylesheet\" type=\"text/css\" /></head>")
            .replace("<body>", "<body id=\"正文\" style=\"background-image: url(&quot;../Images/插图 一.jpg&quot;);\"><p><a href=\"#正文\">正文</a></p>"))
        val stylesheet = """@import '附加 样式.css'; body { background-image: url('../Images/插图\20 一.jpg'); }"""
        store.install(archive(linkedMapOf(
            "Text/chapter.html" to linkedChapter.toByteArray(),
            "Text/简介 说明.xhtml" to standardDoctype(page("简介", "<p><img src=\"../Images/%E6%8F%92%E5%9B%BE%20%E4%B8%80.jpg\" alt=\"插图\" /></p>")).toByteArray(),
            "Styles/主题 样式.css" to stylesheet.toByteArray(),
            "Styles/附加 样式.css" to "body { color: black; }".toByteArray(),
            "Images/插图 一.jpg" to jpeg()
        )))
        val epub = EpubBook().apply { metadata.addTitle("中文与空格路径模板"); metadata.language = "zh" }
        val template = requireNotNull(store.apply(Book(name = "中文与空格路径模板"), epub))
        val chapterResource = ResourceUtil.createChapterResource("第一章", "完整中文正文", template, "Text/第一章 开始.xhtml").apply { id = "chapter_paths" }
        epub.addSection("第一章", chapterResource)
        epub.coverPage = epub.resources.getByHref("Text/简介 说明.xhtml")
        val fixture = File("epub-fixtures/template-paths.epub").apply { parentFile.mkdirs() }
        fixture.outputStream().use { EpubWriter().write(epub, it) }
        ZipFile(fixture).use { zip ->
            fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertNotNull(zip.getEntry("OEBPS/Text/第一章 开始.xhtml"))
            assertNotNull(zip.getEntry("OEBPS/Styles/主题 样式.css"))
            assertTrue(text("OEBPS/content.opf").contains(StringUtil.encodeHref("Text/第一章 开始.xhtml")))
            assertTrue(text("OEBPS/content.opf").contains("href=\"${StringUtil.encodeHref("Text/简介 说明.xhtml")}\""))
            assertTrue(text("OEBPS/toc.ncx").contains(StringUtil.encodeHref("Text/简介 说明.xhtml")))
            assertTrue(text("OEBPS/Text/第一章 开始.xhtml").contains("../Styles/%E4%B8%BB%E9%A2%98%20%E6%A0%B7%E5%BC%8F.css"))
            assertTrue(text("OEBPS/Text/第一章 开始.xhtml").contains("url(&quot;../Images/%E6%8F%92%E5%9B%BE%20%E4%B8%80.jpg&quot;)"))
            assertTrue(text("OEBPS/Text/第一章 开始.xhtml").contains("href=\"#%E6%AD%A3%E6%96%87\""))
            assertTrue(text("OEBPS/Styles/主题 样式.css").contains("../Images/%E6%8F%92%E5%9B%BE%20%E4%B8%80.jpg"))
            assertTrue(text("OEBPS/Styles/主题 样式.css").contains("%E9%99%84%E5%8A%A0%20%E6%A0%B7%E5%BC%8F.css"))
            assertFalse(text("OEBPS/Text/简介 说明.xhtml").contains("%25E6"), "Already encoded references must not be encoded twice")
        }
    }

    @Test fun invalidChapterUnknownResourcesAndActiveXmlPreserveInstalledTemplate() {
        val store = store()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray())))
        val invalid = listOf(
            mapOf("Text/intro.html" to page("intro", "简介").toByteArray()),
            mapOf("Text/chapter.html" to page("chapter", "没有正文占位").toByteArray()),
            mapOf("Text/chapter.html" to page("{content}", "正文占位仅在标题中").toByteArray()),
            mapOf("Text/chapter.html" to "<html><body>{content}</body></html>".toByteArray()),
            mapOf("Text/chapter.html" to chapter.toByteArray(), "unknown.bin" to byteArrayOf(1)),
            mapOf("Text/chapter.html" to chapter.toByteArray(), "script.js" to "alert(1)".toByteArray()),
            mapOf("Text/chapter.html" to page("chapter", "<script>alert(1)</script>{content}").toByteArray()),
            mapOf("Text/chapter.html" to page("chapter", "<p onclick=\"alert(1)\">{content}</p>").toByteArray()),
            mapOf("Text/chapter.html" to """<!DOCTYPE html [<!ENTITY external SYSTEM "file:///C:/Windows/win.ini">]><html xmlns="http://www.w3.org/1999/xhtml"><head><title>chapter</title></head><body>&external;{content}</body></html>""".toByteArray())
        )
        invalid.forEach { entries ->
            assertThrows(IllegalArgumentException::class.java) { store.install(archive(entries)) }
            assertEquals(chapter, store.apply(Book(), EpubBook()))
            assertEquals(listOf("Text/chapter.html"), store.files())
        }
    }

    @Test fun standardFuDoctypeIsAcceptedAndReplacementRemovesPreviousAssets() {
        val store = store()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray(), "Styles/old.css" to "body {}".toByteArray())))
        val doctype = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">"
        val fuChapter = chapter.replace("?><html", "?>$doctype<html")
        assertEquals(listOf("Text/chapter.xhtml"), store.install(archive(mapOf("Asset/Text/chapter.xhtml" to fuChapter.toByteArray()))))
        assertEquals(fuChapter, store.apply(Book(), EpubBook()))
        assertFalse(temp.resolve("user/template/Styles/old.css").toFile().exists())
        val anotherUser = EpubTemplateStore(temp.resolve("another-user/template").toFile())
        assertTrue(anotherUser.files().isEmpty())
        assertNull(anotherUser.apply(Book(), EpubBook()))
    }

    @Test fun traversalAbsolutePathsCaseDuplicatesAndMalformedZipCannotPolluteStore() {
        val store = store()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray())))
        for (name in listOf("../escaped.css", "/absolute.css", "C:/absolute.css", "Text\\bad.css", "Asset/Text/../../escaped.css")) {
            assertThrows(IllegalArgumentException::class.java) {
                store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray(), name to byteArrayOf(1))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray(), "Text/CHAPTER.html" to chapter.toByteArray())))
        }
        val corrupt = temp.resolve("corrupt.zip").toFile().apply { writeText("not a ZIP") }
        assertThrows(IllegalArgumentException::class.java) { store.install(corrupt) }
        assertEquals(listOf("Text/chapter.html"), store.files())
        assertEquals(chapter, store.apply(Book(), EpubBook()))
        assertFalse(temp.resolve("escaped.css").toFile().exists())
    }

    @Test fun declaredInflatedSizeAndEntryCountAreBoundedBeforeReplacement() {
        val store = store()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray())))
        val large = temp.resolve("large.zip").toFile()
        ZipOutputStream(large.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Text/chapter.html")); zip.write(chapter.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("Fonts/huge.ttf"))
            val block = ByteArray(1024 * 1024)
            repeat(33) { zip.write(block) }
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { store.install(large) }
        val many = linkedMapOf("Text/chapter.html" to chapter.toByteArray())
        repeat(512) { many["Styles/$it.css"] = byteArrayOf(1) }
        assertThrows(IllegalArgumentException::class.java) { store.install(archive(many)) }
        assertEquals(chapter, store.apply(Book(), EpubBook()))
    }

    @Test fun crcCorruptionCannotReplaceExistingTemplate() {
        val store = store()
        store.install(archive(mapOf("Text/chapter.html" to chapter.toByteArray())))
        val damaged = temp.resolve("damaged.zip").toFile()
        val bytes = chapter.toByteArray()
        ZipOutputStream(damaged.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Text/chapter.html").apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            })
            zip.write(bytes); zip.closeEntry()
        }
        val damagedBytes = damaged.readBytes()
        val bodyStart = 30 + "Text/chapter.html".toByteArray().size
        damagedBytes[bodyStart] = '!'.code.toByte()
        damaged.writeBytes(damagedBytes)
        assertThrows(IllegalArgumentException::class.java) { store.install(damaged) }
        assertEquals(chapter, store.apply(Book(), EpubBook()))
    }
}
