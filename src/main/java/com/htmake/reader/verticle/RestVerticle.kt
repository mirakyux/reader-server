package com.htmake.reader.verticle

import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.LocalSessionStore
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.github.oshai.kotlinlogging.KotlinLogging
import com.htmake.reader.utils.error
import com.htmake.reader.utils.success
import com.htmake.reader.config.CorsConfig
import org.springframework.beans.factory.annotation.Autowired


private val logger = KotlinLogging.logger {}

abstract class RestVerticle : CoroutineVerticle() {

    @Autowired
    private lateinit var corsConfig: CorsConfig

    protected lateinit var router: Router

    open var port: Int = 8080

    override suspend fun start() {
        super.start()
        router = Router.router(vertx)
        val cors = CorsHandler.create()
            .addOrigins(corsConfig.allowedOrigins)
            .allowCredentials(corsConfig.allowCredentials)
            .allowedMethods(setOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD,
                HttpMethod.PROPFIND, HttpMethod.MKCOL, HttpMethod.MOVE, HttpMethod.COPY))
            .allowedHeaders(setOf("Authorization", "Content-Type", "Accept", "MCP-Protocol-Version", "If-Match", "If-Modified-Since",
                "If-None-Match", "If-Unmodified-Since", "X-Requested-With", "Depth", "Destination", "Overwrite"))
        router.route().handler(cors)
        val cookieName = "reader.session"
	    router.route().handler(
            SessionHandler.create(LocalSessionStore.create(vertx))
                            .setSessionCookieName(cookieName)
                            .setSessionTimeout(7L * 86400 * 1000)
                            .setSessionCookiePath("/")
        );
        router.route().handler {
            it.addHeadersEndHandler { _ ->
                val cookie = it.request().getCookie(cookieName)
                if (cookie != null) {
                    // 每次访问都延长cookie有效期
                    cookie.setMaxAge(2L * 86400 * 1000)
                    cookie.setPath("/")
                }
            }
            it.next()
        }

        router.route().handler(BodyHandler.create())

        router.route("/reader3/*").handler {
            logger.info { "${it.request().method().name()} ${it.request().path()}" }
            it.next()
        }

        router.get("/health").handler { it.success("ok!") }

        initRouter(router)

//        router.errorHandler(500) { routerContext ->
//            logger.error { routerContext.failure().message }
//            routerContext.error(routerContext.failure())
//        }

        router.route().last().failureHandler { ctx ->
            val failure = ctx.failure()
            if (ctx.statusCode() in 400..499) ctx.response().setStatusCode(ctx.statusCode()).end()
            else if (failure != null) ctx.error(failure)
            else ctx.response().setStatusCode(ctx.statusCode()).end()
        }

        logger.info { "port: ${port}" }
        vertx.createHttpServer().requestHandler(router).exceptionHandler { error ->
            onException(error)
        }.listen(port).onFailure { onStartError() }.coAwait()
        logger.info { "Server running at: http://localhost:${port}" }
        started()
    }

    abstract suspend fun initRouter(router: Router);

    open fun onException(error: Throwable) {
        logger.error(error) { "vertx exception: ${error}" }
    }

    open fun onStartError() {
    }

    open fun started() {

    }

    open fun onHandlerError(ctx: RoutingContext, error: Exception) {
        logger.error(error) { "Error: ${error}" }
        ctx.error(error)
    }

    /**
     * An extension method for simplifying coroutines usage with Vert.x Web routers
     */
    fun Route.coroutineHandler(fn: suspend (RoutingContext) -> Any) {
        handler { ctx ->
            val job = launch(Dispatchers.IO) {
                try {
                    ctx.success(fn(ctx))
//                    fn(ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!ctx.response().closed() && !ctx.response().ended()) onHandlerError(ctx, e)
                }
            }
        }
    }

    fun Route.coroutineHandlerWithoutRes(fn: suspend (RoutingContext) -> Any) {
        handler { ctx ->
            val job = launch(Dispatchers.IO) {
                try {
                    fn(ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!ctx.response().closed() && !ctx.response().ended()) onHandlerError(ctx, e)
                }
            }
        }
    }
}
