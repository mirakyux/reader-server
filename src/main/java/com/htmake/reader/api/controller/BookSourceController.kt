package com.htmake.reader.api.controller

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.model.webBook.WebBook
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler;
import io.github.oshai.kotlinlogging.KotlinLogging
import com.htmake.reader.config.AppConfig
import com.htmake.reader.config.BookConfig
import io.legado.app.constant.DeepinkBookSource
import com.htmake.reader.utils.error
import com.htmake.reader.utils.success
import com.htmake.reader.utils.getStorage
import com.htmake.reader.utils.saveStorage
import com.htmake.reader.utils.asJsonArray
import com.htmake.reader.utils.asJsonObject
import com.htmake.reader.utils.toDataClass
import com.htmake.reader.utils.toMap
import com.htmake.reader.utils.fillData
import com.htmake.reader.utils.getWorkDir
import com.htmake.reader.utils.getRandomString
import com.htmake.reader.utils.genEncryptedPassword
import com.htmake.reader.entity.User
import com.htmake.reader.utils.SpringContextUtils
import com.htmake.reader.utils.deleteRecursively
import com.htmake.reader.utils.unzip
import com.htmake.reader.utils.zip
import com.htmake.reader.utils.jsonEncode
import com.htmake.reader.utils.getRelativePath
import com.htmake.reader.verticle.RestVerticle
import org.springframework.stereotype.Component
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.core.http.HttpMethod
import com.htmake.reader.api.ReturnData
import io.legado.app.utils.MD5Utils
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.URL;
import java.util.UUID;
import io.vertx.ext.web.client.WebClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.io.File
import java.lang.Runtime
import kotlin.collections.mutableMapOf
import kotlin.system.measureTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat;
import io.legado.app.utils.EncoderUtils
import io.legado.app.model.rss.Rss
import org.springframework.scheduling.annotation.Scheduled
import io.legado.app.model.localBook.LocalBook
import java.nio.file.Paths
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope

private val logger = KotlinLogging.logger {}

class BookSourceController(coroutineContext: CoroutineContext): BaseController(coroutineContext) {
    suspend fun parseBookSource(context: RoutingContext): ReturnData = sourceOperation(context) {
        val content = context.body().asJsonObject().getString("content") ?: error("书源内容不能为空")
        require(content.isNotBlank()) { "书源内容不能为空" }
        io.legado.app.help.SourceAnalyzer.jsonToBookSources(content).getOrThrow()
    }

