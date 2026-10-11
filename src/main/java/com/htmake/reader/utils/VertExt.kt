package com.htmake.reader.utils

import com.google.common.base.Throwables
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.github.oshai.kotlinlogging.KotlinLogging
import com.htmake.reader.entity.BasicError
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.File
import java.nio.file.Paths
import com.htmake.reader.config.AppConfig
import com.google.gson.reflect.TypeToken
import kotlin.reflect.KProperty1
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import com.htmake.reader.api.service.UserDataFiles
import com.htmake.reader.api.service.advanced.AdvancedStore
import com.htmake.reader.api.service.ChapterCache
import io.legado.app.data.entities.BookChapter
import java.nio.file.Files

/**
 * @Auther: zoharSoul
 * @Date: 2019-05-21 16:17
 * @Description:
 */
val logger = KotlinLogging.logger {}

val gson = GsonBuilder().disableHtmlEscaping().create()
val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

var storageFinalPath = ""
var workDirPath = ""
var workDirInit = false

fun RoutingContext.success(any: Any?) {
    val toJson: String = if (any is JsonObject) {
        any.toString()
    } else {
        gson.toJson(any)
    }
    this.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(toJson)
}

fun RoutingContext.error(throwable: Throwable) {
    val path = this.request().path()
    val basicError = BasicError(
            "Internal Server Error",
            throwable.toString(),
            throwable.message.toString(),
            path,
            500,
            System.currentTimeMillis()
    )

    val errorJson = gson.toJson(basicError)
    logger.error(throwable) { "Internal Server Error" }
    logger.error { errorJson }

    this.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .setStatusCode(500)
            .end(errorJson)
}

fun getWorkDir(subPath: String = ""): String {
    if (!workDirInit && workDirPath.isEmpty()) {
        var osName = System.getProperty("os.name")
        var currentDir = System.getProperty("user.dir")
        logger.info { "osName: ${osName} currentDir: ${currentDir}" }
        // MacOS 存放目录为用户目录
        if (osName.startsWith("Mac OS", true) && !currentDir.startsWith("/Users/")) {
            workDirPath = Paths.get(System.getProperty("user.home"), ".reader").toString()
        } else {
            workDirPath = currentDir
        }
        workDirInit = true
    }
    var path = Paths.get(workDirPath, subPath);

    return path.toString();
}

fun getWorkDir(vararg subDirFiles: String): String {
    return getWorkDir(getRelativePath(*subDirFiles))
}

fun getRelativePath(vararg subDirFiles: String): String {
    val path = StringBuilder("")
    subDirFiles.forEach {
        if (it.isNotEmpty()) {
            path.append(File.separator).append(it)
        }
    }
    return path.toString().let{
        if (it.startsWith("/")) {
            it.substring(1)
        } else {
            it
        }
    }
}

fun getStoragePath(): String {
    if (storageFinalPath.isNotEmpty()) {
        return storageFinalPath;
    }
    var appConfig = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
    var storageDir = File("storage")
    if (appConfig != null) {
        // logger.info("storagePath from appConfig: {}", appConfig.storagePath)
        storageDir = File(appConfig.storagePath)
    }
    if (storageDir.isAbsolute()) {
        return storageDir.toString();
    }
    var storagePath = getWorkDir(storageDir.toString())
    if (appConfig != null) {
        storageFinalPath = storagePath
    }
    return storagePath;
}

private fun storageFile(vararg name: String): File {
    require(name.isNotEmpty())
    return Paths.get(getStoragePath(), *name.copyOfRange(0, name.size - 1), "${name.last()}.json").toFile()
}

fun <T> storageTransaction(vararg name: String, action: () -> T): T =
    synchronized(UserDataFiles.lockFor(storageFile(*name).toPath()), action)

fun saveStorage(vararg name: String, value: Any, pretty: Boolean = false) = storageTransaction(*name) {
    val toJson: String = if (value is JsonObject || value is JsonArray) {
        value.toString()
    } else if (pretty) {
        prettyGson.toJson(value)
    } else {
        gson.toJson(value)
    }

    val file = storageFile(*name)
    logger.info { "Save file to storage name: ${name} path: ${file.absoluteFile}" }

    AdvancedStore.atomicWrite(file.toPath(), toJson.toByteArray(Charsets.UTF_8))
}

fun getStorage(vararg name: String): String? = storageTransaction(*name) {
    val file = storageFile(*name)
    logger.info { "Read file from storage name: ${name} path: ${file.absoluteFile}" }
    if (!file.exists()) {
        return@storageTransaction null
    }
    file.readText(Charsets.UTF_8)
}

