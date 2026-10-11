package com.htmake.reader.api.service.advanced

import com.google.gson.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.util.TreeMap

class ReadingStatistics(private val store: AdvancedStore, private val legacyFiles: com.htmake.reader.api.service.UserDataFiles? = null) {
    fun legacyRecords(user: String): com.google.gson.JsonArray = legacyFiles?.read(user, "readRecord.json")?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray()
    fun delete(user: String, input: JsonObject): Int {
        var removed = 0
        val all = input.flag("all")
        val bookUrl = input.string("bookUrl")
        val id = input.string("id")
        require(all || bookUrl.isNotBlank() || id.isNotBlank() || input.has("legacyBookName")) { "请选择需要删除的统计记录" }
        store.update(user, "reading-sessions.json") { saved ->
            jsonObject("sessions" to saved.array("sessions").filter {
                val row = it.asJsonObject
                val remove = all || (bookUrl.isNotBlank() && row.string("bookUrl") == bookUrl) || (id.isNotBlank() && row.string("id") == id)
                if (remove) removed++
                !remove
            })
        }
        if (all || input.has("legacyBookName")) legacyFiles?.update(user, "readRecord.json") { saved ->
            advancedJson.toJsonTree(saved.asJsonArray.filter {
                val remove = all || it.asJsonObject.string("bookName") == input.string("legacyBookName")
                if (remove) removed++
                !remove
            })
        }
        return removed
    }
    fun save(user: String, input: JsonObject): JsonObject {
        validId(input.string("id"))
        require(input.string("bookUrl").length in 1..4096) { "请输入书籍地址" }
        val start = input.long("startTime"); val end = input.long("endTime")
        require(start > 0 && end >= start && end <= System.currentTimeMillis() + 300000 && end - start <= 86400000) { "阅读会话时间无效" }
        val seconds = input.long("seconds", (end - start) / 1000)
        val characters = input.long("characters")
        require(seconds in 0..((end - start) / 1000) && characters in 0..2000000) { "阅读时长或字数无效" }
        val session = jsonObject("id" to input.string("id"), "bookUrl" to input.string("bookUrl"), "startTime" to start,
            "endTime" to end, "seconds" to seconds, "characters" to characters, "chapterIndex" to input.long("chapterIndex", -1),
            "bookName" to input.string("bookName").take(200), "author" to input.string("author").take(200))
        store.update(user, "reading-sessions.json") { saved ->
            val sessions = saved.array("sessions").map { it.asJsonObject }.toMutableList()
            val previous = sessions.indexOfFirst { it.string("id") == session.string("id") }
            if (previous >= 0) {
                val old = sessions[previous]
                require(old.string("bookUrl") == session.string("bookUrl") && old.long("startTime") == start) { "会话标识已用于其他阅读记录" }
                require(seconds >= old.long("seconds") && characters >= old.long("characters") && end >= old.long("endTime")) { "阅读记录不可倒退" }
                sessions[previous] = session
            } else sessions.add(session)
            jsonObject("sessions" to sessions.sortedByDescending { it.long("endTime") }.take(10000))
        }
        return session
    }

    private data class Counts(var seconds: Long = 0, var characters: Long = 0, var sessions: Int = 0) {
        fun json() = jsonObject("seconds" to seconds, "characters" to characters, "sessions" to sessions)
    }
    fun summary(user: String, zone: String = ZoneId.systemDefault().id): JsonObject {
        val timezone = ZoneId.of(zone)
        val days = TreeMap<String, Counts>()
        val books = linkedMapOf<String, Pair<Counts, TreeMap<String, Counts>>>()
        val labels = linkedMapOf<String, Pair<String, String>>()
        var totalSeconds = 0L; var totalCharacters = 0L
        val sessions = store.read(user, "reading-sessions.json").array("sessions")
        for (element in sessions) {
            val session = element.asJsonObject
            val book = books.getOrPut(session.string("bookUrl")) { Counts() to TreeMap() }
            labels.putIfAbsent(session.string("bookUrl"), session.string("bookName") to session.string("author"))
            val seconds = session.long("seconds"); val characters = session.long("characters")
            totalSeconds += seconds; totalCharacters += characters
            book.first.seconds += seconds; book.first.characters += characters; book.first.sessions++
            val start = session.long("startTime"); val end = session.long("endTime")
            var cursor = start; var assignedSeconds = 0L; var assignedCharacters = 0L
            do {
                val date = Instant.ofEpochMilli(cursor).atZone(timezone).toLocalDate()
                val boundary = date.plusDays(1).atStartOfDay(timezone).toInstant().toEpochMilli().coerceAtMost(end)
                val final = boundary == end
                val partSeconds = if (final) seconds - assignedSeconds else seconds * (boundary - cursor) / (end - start).coerceAtLeast(1)
                val partCharacters = if (final) characters - assignedCharacters else characters * (boundary - cursor) / (end - start).coerceAtLeast(1)
                for (counts in listOf(days.getOrPut(date.toString()) { Counts() }, book.second.getOrPut(date.toString()) { Counts() })) {
                    counts.seconds += partSeconds; counts.characters += partCharacters; counts.sessions++
                }
                assignedSeconds += partSeconds; assignedCharacters += partCharacters; cursor = boundary
            } while (cursor < end)
        }
        fun dayList(values: TreeMap<String, Counts>) = values.map { (date, counts) -> counts.json().also { it.addProperty("date", date) } }
        // FU only records cumulative milliseconds by device/book. Do not invent dates,
        // characters or reading sessions when importing these historical totals.
        val legacy = legacyRecords(user).filter { it.isJsonObject }.map { element -> element.asJsonObject.let { row ->
            jsonObject("deviceId" to row.string("deviceId"), "bookName" to row.string("bookName"), "seconds" to row.long("readTime").coerceAtLeast(0) / 1000, "lastRead" to row.long("lastRead"))
        } }
        val legacySeconds = legacy.sumOf { it.long("seconds") }
        return jsonObject("days" to dayList(days), "books" to books.map { (url, values) -> values.first.json().also {
            it.addProperty("bookName", labels[url]?.first.orEmpty()); it.addProperty("author", labels[url]?.second.orEmpty())
            it.addProperty("bookUrl", url); it.add("days", advancedJson.toJsonTree(dayList(values.second)))
        } }, "timezone" to timezone.id, "totalSeconds" to totalSeconds + legacySeconds, "recordedSeconds" to totalSeconds,
            "legacySeconds" to legacySeconds, "legacyRecords" to legacy, "totalCharacters" to totalCharacters, "totalSessions" to sessions.size())
    }
}
