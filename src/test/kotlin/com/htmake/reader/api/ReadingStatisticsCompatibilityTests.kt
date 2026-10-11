package com.htmake.reader.api

import com.htmake.reader.api.service.UserDataFiles
import com.htmake.reader.api.service.advanced.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReadingStatisticsCompatibilityTests {
    @TempDir lateinit var root: Path
    @Test fun importedFuCumulativeMillisecondsAreVisibleWithoutFabricatingDailySessions() {
        val files = UserDataFiles(root.resolve("data"))
        files.write("alice", "readRecord.json", advancedJson.toJsonTree(listOf(mapOf("deviceId" to "fu-phone", "bookName" to "历史书", "readTime" to 125000, "lastRead" to 1720000000000))))
        val stats = ReadingStatistics(AdvancedStore(root.resolve("advanced")), files)
        val summary = stats.summary("alice", "UTC")
        assertEquals(125, summary.long("totalSeconds")); assertEquals(125, summary.long("legacySeconds"))
        assertEquals(0, summary.array("days").size()); assertEquals(0, summary.long("totalSessions")); assertEquals(0, summary.long("totalCharacters"))
        assertEquals("历史书", summary.array("legacyRecords")[0].asJsonObject.string("bookName"))
        assertEquals(0, stats.summary("bob", "UTC").long("totalSeconds"))
        assertEquals(1, stats.delete("alice", jsonObject("legacyBookName" to "历史书")))
        assertEquals(0, stats.summary("alice", "UTC").long("totalSeconds"))
    }
    @Test fun deletingASelectedBookKeepsOtherBookSessions() {
        val store = AdvancedStore(root.resolve("advanced")); val stats = ReadingStatistics(store)
        store.write("alice", "reading-sessions.json", jsonObject("sessions" to listOf(
            mapOf("id" to "one", "bookUrl" to "a", "startTime" to 1720000000000L, "endTime" to 1720000010000L, "seconds" to 10, "characters" to 100),
            mapOf("id" to "two", "bookUrl" to "b", "startTime" to 1720000000000L, "endTime" to 1720000020000L, "seconds" to 20, "characters" to 200))))
        assertEquals(1, stats.delete("alice", jsonObject("bookUrl" to "a")))
        assertEquals(20, stats.summary("alice", "UTC").long("totalSeconds"))
        assertEquals(1, stats.delete("alice", jsonObject("all" to true)))
        assertEquals(0, stats.summary("alice", "UTC").long("totalSessions"))
    }
}
