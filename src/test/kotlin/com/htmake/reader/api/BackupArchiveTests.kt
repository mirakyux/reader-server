package com.htmake.reader.api

import com.htmake.reader.api.service.BackupArchive
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTests {
    @TempDir lateinit var temp: Path
    @Test fun fuExportIncludesWebReadingTotalsButV2RestoreDoesNotCountThemTwice() {
        val data = temp.resolve("stats-data").toFile().apply { mkdirs() }
        val advanced = temp.resolve("stats-advanced").toFile().apply { mkdirs() }
        File(data, "readRecord.json").writeText("[{\"deviceId\":\"fu-phone\",\"bookName\":\"历史书\",\"readTime\":120000,\"lastRead\":1}]")
        File(advanced, "reading-sessions.json").writeText("{\"sessions\":[{\"bookName\":\"网页书\",\"seconds\":65,\"endTime\":1000}]}")
        val archive = temp.resolve("stats-backup.zip").toFile()
        BackupArchive.create(archive, mapOf("data" to data, "advanced" to advanced))
        java.util.zip.ZipFile(archive).use { zip ->
            val rows = io.vertx.core.json.JsonArray(zip.getInputStream(zip.getEntry("readRecord.json")).reader().readText())
            assertEquals(2, rows.size())
            assertEquals(65000L, rows.getJsonObject(1).getLong("readTime"))
        }
        BackupArchive.restore(archive, mapOf("data" to data, "advanced" to advanced))
        assertEquals(1, io.vertx.core.json.JsonArray(File(data, "readRecord.json").readText()).size(), "generated compatibility totals must not duplicate restored sessions")
    }
    @Test fun tamperedFuAliasCannotReplaceVersionedOriginalData() {
        val data = temp.resolve("alias-data").toFile().apply { mkdirs() }
        File(data, "rssStar.json").writeText("[]")
        val archive = versionedArchive(linkedMapOf("data/rssStar.json" to "[]".toByteArray(), "rssStar.json" to "[{\"bad\":true}]".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.restore(archive, mapOf("data" to data)) }
        assertEquals("[]", File(data, "rssStar.json").readText())
    }
    @Test fun fuBusinessEntitiesAreRestoredAndVersionedBackupsExposeLegacyTopLevelNames() {
        val data = temp.resolve("fu-data").toFile().apply { mkdirs() }
        val names = listOf("rssStar.json", "sourceSub.json", "searchHistory.json", "dictRule.json", "readRecord.json")
        val legacy = temp.resolve("fu.zip").toFile()
        ZipOutputStream(legacy.outputStream()).use { zip -> names.forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write("[{\"fixture\":true}]".toByteArray()); zip.closeEntry()
        } }
        BackupArchive.restore(legacy, mapOf("data" to data))
        names.forEach { assertTrue(File(data, it).exists(), it) }
        val compatible = temp.resolve("compatible.zip").toFile()
        BackupArchive.create(compatible, mapOf("data" to data))
        java.util.zip.ZipFile(compatible).use { zip -> names.forEach { name ->
            assertNotNull(zip.getEntry(name), "FU reads top-level $name")
            assertEquals("[{\"fixture\":true}]", zip.getInputStream(zip.getEntry(name)).reader().readText())
        } }
        names.forEach { File(data, it).writeText("[]") }
        BackupArchive.restore(compatible, mapOf("data" to data))
        names.forEach { assertEquals("[{\"fixture\":true}]", File(data, it).readText()) }
    }
    @Test fun backupCopiesAllEntitiesAndRestoresAfterValidation() {
        val data = temp.resolve("data").toFile().apply { mkdirs() }
        File(data, "bookGroup.json").writeText("[{\"id\":1}]")
        File(data, "bookmark.json").writeText("[{\"bookmarkType\":1}]")
        val advanced = temp.resolve("advanced").toFile().apply { mkdirs() }
        File(advanced, "ai-config.json").writeText("{\"apiKey\":\"private\"}")
        File(advanced, "reading-sessions.json").writeText("[]")
        val archive = temp.resolve("backup.zip").toFile()
        val roots = mapOf("data" to data, "advanced" to advanced)
        BackupArchive.create(archive, roots)
        assertTrue(File(data, "bookGroup.json").exists(), "backup must never move active files")
        File(data, "bookGroup.json").writeText("[]")
        File(advanced, "ai-config.json").writeText("{}")
        BackupArchive.restore(archive, roots)
        assertEquals("[{\"id\":1}]", File(data, "bookGroup.json").readText())
        assertEquals("private", JsonObject(File(advanced, "ai-config.json").readText()).getString("apiKey"))
    }
    @Test fun traversalTamperingAndUnsupportedVersionCannotWritePartialData() {
        val data = temp.resolve("data").toFile().apply { mkdirs() }
        val existing = File(data, "bookshelf.json").apply { writeText("[]") }
        val archive = temp.resolve("bad.zip").toFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("bookshelf.json")); zip.write("[{\"name\":\"bad\"}]".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("../escape.json")); zip.write("{}".toByteArray()); zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.restore(archive, mapOf("data" to data)) }
        assertEquals("[]", existing.readText())
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write("{\"version\":99,\"files\":[]}".toByteArray()); zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.restore(archive, mapOf("data" to data)) }
        assertEquals("[]", existing.readText())
    }
    @Test fun legacyLegadoEntitiesAreAcceptedWithoutUnknownFiles() {
        val data = temp.resolve("legacy").toFile().apply { mkdirs() }
        val archive = temp.resolve("legacy.zip").toFile()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("replaceRule.json")); zip.write("[]".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("unknown-android.json")); zip.write("{}".toByteArray()); zip.closeEntry()
        }
        BackupArchive.restore(archive, mapOf("data" to data))
        assertEquals("[]", File(data, "replaceRule.json").readText())
        assertFalse(File(data, "unknown-android.json").exists())
    }

    @Test fun versionedDataRegionRejectsNestedJsonAndNonJsonBeforeReplacingExistingData() {
        val data = temp.resolve("strict-data").toFile().apply { mkdirs() }
        val existing = File(data, "userConfig.json").apply { writeText("{\"preserved\":true}") }
        for (invalid in listOf("data/nested/userConfig.json", "data/avatar.png")) {
            val archive = versionedArchive(linkedMapOf("data/userConfig.json" to "{}".toByteArray(), invalid to "{}".toByteArray()))
            assertThrows(IllegalArgumentException::class.java) { BackupArchive.restore(archive, mapOf("data" to data)) }
            assertEquals("{\"preserved\":true}", existing.readText())
            assertFalse(File(data, "nested").exists())
            assertFalse(File(data, "avatar.png").exists())
        }
    }

    @Test fun sourceStateRootsCanBeReboundAfterTheArchiveIsReadWithinLimits() {
        val data = temp.resolve("rebound-data").toFile()
        val state = temp.resolve("rebound-source").toFile()
        val archive = versionedArchive(linkedMapOf("data/bookSource.json" to "[{\"bookSourceUrl\":\"https://fixture\"}]".toByteArray(),
            "source-imported/state" to "private source state".toByteArray()), JsonObject().put("sourceNamespaces", JsonObject().put("https://fixture", "imported")))
        val called = AtomicBoolean()
        BackupArchive.restore(archive, { manifest, values ->
            called.set(true)
            assertEquals("imported", manifest.getJsonObject("metadata").getJsonObject("sourceNamespaces").getString("https://fixture"))
            assertEquals("[{\"bookSourceUrl\":\"https://fixture\"}]", String(values.getValue("data/bookSource.json")))
            mapOf("data" to data, "source-imported" to state)
        })
        assertTrue(called.get())
        assertEquals("private source state", File(state, "state").readText())
        assertTrue(File(data, "bookSource.json").isFile)
    }

    @Test fun oversizedLocalResourceIsRejectedBeforeBackupFileCreation() {
        val assets = temp.resolve("large-assets").toFile().apply { mkdirs() }
        val resource = File(assets, "oversized-font.woff2")
        RandomAccessFile(resource, "rw").use { it.setLength(257L * 1024 * 1024) }
        val output = temp.resolve("oversized-local.zip").toFile()
        val failure = assertThrows(IllegalArgumentException::class.java) { BackupArchive.create(output, mapOf("assets" to assets)) }
        assertTrue(failure.message.orEmpty().contains("256 MiB"))
        assertFalse(output.exists())
        assertTrue(resource.isFile, "Rejected backups must retain their original resources")
    }

    @Test fun inflatedManifestAndBookSourceAreRejectedBeforeMetadataOrSourceParsing() {
        val archive = temp.resolve("oversized.zip").toFile()
        val data = temp.resolve("unchanged").toFile().apply { mkdirs() }
        val existing = File(data, "userConfig.json").apply { writeText("{\"preserved\":true}") }
        val block = ByteArray(1024 * 1024) { ' '.code.toByte() }
        for (oversized in listOf("manifest.json", "data/bookSource.json")) {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(oversized))
                repeat(257) { zip.write(block) }
                zip.closeEntry()
            }
            val called = AtomicBoolean()
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BackupArchive.restore(archive, { _, _ -> called.set(true); mapOf("data" to data) })
            }
            assertTrue(failure.message.orEmpty().contains("256 MiB"))
            assertFalse(called.get(), "metadata and source readers must run only after the bounded archive read")
            assertEquals("{\"preserved\":true}", existing.readText())
        }
    }

    private fun versionedArchive(values: Map<String, ByteArray>, metadata: JsonObject = JsonObject()): File {
        val archive = temp.resolve("versioned.zip").toFile()
        val files = io.vertx.core.json.JsonArray(values.map { (path, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            JsonObject().put("path", path).put("size", bytes.size).put("sha256", hash)
        })
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(JsonObject().put("version", 2).put("metadata", metadata).put("files", files).encode().toByteArray()); zip.closeEntry()
            values.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        return archive
    }
}
