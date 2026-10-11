package com.htmake.reader.api.service

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.BookHelp
import io.legado.app.help.source.BrowserBridge
import io.vertx.core.json.JsonObject
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.LazyResource
import me.ag2s.epublib.domain.MediaType
import me.ag2s.epublib.domain.MediaTypes
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.FileResourceProvider
import me.ag2s.epublib.util.StringUtil
import org.jsoup.Jsoup
import com.google.gson.JsonParser
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.regex.Matcher
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** EPUB image bytes, names and media types must agree; chapter text remains untouched. */
object EpubImages {
    fun type(bytes: ByteArray): MediaType? {
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it] == values[it].toByte() }
        return when {
            starts(0xff, 0xd8, 0xff) -> MediaTypes.JPG
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> MediaTypes.PNG
            bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> MediaTypes.GIF
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> MediaTypes.WEBP
            bytes.toString(Charsets.UTF_8).trimStart().let { it.startsWith("<svg") || it.startsWith("<?xml") && it.contains("<svg") } -> MediaTypes.SVG
            else -> null
        }
    }

    private fun raster(bytes: ByteArray): BufferedImage? {
        ImageIO.read(ByteArrayInputStream(bytes))?.let { return it }
        if (type(bytes) != MediaTypes.WEBP) return null
        // Reuse the existing isolated browser decoder when the JDK has no WebP codec.
        val decoded = BrowserBridge.call("/decode-image", JsonObject().put("base64", Base64.getEncoder().encodeToString(bytes)))
        val png = Base64.getDecoder().decode(decoded.getString("base64"))
        require(type(png) == MediaTypes.PNG) { "浏览器图片转换结果不是 PNG" }
        return ImageIO.read(ByteArrayInputStream(png))
    }

    fun cover(bytes: ByteArray): Resource {
        val input = raster(bytes) ?: throw IllegalArgumentException("封面不是可解码的图片")
        val rgb = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().let { graphics ->
            try { graphics.color = Color.WHITE; graphics.fillRect(0, 0, rgb.width, rgb.height); graphics.drawImage(input, 0, 0, null) }
            finally { graphics.dispose(); input.flush() }
        }
        val bytesOut = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(bytesOut).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.9f }
                writer.write(null, IIOImage(rgb, null, null), parameters)
            }
        } finally { writer.dispose(); rgb.flush() }
        return Resource(bytesOut.toByteArray(), "Images/cover.jpg")
    }

    private fun interactive(src: String): Boolean {
        val options = AnalyzeUrl.paramPattern.matcher(src)
        return options.find() && runCatching {
            JsonParser.parseString(src.substring(options.end())).asJsonObject.get("click")?.asString?.isNotBlank() == true
        }.getOrDefault(false)
    }

    suspend fun embed(epub: EpubBook, book: Book, content: String, chapter: BookChapter, source: BookSource, filterInteractive: Boolean): String {
        val matcher = AppPattern.imgPattern.matcher(content)
        val result = StringBuffer(content.length)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            if (filterInteractive && interactive(raw)) {
                matcher.appendReplacement(result, "")
                continue
            }
            val src = if (raw.startsWith("data:", true)) raw else NetworkUtils.getAbsoluteURL(chapter.getAbsoluteURL(), raw)
            val file = BookHelp.getImage(book, src)
            try {
                if (!file.isFile) BookHelp.saveImage(source, book, src, chapter = chapter)
                var bytes = file.takeIf { it.isFile }?.readBytes()
                var media = bytes?.let(::type)
                if (media in listOf(MediaTypes.JPG, MediaTypes.PNG, MediaTypes.GIF) &&
                    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() == null) media = null
                if (media == null) {
                    BookHelp.saveImage(source, book, src, chapter = chapter, refresh = true)
                    bytes = file.readBytes()
                    media = type(bytes)
                }
                require(media != null) { "图片内容不是支持的图片格式" }
                val raster = if (media in listOf(MediaTypes.JPG, MediaTypes.PNG, MediaTypes.GIF, MediaTypes.WEBP))
                    raster(requireNotNull(bytes)) else null
                require(media == MediaTypes.SVG || raster != null) {
                    if (media == MediaTypes.WEBP) "当前图片解码器无法转换 WebP；EPUB 2 需要 PNG/JPEG 图片" else "图片内容无法解码"
                }
                val converted = if (media == MediaTypes.WEBP) ByteArrayOutputStream().also {
                    check(ImageIO.write(raster, "png", it)) { "无法将 WebP 转换为 PNG" }
                }.toByteArray() else null
                raster?.flush()
                if (converted != null) media = MediaTypes.PNG
                val href = "Images/${MD5Utils.md5Encode16(src)}${media.defaultExtension}"
                if (epub.resources.getByHref(href) == null) {
                    epub.resources.add(if (converted != null) Resource(converted, href)
                        else LazyResource(FileResourceProvider(file.parent), href, file.name))
                }
                val alt = Jsoup.parseBodyFragment(matcher.group()).selectFirst("img")?.attr("alt").orEmpty()
                val image = "<img src=\"../$href\" alt=\"${StringUtil.escapeXml(alt)}\" />"
                matcher.appendReplacement(result, Matcher.quoteReplacement(image))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException("章节图片导出失败：${chapter.title}：${error.message}（$src）", error)
            }
        }
        matcher.appendTail(result)
        return result.toString()
    }
}
