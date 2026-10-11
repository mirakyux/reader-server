package io.legado.app.help.source

import io.legado.app.utils.ACache
import com.htmake.reader.init.appCtx
import java.io.File
import java.security.MessageDigest

/** Source state and downloaded files stay isolated; FU script cache is shared only within one user. */
object SourceStorage {
    fun namespace(user: String, source: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$user\u0000$source".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun sharedCache(user: String): SourceCache = SourceCache(namespace(user, "shared-source-cache"), separateFiles = true)

    fun file(root: File, namespace: String, path: String): File {
        val cacheRoot = root.canonicalFile
        val sourceRoot = File(cacheRoot, "source/$namespace").canonicalFile
        require(sourceRoot.toPath().startsWith(cacheRoot.toPath()) && sourceRoot != cacheRoot) { "书源缓存目录越界" }
        val target = File(sourceRoot, path.trimStart('/', '\\')).canonicalFile
        require(target.toPath().startsWith(sourceRoot.toPath()) && target != sourceRoot) {
            "书源文件路径超出缓存目录"
        }
        return target
    }
}

class SourceCache @JvmOverloads constructor(private val namespace: String, private val separateFiles: Boolean = false) {
    private val cache get() = ACache.get(File(appCtx.cacheDir, "source-state/$namespace"))
    private val fileCache get() = if (separateFiles) {
        ACache.get(File(appCtx.cacheDir, "source-file-cache/$namespace"))
    } else cache
    @JvmOverloads fun put(key: String, value: Any, saveTime: Int = 0) {
        when (value) {
            is ByteArray -> if (saveTime != 0) fileCache.put(key, value, saveTime) else fileCache.put(key, value)
            else -> {
                val text = value.toString()
                putMemory(key, text)
                if (saveTime != 0) cache.put(key, text, saveTime) else cache.put(key, text)
            }
        }
    }

    /** FU memory entries retain their original type and have no TTL. */
    fun putMemory(key: String, value: Any) {
        val entry = MemoryEntry(value, 40L + 2L * value.toString().length)
        synchronized(memory) {
            val scopedKey = namespace to key
            memory.remove(scopedKey)?.let { memorySize -= it.size }
            memory[scopedKey] = entry
            memorySize += entry.size
            val iterator = memory.entries.iterator()
            while (memorySize > MAX_MEMORY_BYTES && iterator.hasNext()) {
                memorySize -= iterator.next().value.size
                iterator.remove()
            }
        }
    }

    fun getFromMemory(key: String): Any? = synchronized(memory) { memory[namespace to key]?.value }

    fun deleteMemory(key: String) {
        synchronized(memory) { memory.remove(namespace to key)?.let { memorySize -= it.size } }
    }

    fun get(key: String): String? = (getFromMemory(key) as? String)
        ?: get(key, true)?.also { putMemory(key, it) }

    fun get(key: String, onlyDisk: Boolean): String? = if (onlyDisk) cache.getAsString(key) else get(key)
    fun getInt(key: String): Int? = getFromMemory(key) as? Int ?: get(key, true)?.toIntOrNull()
    fun getLong(key: String): Long? = getFromMemory(key) as? Long ?: get(key, true)?.toLongOrNull()
    fun getDouble(key: String): Double? = getFromMemory(key) as? Double ?: get(key, true)?.toDoubleOrNull()
    fun getFloat(key: String): Float? = getFromMemory(key) as? Float ?: get(key, true)?.toFloatOrNull()
    fun getByteArray(key: String): ByteArray? = fileCache.getAsBinary(key)
    fun delete(key: String) {
        deleteMemory(key)
        cache.remove(key)
        if (separateFiles) fileCache.remove(key)
    }
    @JvmOverloads fun putFile(key: String, value: String, saveTime: Int = 0) {
        if (saveTime != 0) fileCache.put(key, value, saveTime) else fileCache.put(key, value)
    }
    fun getFile(key: String): String? = fileCache.getAsString(key)

    private data class MemoryEntry(val value: Any, val size: Long)

    private companion object {
        const val MAX_MEMORY_BYTES = 50L * 1024 * 1024
        val memory = LinkedHashMap<Pair<String, String>, MemoryEntry>(16, 0.75f, true)
        var memorySize = 0L
    }
}
