package com.htmake.reader.api.service

import io.legado.app.data.entities.Book
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.MediaType
import me.ag2s.epublib.domain.MediaTypes
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.util.ResourceUtil
import me.ag2s.epublib.util.StringUtil
import org.jsoup.parser.Parser
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** A private, validated FU Asset template. No template code is evaluated. */
class EpubTemplateStore(private val directory: File) {
    private val root = directory.canonicalFile.toPath()
    private val lock = locks.computeIfAbsent(root) { Any() }

    /** Validate the entire ZIP before replacing the previous template. */
    fun install(zip: File): List<String> = synchronized(lock) {
        val values = try {
            readArchive(zip).also(::validate)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("EPUB 模板 ZIP 已损坏或无法读取", error)
        }
        Files.createDirectories(root.parent)
        val staging = Files.createTempDirectory(root.parent, ".epub-template-")
        val backup = staging.resolveSibling("${staging.fileName}-previous")
        var previousMoved = false
        try {
            values.forEach { (name, bytes) ->
                val target = staging.resolve(name)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "EPUB 模板目录无效" }
                move(root, backup)
                previousMoved = true
            }
            try {
                move(staging, root)
            } catch (error: Exception) {
                if (previousMoved) {
                    try { move(backup, root) }
                    catch (rollback: Exception) { error.addSuppressed(rollback) }
                }
                throw error
            }
            // Installation has committed; a stale backup cleanup must not report
            // a failed installation after the active template has changed.
            runCatching { deleteTree(backup) }
            values.keys.sorted()
        } finally {
            deleteTree(staging)
        }
    }

    fun files(): List<String> = synchronized(lock) { storedFiles().map { root.relativize(it).toString().replace('\\', '/') }.sorted() }

    fun clear() = synchronized(lock) { deleteTree(root) }

    /**
     * Apply public pages and assets, returning the reusable chapter template.
     * The generated book cover takes priority; the template cover is used only
     * when no generated cover exists. Callers can add missing cover/intro pages.
     */
    fun apply(book: Book, epubBook: EpubBook, fallbackCover: Resource? = null): String? = synchronized(lock) {
        val paths = storedFiles()
        if (paths.isEmpty()) return@synchronized null
        var total = 0L
        val values = paths.associate { path ->
            val bytes = Files.newInputStream(path).use { readLimited(it, maxBytes - total) }
            total += bytes.size
            root.relativize(path).toString().replace('\\', '/') to bytes
        }
        validate(values)
        val chapterName = values.keys.single(::isChapter)
        val generatedCover = epubBook.coverImage
        val templateCoverName = values.keys.firstOrNull { it.equals("Images/cover.jpg", true) }
        if (generatedCover == null && templateCoverName == null && fallbackCover != null) epubBook.coverImage = fallbackCover
        val assets = values.keys.filter { it != chapterName && !isPublicPage(it) }
        var resourceNumber = 0
        fun nextId(): String {
            var id: String
            do { id = "template_${++resourceNumber}" } while (epubBook.resources.containsId(id))
            return id
        }
        assets.sorted().forEach { name ->
            if (name == templateCoverName && generatedCover != null) return@forEach
            val type = mediaType(name)
            val bytes = if (type == MediaTypes.CSS) encodeCssReferences(String(values.getValue(name), Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                else if (type == MediaTypes.XHTML || type == MediaTypes.SVG) encodeXmlReferences(String(values.getValue(name), Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                else values.getValue(name)
            val asset = Resource(nextId(), bytes, name, type)
            epubBook.resources.add(asset)
            if (name == templateCoverName && generatedCover == null) epubBook.coverImage = asset
        }
        values.keys.filter(::isPublicPage).sorted().forEach { name ->
            var model = String(values.getValue(name), Charsets.UTF_8)
            epubBook.coverImage?.let { cover ->
                val relative = Path.of(name).parent.relativize(Path.of(cover.href)).toString().replace('\\', '/')
                model = model.replace(Regex("(?:\\.\\./)+Images/cover\\.jpg", RegexOption.IGNORE_CASE)) { StringUtil.escapeXml(StringUtil.encodeHref(relative)) }
            }
            val page = ResourceUtil.createPublicResource(book.name, book.getRealAuthor(), book.getDisplayIntro(),
                book.kind, book.wordCount, encodeXmlReferences(model), name)
            page.id = nextId()
            epubBook.addSection(File(name).nameWithoutExtension, page)
            if (File(name).nameWithoutExtension.equals("cover", true)) epubBook.coverPage = page
        }
        encodeXmlReferences(String(values.getValue(chapterName), Charsets.UTF_8))
    }

    private fun encodeXmlReferences(xml: String): String {
        val attributes = Regex("(\\b(href|src|poster|style)\\s*=\\s*)([\"'])(.*?)\\3", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val encoded = attributes.replace(xml) { match ->
            val value = Parser.unescapeEntities(match.groupValues[4], false)
            val reference = if (match.groupValues[2].equals("style", true)) encodeCssReferences(value) else encodeReference(value)
            match.groupValues[1] + match.groupValues[3] + StringUtil.escapeXml(reference) + match.groupValues[3]
        }
        return Regex("(<style\\b[^>]*>)(.*?)(</style>)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(encoded) { match ->
            val css = match.groupValues[2]
            val body = if (css.trim().startsWith("<![CDATA[") && css.trim().endsWith("]]>")) {
                "<![CDATA[" + encodeCssReferences(css.trim().removePrefix("<![CDATA[").removeSuffix("]]>")) + "]]>"
            } else StringUtil.escapeXml(encodeCssReferences(Parser.unescapeEntities(css, false)))
            match.groupValues[1] + body + match.groupValues[3]
        }
    }

    private fun encodeCssReferences(css: String): String {
        fun unescape(value: String) = Regex("\\\\([0-9a-fA-F]{1,6})[ \\t\\r\\n]?|\\\\(.)").replace(value) { match ->
            if (match.groupValues[1].isEmpty()) match.groupValues[2]
            else String(Character.toChars(match.groupValues[1].toInt(16).takeIf { it in 1..0x10FFFF } ?: 0xFFFD))
        }
        val urls = Regex("url\\(\\s*(?:([\"'])(.*?)\\1|((?:\\\\.|[^)])*?))\\s*\\)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val encoded = urls.replace(css) { match ->
            val quote = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3].trim() }
            "url(" + quote + encodeReference(unescape(value)) + quote + ")"
        }
        return Regex("(@import\\s+)([\"'])(.*?)\\2", RegexOption.IGNORE_CASE).replace(encoded) { match ->
            match.groupValues[1] + match.groupValues[2] + encodeReference(unescape(match.groupValues[3])) + match.groupValues[2]
        }
    }

    private fun encodeReference(value: String): String {
        if (value.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)) return value
        val suffixIndex = value.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) value.length else it }
        val path = value.substring(0, suffixIndex)
        fun decode(text: String) = runCatching { URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(text)
        val suffix = value.substring(suffixIndex)
        val query = if (suffix.startsWith('?')) URI(null, null, null, -1, null, decode(suffix.substringBefore('#').substring(1)), null).toASCIIString() else ""
        val fragment = if (suffix.contains('#')) "#" + StringUtil.encodeHref(decode(suffix.substringAfter('#'))) else ""
        return StringUtil.encodeHref(decode(path)) + query + fragment
    }

    private fun storedFiles(): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().toList()
        }
    }

    private fun readArchive(file: File): Map<String, ByteArray> {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(maxEntries + 1).toList()
            require(entries.size <= maxEntries) { "EPUB 模板最多允许 512 个条目" }
            val names = entries.map { checkedName(it.name.removeSuffix("/")) }
            require(names.map(::pathKey).distinct().size == names.size) { "EPUB 模板文件重复" }
            val fileEntries = entries.filterNot { it.isDirectory }
            val hasAsset = fileEntries.any { it.name.startsWith("Asset/", true) }
            require(!hasAsset || fileEntries.all { it.name.startsWith("Asset/", true) }) { "EPUB 模板不能混用 Asset 目录与根目录资源" }
            var total = 0L
            val values = linkedMapOf<String, ByteArray>()
            fileEntries.forEach { entry ->
                require(entry.size >= 0 && entry.size <= maxBytes - total) { "EPUB 模板解压超过 32 MiB" }
                val bytes = zip.getInputStream(entry).use { readLimited(it, maxBytes - total) }
                require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) { "EPUB 模板文件校验失败：${entry.name}" }
                total += bytes.size
                val name = if (hasAsset) entry.name.substringAfter('/') else entry.name
                values[checkedName(name)] = bytes
            }
            return values
        }
    }

    private fun validate(values: Map<String, ByteArray>) {
        val chapters = values.keys.filter(::isChapter)
        require(chapters.size == 1) { "EPUB 模板必须包含唯一的 Text/chapter.html 或 Text/chapter.xhtml" }
        require(String(values.getValue(chapters.single()), Charsets.UTF_8).contains("{content}")) { "EPUB 正文模板缺少 {content}" }
        values.forEach { (name, bytes) ->
            val type = mediaType(name)
            require(type != MediaTypes.JAVASCRIPT) { "EPUB 模板不支持 JavaScript" }
            if (type == MediaTypes.XHTML || type == MediaTypes.SVG) validateXml(name, bytes, type == MediaTypes.XHTML)
        }
    }

    private fun validateXml(name: String, bytes: ByteArray, xhtml: Boolean) {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val document = factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))
            document.doctype?.let { doctype ->
                require(doctype.internalSubset.isNullOrBlank() && (doctype.systemId.isNullOrBlank() ||
                    Regex("https?://www\\.w3\\.org/TR/(?:xhtml1|xhtml11)/DTD/xhtml(?:1-(?:strict|transitional|frameset)|11)\\.dtd").matches(doctype.systemId))) {
                    "EPUB 模板不支持自定义 DTD 或实体：$name"
                }
            }
            if (xhtml) require(document.documentElement.localName == "html" && document.documentElement.namespaceURI == "http://www.w3.org/1999/xhtml") { "EPUB 页面必须使用 XHTML 命名空间：$name" }
            if (isChapter(name)) require(document.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "body").item(0)?.textContent.orEmpty().contains("{content}")) {
                "EPUB 正文模板的 body 中缺少 {content}：$name"
            }
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val node = elements.item(index)
                require(!node.localName.equals("script", true)) { "EPUB 模板不支持脚本：$name" }
                for (attributeIndex in 0 until node.attributes.length) {
                    val attribute = node.attributes.item(attributeIndex)
                    require(!attribute.nodeName.startsWith("on", true) && !attribute.nodeValue.trim().startsWith("javascript:", true)) { "EPUB 模板不支持脚本属性：$name" }
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("EPUB 页面 XML 无效：$name", error)
        }
    }

    private fun mediaType(name: String): MediaType = requireNotNull(MediaTypes.determineMediaType(name)) { "EPUB 模板资源类型不支持：$name" }
    private fun isChapter(name: String) = name.equals("Text/chapter.html", true) || name.equals("Text/chapter.xhtml", true)
    private fun isPublicPage(name: String) = name.startsWith("Text/", true) && !isChapter(name) && (name.endsWith(".html", true) || name.endsWith(".xhtml", true))
    private fun pathKey(name: String) = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun checkedName(name: String): String {
        require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && name.none { it in ":<>\"|?*" || it.code < 32 } &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') }) { "EPUB 模板路径无效" }
        return name
    }
    private fun readLimited(input: InputStream, remaining: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= remaining) { "EPUB 模板解压超过 32 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun move(from: Path, to: Path) {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from, to) }
    }
    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    companion object {
        private const val maxBytes = 32L * 1024 * 1024
        private const val maxEntries = 512
        private val locks = ConcurrentHashMap<Path, Any>()
    }
}