/** New writes use the configured root; legacy files are copied once and remain recoverable. */
fun storageDirectory(vararg parts: String): File {
    val directory = Paths.get(getStoragePath(), *parts).toFile()
    val legacy = Paths.get(getWorkDir("storage"), *parts).toFile()
    val imported = File(directory.parentFile, ".${directory.name}.legacy-imported")
    if (directory.canonicalFile != legacy.canonicalFile && legacy.isDirectory && !imported.exists()) {
        synchronized(UserDataFiles.lockFor(directory.toPath())) {
            if (!imported.exists()) {
                val catalogs = legacy.listFiles()?.filter {
                    it.isDirectory && it.name.matches(Regex("[a-fA-F0-9]{32}")) && File(legacy, "${it.name}.json").isFile
                }.orEmpty()
                val cacheNames = catalogs.map { it.name }.toSet()
                // A legacy body belongs to its legacy catalog, which may differ from a newer configured catalog.
                catalogs.forEach { cache ->
                    fun chapters(file: File): List<BookChapter> {
                        val array = JsonArray(file.readText(Charsets.UTF_8))
                        return (0 until array.size()).map { array.getJsonObject(it).mapTo(BookChapter::class.java) }
                    }
                    val oldCatalog = chapters(File(legacy, "${cache.name}.json"))
                    val targetCatalog = File(directory, "${cache.name}.json")
                    val currentCatalog = if (targetCatalog.isFile) chapters(targetCatalog) else oldCatalog
                    Files.createDirectories(directory.parentFile.toPath())
                    val staging = Files.createTempDirectory(directory.parentFile.toPath(), ".legacy-cache-").toFile()
                    try {
                        cache.copyRecursively(staging, overwrite = true)
                        ChapterCache.reconcile(staging, oldCatalog, currentCatalog)
                        val targetCache = File(directory, cache.name)
                        val occupied = targetCache.listFiles()?.filter { it.name.matches(Regex("\\d+\\.txt")) }
                            ?.map { it.name.substringBefore('.') }?.toSet().orEmpty()
                        staging.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relative = file.relativeTo(staging)
                            val target = File(targetCache, relative.path)
                            val belongsToOccupiedBody = relative.parent == null && file.name.substringBefore('.') in occupied
                            if (!belongsToOccupiedBody && !target.exists()) AdvancedStore.atomicWrite(target.toPath(), file.readBytes())
                        }
                    } finally { staging.deleteRecursively() }
                }
                legacy.walkTopDown().onEnter { it == legacy || it.parentFile != legacy || it.name !in cacheNames }
                    .filter { it.isFile }.forEach { file ->
                    val target = File(directory, file.relativeTo(legacy).path)
                    if (!target.exists()) AdvancedStore.atomicWrite(target.toPath(), file.readBytes())
                }
                AdvancedStore.atomicWrite(imported.toPath(), byteArrayOf())
            }
        }
    }
    return directory
}

fun asJsonArray(value: Any?): JsonArray? {
    if (value is JsonArray) {
        return value
    } else if (value is String) {
        return JsonArray(value)
    }
    return null
}

fun asJsonObject(value: Any?): JsonObject? {
    if (value is JsonObject) {
        return value
    } else if (value is String) {
        return JsonObject(value)
    }
    return null
}

//convert a data class to a map
fun <T> T.serializeToMap(): Map<String, Any> {
    return convert()
}

//convert string to a map
fun <T> T.toMap(): Map<String, Any> {
    return convert()
}

//convert a map to a data class
inline fun <reified T> Map<String, Any>.toDataClass(): T {
    return convert()
}

//convert an object of type I to type O
inline fun <I, reified O> I.convert(): O {
    val json = if (this is String) {
        this
    } else {
        gson.toJson(this)
    }
    return gson.fromJson(json, object : TypeToken<O>() {}.type)
}

@Suppress("UNCHECKED_CAST")
fun <R> readInstanceProperty(instance: Any, propertyName: String): R {
    val property = instance::class.memberProperties
                     // don't cast here to <Any, R>, it would succeed silently
                     .first { it.name == propertyName } as KProperty1<Any, *>
    // force a invalid cast exception if incorrect type here
    return property.get(instance) as R
}

@Suppress("UNCHECKED_CAST")
fun setInstanceProperty(instance: Any, propertyName: String, propertyValue: Any) {
    val property = instance::class.memberProperties
                     .first { it.name == propertyName }
    if(property is KMutableProperty<*>) {
        property.setter.call(instance, propertyValue)
    }
}

fun Book.fillData(newBook: Book, keys: List<String>): Book {
    keys.let {
        for (key in it) {
            var current = readInstanceProperty<String>(this, key)
            if (current.isNullOrEmpty()) {
                var cacheValue = readInstanceProperty<String>(newBook, key)
                if (!cacheValue.isNullOrEmpty()) {
                    setInstanceProperty(this, key, cacheValue)
                }
            }
        }
    }
    return this
}

fun getRandomString(length: Int) : String {
    val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

fun genEncryptedPassword(password: String, salt: String): String {
    return MD5Utils.md5Encode(
        MD5Utils.md5Encode(password + salt).toString() + salt
    ).toString()
}

fun jsonEncode(value: Any, pretty: Boolean = false): String {
    if (pretty) {
        return prettyGson.toJson(value)
    }
    return gson.toJson(value)
}