    private suspend fun sourceOperation(context: RoutingContext, action: () -> Any): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try { ReturnData().setData(withContext(Dispatchers.IO) { action() }) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "书源操作失败") }
    }

    private suspend fun requestSource(context: RoutingContext): BookSource {
        val url = context.body().asJsonObject().getString("bookSourceUrl") ?: error("书源链接不能为空")
        val user = getUserNameSpace(context)
        val json = getUserBookSourceJson(user)?.firstOrNull { item ->
            (item as? JsonObject)?.getString("bookSourceUrl") == url
        } as? JsonObject ?: error("书源信息不存在")
        return BookSource.fromJson(json.encode(), user).getOrThrow()
    }

    suspend fun getBookSourceLoginUi(context: RoutingContext): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try {
            val source = requestSource(context)
            val state = context.body().asJsonObject().getJsonObject("state")?.map ?: emptyMap()
            sourceOperation(context) { io.legado.app.model.jsSource.SourceLogin.ui(source, state) }
        } catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "登录表单读取失败") }
    }

    suspend fun loginBookSource(context: RoutingContext): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try {
            val source = requestSource(context)
            val body = context.body().asJsonObject()
            val form = body.getJsonObject("form")?.map ?: emptyMap()
            val state = body.getJsonObject("state")?.map ?: emptyMap()
            sourceOperation(context) { io.legado.app.model.jsSource.SourceLogin.login(source, form, body.getString("action"), state) }
        } catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "书源登录失败") }
    }

    suspend fun getBookSourceCookies(context: RoutingContext): ReturnData = cookieOperation(context, "get")
    suspend fun saveBookSourceCookies(context: RoutingContext): ReturnData = cookieOperation(context, "save")
    suspend fun clearBookSourceCookies(context: RoutingContext): ReturnData = cookieOperation(context, "clear")

    private suspend fun cookieOperation(context: RoutingContext, action: String): ReturnData {
        if (!checkAuth(context)) return ReturnData().setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        return try {
            val source = requestSource(context)
            sourceOperation(context) {
                val cookies = io.legado.app.help.http.CookieStore.forSource(source)
                when (action) {
                    "save" -> cookies.setCookie(source.getKey(), context.body().asJsonObject().getString("cookie") ?: "")
                    "clear" -> cookies.clear()
                }
                if (action == "save" || action == "clear") io.legado.app.help.source.BrowserBridge.clearCookies(source)
                mapOf("cookie" to cookies.getCookie(source.getKey()))
            }
        } catch (error: Exception) { ReturnData().setErrorMsg(error.message ?: "Cookie 操作失败") }
    }

    private var webClient: WebClient

    init {
        webClient = SpringContextUtils.getBean("webClient", WebClient::class.java)
    }

    fun getUserBookSourceJson(userNameSpace: String): JsonArray? = userStorageTransaction(userNameSpace, "bookSource") {
        var bookSourceList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookSource"))
        if (bookSourceList == null && !userNameSpace.equals("default")) {
            // 用户书源文件不存在，拷贝系统书源
            var systemBookSourceList: JsonArray? = asJsonArray(getUserStorage("default", "bookSource"))
            if (systemBookSourceList != null) {
                saveUserStorage(userNameSpace, "bookSource", systemBookSourceList.getList())
                bookSourceList = systemBookSourceList
            }
        }
        bookSourceList
    }

    suspend fun saveBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookSource = BookSource.fromJson(context.body().asString()).getOrNull()
        if (bookSource == null) {
            return returnData.setErrorMsg("参数错误")
        }
        // val bookSource = context.body().asJsonObject().mapTo(BookSource::class.java)

        var userNameSpace = getUserNameSpace(context)
        userStorageTransaction(userNameSpace, "bookSource") {
            var bookSourceList = getUserBookSourceJson(userNameSpace)
            if (bookSourceList == null) {
                bookSourceList = JsonArray()
            }
            // 遍历判断书本是否存在
            var existIndex: Int = -1
            for (i in 0 until bookSourceList.size()) {
                var _bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                if (_bookSource.bookSourceUrl.equals(bookSource.bookSourceUrl)) {
                    existIndex = i
                    break;
                }
            }
            if (existIndex >= 0) {
                var sourceList = bookSourceList.getList()
                sourceList.set(existIndex, JsonObject.mapFrom(bookSource))
                bookSourceList = JsonArray(sourceList)
            } else {
                bookSourceList.add(JsonObject.mapFrom(bookSource))
            }

            // logger.info("bookSourceList: {}", bookSourceList)
            saveUserStorage(userNameSpace, "bookSource", bookSourceList)
        }
        return returnData.setData("")
    }

    suspend fun saveBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookSourceJsonArray = context.body().asJsonArray()
        if (bookSourceJsonArray == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var userNameSpace = getUserNameSpace(context)
        userStorageTransaction(userNameSpace, "bookSource") {
            var bookSourceList = getUserBookSourceJson(userNameSpace)
            if (bookSourceList == null) {
                bookSourceList = JsonArray()
            }
            for (k in 0 until bookSourceJsonArray.size()) {
                val bookSource = BookSource.fromJson(bookSourceJsonArray.getJsonObject(k).toString()).getOrNull()
                if (bookSource == null) {
                    continue
                }
                // var bookSource = bookSourceJsonArray.getJsonObject(k).mapTo(BookSource::class.java)
                // 遍历判断书本是否存在
                var existIndex: Int = -1
                for (i in 0 until bookSourceList!!.size()) {
                    var _bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                    if (_bookSource.bookSourceUrl.equals(bookSource.bookSourceUrl)) {
                        existIndex = i
                        break;
                    }
                }
                if (existIndex >= 0) {
                    var sourceList = bookSourceList.getList()
                    sourceList.set(existIndex, JsonObject.mapFrom(bookSource))
                    bookSourceList = JsonArray(sourceList)
                } else {
                    bookSourceList.add(JsonObject.mapFrom(bookSource))
                }
            }

            // logger.info("bookSourceList: {}", bookSourceList)
            saveUserStorage(userNameSpace, "bookSource", bookSourceList!!)
        }
        return returnData.setData("")
    }

    suspend fun getBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        var bookSourceUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookSourceUrl = context.body().asJsonObject().getString("bookSourceUrl")
        } else {
            // get 请求
            bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull() ?: ""
        }
        if (bookSourceUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书源链接不能为空")
        }

        var userNameSpace = getUserNameSpace(context)
        var bookSourceList = getUserBookSourceJson(userNameSpace)
        if (bookSourceList == null) {
            bookSourceList = JsonArray()
        }
        // 遍历判断书本是否存在
        var existIndex: Int = -1
        for (i in 0 until bookSourceList.size()) {
            var _bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
            if (_bookSource.bookSourceUrl.equals(bookSourceUrl)) {
                existIndex = i
                break;
            }
        }
        if (existIndex < 0) {
            return returnData.setErrorMsg("书源信息不存在")
        }

        return returnData.setData(bookSourceList.getJsonObject(existIndex).map)
    }

    suspend fun getBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        var simple: Int = 0
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            simple = context.body().asJsonObject().getInteger("simple", 0)
        } else {
            // get 请求
            simple = context.queryParam("simple").firstOrNull()?.toInt() ?: 0
        }
        var userNameSpace = getUserNameSpace(context)
        var bookSourceList = getUserBookSourceJson(userNameSpace)
        if (bookSourceList != null) {
            if (simple > 0) {
                var list = arrayListOf<Map<String, Any?>>()
                for (i in 0 until bookSourceList.size()) {
                    var bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                    list.add(mapOf<String, Any?>(
                        "bookSourceGroup" to bookSource.bookSourceGroup,
                        "bookSourceName" to bookSource.bookSourceName,
                        "bookSourceUrl" to bookSource.bookSourceUrl,
                        "exploreUrl" to bookSource.exploreUrl
                    ))
                }
                return returnData.setData(list)
            }
            return returnData.setData(bookSourceList.getList())
        }
        return returnData.setData(arrayListOf<Int>())
    }

    suspend fun deleteBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookSource = context.body().asJsonObject().mapTo(BookSource::class.java)

        var userNameSpace = getUserNameSpace(context)
        userStorageTransaction(userNameSpace, "bookSource") {
            var bookSourceList = getUserBookSourceJson(userNameSpace)
            if (bookSourceList == null) {
                bookSourceList = JsonArray()
            }
            // 遍历判断书本是否存在
            var existIndex: Int = -1
            for (i in 0 until bookSourceList.size()) {
                var _bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                if (_bookSource.bookSourceUrl.equals(bookSource.bookSourceUrl)) {
                    existIndex = i
                    break;
                }
            }
            if (existIndex >= 0) {
                bookSourceList.remove(existIndex)
            }

            // logger.info("bookSourceList: {}", bookSourceList)
            saveUserStorage(userNameSpace, "bookSource", bookSourceList)
        }
        return returnData.setData("")
    }

    suspend fun deleteBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookSourceJsonArray = context.body().asJsonArray()

        var userNameSpace = getUserNameSpace(context)
        userStorageTransaction(userNameSpace, "bookSource") {
            var bookSourceList = getUserBookSourceJson(userNameSpace)
            if (bookSourceList == null) {
                bookSourceList = JsonArray()
            }
            for (k in 0 until bookSourceJsonArray.size()) {
                var bookSource = bookSourceJsonArray.getJsonObject(k).mapTo(BookSource::class.java)
                // 遍历判断书本是否存在
                var existIndex: Int = -1
                for (i in 0 until bookSourceList.size()) {
                    var _bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                    if (_bookSource.bookSourceUrl.equals(bookSource.bookSourceUrl)) {
                        existIndex = i
                        break;
                    }
                }
                if (existIndex >= 0) {
                    bookSourceList.remove(existIndex)
                }
            }

            // logger.info("bookSourceList: {}", bookSourceList)
            saveUserStorage(userNameSpace, "bookSource", bookSourceList)
        }
        return returnData.setData("")
    }

    suspend fun deleteAllBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var userNameSpace = getUserNameSpace(context)
        saveUserStorage(userNameSpace, "bookSource", JsonArray())
        return returnData.setData("")
    }

    suspend fun setAsDefaultBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!checkManagerAuth(context)) {
            return returnData.setData("NEED_SECURE_KEY").setErrorMsg("请输入管理密码")
        }
        var username = context.body().asJsonObject().getString("username")
        var bookSourceList: JsonArray? = asJsonArray(getUserStorage(username, "bookSource"))
        if (bookSourceList == null) {
            return returnData.setErrorMsg("用户书源不存在")
        }

        // 保存为默认书源
        saveUserStorage("default", "bookSource", bookSourceList.getList())
        return returnData.setData("设置默认书源成功")
    }

    suspend fun readSourceFile(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (context.fileUploads() == null || context.fileUploads().isEmpty()) {
            return returnData.setErrorMsg("请上传文件")
        }
        var sourceList = JsonArray()
        context.fileUploads().forEach {
            // logger.info("readSourceFile: {}", it.uploadedFileName())
            var file = File(it.uploadedFileName())
            if (file.exists()) {
                sourceList.add(file.readText())
                file.delete()
            }
        }
        return returnData.setData(sourceList.getList())
    }

    suspend fun readRemoteSourceFile(context: RoutingContext) {
        val returnData = ReturnData()
        var url: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            url = context.body().asJsonObject().getString("url") ?: ""
        } else {
            // get 请求
            url = context.queryParam("url").firstOrNull() ?: ""
        }
        if (url.isNullOrEmpty()) {
            context.success(returnData.setErrorMsg("请输入远程书源链接"))
            return
        }

        launch(Dispatchers.IO) {
            webClient.getAbs(url).timeout(3000).send().onComplete {
                var body = it.result()?.bodyAsString()
                if (body != null) {
                    context.success(returnData.setData(arrayListOf(body)))
                } else {
                    context.success(returnData.setErrorMsg("远程书源链接错误"))
                }
            }
        }
    }

    suspend fun deleteUserBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (!checkManagerAuth(context)) {
            return returnData.setData("NEED_SECURE_KEY").setErrorMsg("请输入管理密码")
        }
        val userJsonArray = context.body().asJsonArray()
        for (i in 0 until userJsonArray.size()) {
            var username = userJsonArray.getString(i)
            var userBookSourceFile = File(getWorkDir("storage", "data", username, "bookSource.json"))
            // 删除用户书源文件，恢复默认书源
            if (userBookSourceFile.exists()) {
                userBookSourceFile.deleteRecursively()
            }
        }
        return returnData.setData("删除书源成功")
    }

    suspend fun deleteBookSourcesFile(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var userNameSpace = getUserNameSpace(context)
        var userBookSourceFile = File(getWorkDir("storage", "data", userNameSpace, "bookSource.json"))
        // 删除用户书源文件，恢复默认书源
        if (userBookSourceFile.exists()) {
            userBookSourceFile.deleteRecursively()
        }
        return returnData.setData("")
    }
}
