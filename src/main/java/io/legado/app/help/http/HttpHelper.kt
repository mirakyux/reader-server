package io.legado.app.help.http

// import io.legado.app.help.http.cronet.CronetInterceptor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.Authenticator
import okhttp3.Response
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import java.io.IOException
import io.legado.app.model.DebugLog

private val proxyClientCache: ConcurrentHashMap<String, OkHttpClient> by lazy {
    ConcurrentHashMap()
}

val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .addHeader("Keep-Alive", "300")
                .addHeader("Connection", "Keep-Alive")
                .addHeader("Cache-Control", "no-cache")
                .build()
            chain.proceed(request)
        })
    // if (AppConfig.isCronet) {
    //     builder.addInterceptor(CronetInterceptor())
    // }

    builder.build()
}

/**
 * 缓存代理okHttp
 */
private fun getBaseProxyClient(proxy: String? = null, debugLog: DebugLog? = null): OkHttpClient {
    if (proxy.isNullOrBlank()) {
        if (debugLog == null) {
            return okHttpClient
        }
        val builder = okHttpClient.newBuilder()
        val logInterceptor = HttpLoggingInterceptor(debugLog);//创建拦截对象
        logInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
        logInterceptor.redactHeader("Cookie")
        logInterceptor.redactHeader("Set-Cookie")
        logInterceptor.redactHeader("Authorization")

        builder.addNetworkInterceptor(logInterceptor)  //设置打印拦截日志
        return builder.build()
    }
    if (debugLog == null) {
        proxyClientCache[proxy]?.let {
            return it
        }
    }
    val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
    val ms = r.findAll(proxy)
    val group = ms.first()
    var username = ""       //代理服务器验证用户名
    var password = ""       //代理服务器验证密码
    val type = if (group.groupValues[1] == "http") "http" else "socks"
    val host = group.groupValues[2]
    val port = group.groupValues[3].toInt()
    if (group.groupValues[4] != "") {
        username = group.groupValues[4].split("@")[1]
        password = group.groupValues[4].split("@")[2]
    }
    if (type != "direct" && host != "") {
        val builder = okHttpClient.newBuilder()
        if (type == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
        }
        if (username != "" && password != "") {
            val proxyAuthenticator = object: Authenticator {
                @Throws(IOException::class)
                override fun authenticate(route: Route?, response: Response): Request {
                    //设置代理服务器账号密码
                    val credential = Credentials.basic(username, password);
                    return response.request.newBuilder()
                           .header("Proxy-Authorization", credential)
                           .build();
                }
            }
            builder.proxyAuthenticator(proxyAuthenticator);
            // builder.proxyAuthenticator { _, response -> //设置代理服务器账号密码
            //     val credential: String = Credentials.basic(username, password)
            //     response.request.newBuilder()
            //         .header("Proxy-Authorization", credential)
            //         .build()
            // }
        }
        if (debugLog != null) {
            val logInterceptor = HttpLoggingInterceptor(debugLog);//创建拦截对象
            logInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
            logInterceptor.redactHeader("Cookie")
            logInterceptor.redactHeader("Set-Cookie")
            logInterceptor.redactHeader("Authorization")

            builder.addNetworkInterceptor(logInterceptor)  //设置打印拦截日志
            return builder.build()
        }
        val proxyClient = builder.build()
        proxyClientCache[proxy] = proxyClient
        return proxyClient
    }
    return okHttpClient
}

fun getProxyClient(
    proxy: String? = null,
    debugLog: DebugLog? = null,
    source: io.legado.app.data.entities.BaseSource? = null,
    customCookie: String? = null,
    cookieUrl: String? = null
): OkHttpClient {
    val base = getBaseProxyClient(proxy, debugLog)
    if (source == null) return base
    val store = CookieStore.forSource(source)
    val originalHost = cookieUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    return base.newBuilder().cookieJar(object : okhttp3.CookieJar {
        override fun loadForRequest(url: okhttp3.HttpUrl) = store.loadForRequest(url)
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (source.enabledCookieJar != false) store.saveFromResponse(url, cookies)
        }
    }).apply {
        if (!customCookie.isNullOrBlank()) addNetworkInterceptor { chain ->
            var request = chain.request()
            if (originalHost == request.url.host) {
                val values = store.cookieToMap(request.header("Cookie").orEmpty()).apply {
                    putAll(store.cookieToMap(customCookie))
                }
                request = request.newBuilder().header("Cookie", store.mapToCookie(values).orEmpty()).build()
            }
            chain.proceed(request)
        }
    }.build()
}

// suspend fun getWebViewSrc(params: AjaxWebView.AjaxParams): StrResponse =
//     suspendCancellableCoroutine { block ->
//         val webView = AjaxWebView()
//         block.invokeOnCancellation {
//             webView.destroyWebView()
//         }
//         webView.callback = object : AjaxWebView.Callback() {
//             override fun onResult(response: StrResponse) {

//                 if (!block.isCompleted)
//                     block.resume(response)
//             }

//             override fun onError(error: Throwable) {
//                 if (!block.isCompleted)
//                     block.cancel(error)
//             }
//         }
//         webView.load(params)
//     }
