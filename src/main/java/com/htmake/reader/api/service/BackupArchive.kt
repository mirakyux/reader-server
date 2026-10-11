package com.htmake.reader.api.service

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Versioned private user backup. Validate the entire archive before replacing any live file. */
object BackupArchive {
    private const val maxBytes = 256L * 1024 * 1024
    val legacyNames = setOf("bookSource.json", "bookshelf.json", "bookGroup.json", "rssSources.json", "rssStar.json", "replaceRule.json", "bookmark.json", "userConfig.json", "readRecord.json", "searchHistory.json", "sourceSub.json", "sourceSubscription.json", "txtTocRule.json", "dictRule.json", "keyboardAssists.json", "readConfig.json", "shareReadConfig.json", "highlightRule.json", "themeConfig.json", "coverRule.json", "servers.json")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun checkedName(name: String): String {
        require(name.isNotBlank() && !name.contains('\\') && !name.startsWith('/') && !name.contains(':') && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "备份文件路径无效" }
        return name
    }
    fun create(output: File, roots: Map<String, File>, metadata: JsonObject = JsonObject()): JsonObject {
        val files = linkedMapOf<String, ByteArray>()
        val backupMetadata = metadata.copy()
        var total = 0L
        roots.forEach { (prefix, root) ->
            if (root.isDirectory) root.walkTopDown().filter { it.isFile }.forEach fileLoop@{ file ->
                require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "备份目录越界" }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if ((prefix == "data" && (!relative.endsWith(".json") || relative.contains('/'))) || relative.startsWith("audio/")) return@fileLoop
                require(file.length() <= maxBytes - total) { "备份超过 256 MiB" }
                val bytes = file.inputStream().use { readLimited(it, maxBytes - total) }; total += bytes.size
                files[checkedName("$prefix/$relative")] = bytes
            }
        }
        // Export current reading sessions in FU's genuine cumulative-millisecond format.
        // Keep imported device totals separate so reimporting cannot double their time.
        files["advanced/reading-sessions.json"]?.let { bytes ->
            val parsed = com.google.gson.JsonParser.parseString(String(bytes))
            val sessions = if (parsed.isJsonObject) parsed.asJsonObject.getAsJsonArray("sessions") else null
            if (sessions != null) {
                val deviceId = "reader-dev-" + hash((roots["data"]?.canonicalPath ?: "reader").toByteArray()).take(16)
                backupMetadata.put("readingStatsDeviceId", deviceId)
                val previous = files["data/readRecord.json"]?.let { com.google.gson.JsonParser.parseString(String(it)).asJsonArray } ?: com.google.gson.JsonArray()
                val records = previous.filter { !it.isJsonObject || it.asJsonObject.get("deviceId")?.asString != deviceId }.toMutableList()
                sessions.filter { it.isJsonObject }.groupBy { it.asJsonObject.get("bookName")?.asString.orEmpty() }.forEach { (name, rows) ->
                    if (name.isNotBlank()) records.add(com.google.gson.JsonObject().also { record ->
                        record.addProperty("deviceId", deviceId); record.addProperty("bookName", name)
                        record.addProperty("readTime", rows.sumOf { it.asJsonObject.get("seconds")?.asLong ?: 0 } * 1000)
                        record.addProperty("lastRead", rows.maxOfOrNull { it.asJsonObject.get("endTime")?.asLong ?: 0 } ?: 0)
                    })
                }
                val recordBytes = com.htmake.reader.api.service.advanced.advancedJson.toJson(records).toByteArray()
                total -= files["data/readRecord.json"]?.size ?: 0
                require(recordBytes.size <= maxBytes - total) { "备份超过 256 MiB" }
                files["data/readRecord.json"] = recordBytes; total += recordBytes.size
            }
        }
        // FU restores its business entities from the ZIP root. Include checked aliases in
        // the v2 manifest so neither application has to give up its native backup format.
        files.filterKeys { it.startsWith("data/") && it.removePrefix("data/") in legacyNames }.toMap().forEach { (path, bytes) ->
            require(bytes.size <= maxBytes - total) { "备份超过 256 MiB" }
            files[path.removePrefix("data/")] = bytes; total += bytes.size
        }
        val manifest = JsonObject().put("version", 2).put("createdAt", System.currentTimeMillis()).put("metadata", backupMetadata)
            .put("files", JsonArray(files.map { (path, bytes) -> JsonObject().put("path", path).put("size", bytes.size).put("sha256", hash(bytes)) }))
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.encodePrettily().toByteArray()); zip.closeEntry()
            files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        return manifest
    }
    fun restore(archive: File, roots: Map<String, File>, transform: (JsonObject, String, ByteArray) -> ByteArray = { _, _, bytes -> bytes }): Int =
        restore(archive, { _, _ -> roots }, transform)

    fun restore(archive: File, resolveRoots: (JsonObject, Map<String, ByteArray>) -> Map<String, File>,
                transform: (JsonObject, String, ByteArray) -> ByteArray = { _, _, bytes -> bytes }): Int {
        val pending = linkedMapOf<File, ByteArray>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            require(entries.size <= 10000 && entries.map { checkedName(it.name) }.distinct().size == entries.size) { "备份文件重复或数量过多" }
            var declared = 0L
            entries.forEach { entry ->
                require(entry.size >= 0 && entry.size <= maxBytes - declared) { "备份解压超过 256 MiB" }
                declared += entry.size
            }
            var total = 0L
            val values = entries.associate { entry ->
                val bytes = zip.getInputStream(entry).use { readLimited(it, maxBytes - total) }
                total += bytes.size
                entry.name to bytes
            }
            val manifest = values["manifest.json"]?.let { JsonObject(String(it, Charsets.UTF_8)) }
            val roots = resolveRoots(manifest ?: JsonObject(), values)
            if (manifest != null) {
                require(manifest.getInteger("version") == 2) { "不支持此备份版本" }
                val files = manifest.getJsonArray("files") ?: error("备份清单缺失")
                val names = (0 until files.size()).map { files.getJsonObject(it).getString("path") }
                require(names.distinct().size == names.size && names.toSet() == values.keys - "manifest.json") { "备份清单与文件不一致" }
                for (index in 0 until files.size()) {
                    val record = files.getJsonObject(index); val name = checkedName(record.getString("path"))
                    val bytes = values[name] ?: error("备份文件缺失")
                    require(record.getLong("size") == bytes.size.toLong() && record.getString("sha256") == hash(bytes)) { "备份文件校验失败：$name" }
                    if (!name.contains('/')) {
                        require(name in legacyNames && values["data/$name"]?.contentEquals(bytes) == true) { "FU 兼容副本与原数据不一致" }
                        continue
                    }
                    val prefix = name.substringBefore('/'); val root = roots[prefix] ?: error("未知备份区域：$prefix")
                    val relative = name.substringAfter('/', ""); require(relative.isNotEmpty()) { "备份文件路径无效" }
                    if (prefix == "data") require(relative.endsWith(".json") && !relative.contains('/')) { "用户数据区域仅允许顶层 JSON 文件" }
                    val target = File(root, relative).canonicalFile
                    require(target.toPath().startsWith(root.canonicalFile.toPath()) && target != root.canonicalFile) { "恢复目录越界" }
                    if ((prefix == "data" || prefix == "advanced") && name != "data/servers.json") com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8))
                    var restored = bytes
                    val generatedDevice = manifest.getJsonObject("metadata")?.getString("readingStatsDeviceId")
                    if (name == "data/readRecord.json" && generatedDevice != null && values.containsKey("advanced/reading-sessions.json")) {
                        val records = com.google.gson.JsonParser.parseString(String(bytes)).asJsonArray.filter { it.asJsonObject.get("deviceId")?.asString != generatedDevice }
                        restored = com.htmake.reader.api.service.advanced.advancedJson.toJson(records).toByteArray()
                    }
                    pending[target] = transform(manifest, name, restored)
                }
            } else {
                val data = roots["data"] ?: error("缺少用户数据目录")
                values.filterKeys { it in legacyNames }.forEach { (name, bytes) ->
                    if (name != "servers.json") com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8))
                    pending[File(data, name)] = bytes
                }
                require(pending.isNotEmpty()) { "备份中没有可恢复的数据" }
            }
        }
        val rollback = pending.keys.associateWith { if (it.exists()) it.readBytes() else null }
        try { pending.forEach { (target, bytes) -> atomicWrite(target, bytes) } }
        catch (error: Exception) { rollback.forEach { (target, bytes) -> if (bytes == null) target.delete() else atomicWrite(target, bytes) }; throw error }
        return pending.size
    }
    private fun readLimited(stream: InputStream, remaining: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= remaining) { "备份读取超过 256 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile.mkdirs()
        val temp = Files.createTempFile(target.parentFile.toPath(), ".restore-", ".tmp")
        try { Files.write(temp, bytes); Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
    }
}
