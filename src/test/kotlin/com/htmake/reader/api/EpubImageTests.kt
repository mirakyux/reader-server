package com.htmake.reader.api

import com.htmake.reader.api.service.EpubImages
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.runBlocking
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.MediaTypes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

class EpubImageTests {
    @TempDir lateinit var directory: Path
    private var previousStorageRoot = ""
    @BeforeEach fun isolateStorage() {
        previousStorageRoot = com.htmake.reader.utils.storageFinalPath
        com.htmake.reader.utils.storageFinalPath = directory.resolve("storage").toString()
    }
    @AfterEach fun restoreStorage() { com.htmake.reader.utils.storageFinalPath = previousStorageRoot }

    @Test
    fun `transparent PNG covers are actually converted to JPEG with a white background`() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val cover = EpubImages.cover(bytes)
        assertEquals("Images/cover.jpg", cover.href)
        assertEquals(MediaTypes.JPG, cover.mediaType)
        assertEquals(0xff.toByte(), cover.data[0])
        assertEquals(0xd8.toByte(), cover.data[1])
        val decoded = ImageIO.read(ByteArrayInputStream(cover.data))
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(0xffffff, decoded.getRGB(1, 1) and 0xffffff)
    }

    @Test
    fun `invalid cover data is rejected instead of being labelled JPEG`() {
        assertThrows(IllegalArgumentException::class.java) { EpubImages.cover("not an image".toByteArray()) }
    }

    @Test
    fun `WebP and WOFF2 are recognized media resources`() {
        val header = "RIFF0000WEBPVP8 ".toByteArray()
        assertEquals(MediaTypes.WEBP, EpubImages.type(header))
        assertEquals(MediaTypes.WEBP, MediaTypes.determineMediaType("Images/page.webp"))
        assertEquals("font/woff2", MediaTypes.determineMediaType("Fonts/chinese.woff2").name)
    }

    @Test
    fun `interactive image filtering removes click images but keeps ordinary image text`() = runBlocking {
        val book = Book(bookUrl = "https://example.org/book", name = "fixture").apply {
            setRootDir(directory.toString()); setUserNameSpace("fixture")
        }
        val input = "中文 & 正文<img src=\"https://example.org/tap,{\"click\":\"doSomething()\"}\">后续正文"
        val result = EpubImages.embed(EpubBook(), book, input, BookChapter(url = "https://example.org/chapter"), BookSource(), true)
        assertEquals("中文 & 正文后续正文", result)
    }
}
