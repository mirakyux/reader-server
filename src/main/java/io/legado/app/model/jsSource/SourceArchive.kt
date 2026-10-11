package io.legado.app.model.jsSource

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Extracts streams into paths chosen by the server; 7-Zip never writes archive paths. */
object SourceArchive {
    private const val MAX_BYTES = 128L * 1024 * 1024
    private fun tool(): String {
        System.getenv("READER_7ZIP")?.takeIf { it.isNotBlank() && File(it).isFile }?.let { return it }
        val names = if (System.getProperty("os.name").startsWith("Windows", true)) listOf("7z.exe", "7zz.exe") else listOf("7zz", "7z")
        System.getenv("PATH").orEmpty().split(File.pathSeparator).forEach { directory ->
            names.forEach { name -> File(directory, name).takeIf { it.isFile }?.let { return it.absolutePath } }
        }
        error("7z/RAR 解压需要现有 7-Zip 可执行程序；可通过 READER_7ZIP 指定位置")
    }
    private fun execute(arguments: List<String>, maximum: Long): ByteArray {
        val process = ProcessBuilder(listOf(tool()) + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(bytes.size().toLong() + count <= maximum) { "解压输出超过限制" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        }
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "解压执行超时" }
            check(process.exitValue() == 0) { "压缩文件损坏、加密或格式不支持" }
            return output.get(2, TimeUnit.SECONDS)
        } finally { if (process.isAlive) process.destroyForcibly(); output.cancel(true) }
    }
    fun extract(archive: File, output: File) {
        require(archive.isFile && archive.length() <= MAX_BYTES) { "压缩文件不存在或超过限制" }
        val listing = execute(listOf("l", "-slt", "-ba", "-sccUTF-8", "--", archive.canonicalPath), 4L * 1024 * 1024).toString(Charsets.UTF_8)
        val entries = listing.split(Regex("\\r?\\n\\s*\\r?\\n")).mapNotNull { block ->
            block.lineSequence().mapNotNull { line -> line.indexOf(" = ").takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 3) } }
                .toMap().takeIf { it.containsKey("Path") }
        }
        require(entries.isNotEmpty() && entries.size <= 10000) { "压缩文件为空或包含过多条目" }
        var size = 0L
        val paths = hashSetOf<String>()
        val root = output.canonicalFile
        val targets = entries.map { entry ->
            val name = entry.getValue("Path")
            require(!File(name).isAbsolute && !name.startsWith('/') && !name.startsWith('\\') && !name.contains(':')) { "压缩文件包含绝对路径" }
            val target = File(root, name.replace('\\', '/')).canonicalFile
            require(target.toPath().startsWith(root.toPath()) && target != root && paths.add(target.path.lowercase())) { "压缩路径超出缓存目录或重复" }
            require(entry["Symbolic Link"].isNullOrEmpty() && entry["Hard Link"].isNullOrEmpty() && entry["Encrypted"] != "+") { "不支持压缩文件中的链接或加密条目" }
            val length = entry["Size"]?.toLongOrNull() ?: 0L
            require(length >= 0 && length <= MAX_BYTES - size) { "解压总大小超过限制" }; size += length
            Triple(entry, target, length)
        }
        root.mkdirs()
        try {
            targets.forEach { (entry, target, length) ->
                if (entry["Folder"] == "+" || entry["Attributes"]?.startsWith("D") == true) target.mkdirs()
                else {
                    val bytes = execute(listOf("x", "-so", "-spd", "--", archive.canonicalPath, entry.getValue("Path")), length.coerceAtLeast(1))
                    require(bytes.size.toLong() == length) { "解压条目长度不匹配" }
                    target.parentFile.mkdirs(); target.writeBytes(bytes)
                }
            }
        } catch (failure: Exception) { root.deleteRecursively(); throw failure }
    }
}
