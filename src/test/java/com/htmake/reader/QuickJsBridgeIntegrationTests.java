package com.htmake.reader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.htmake.reader.config.AppConfig;
import com.sun.net.httpserver.HttpServer;
import io.legado.app.data.entities.BookSource;
import io.legado.app.model.analyzeRule.AnalyzeRule;
import io.legado.app.model.jsSource.JsSourceEngine;
import io.legado.app.quickjs.QuickJsSandboxBridge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReaderApplication.class, properties = {
        "reader.app.storagePath=build/test-storage", "reader.app.secure=false", "reader.app.secureKey="
})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
class QuickJsBridgeIntegrationTests {
    @Autowired private AppConfig config;

    @Test void onlySingleFileJsHostExposesFuQuickJsApiAndDoesNotOverwriteUserGlobals() {
        BookSource source = source("function probe(){return typeof java.getQuickJsSandbox+'|'+typeof isolatedJs;}");
        assertEquals("function|undefined", new JsSourceEngine(source, null).call("probe", List.of(), false, true));
        AnalyzeRule rule = new AnalyzeRule(new io.legado.app.data.entities.Book(), source);
        assertEquals("undefined|undefined", rule.evalJS("typeof java.getQuickJsSandbox+'|'+typeof isolatedJs", null));
        source.setMainJs("var isolatedJs={evalString:function(){return 'user';}};function probe(){return isolatedJs.evalString('value');}");
        assertEquals("user", new JsSourceEngine(source, null).call("probe", List.of(), false, true));
    }

    @Test void rhinoCanCallStringOnlyQuickJsBridgeWithPrivateAuthenticationAndOriginalScript() throws Exception {
        AtomicReference<String> script = new AtomicReference<>();
        withService("{\"success\":true,\"value\":\"QuickJS正文 😀\"}", script, () -> {
            BookSource source = source("function probe(){return java.getQuickJsSandbox().evalString('await Promise.resolve(\"正文\")');}");
            assertEquals("QuickJS正文 😀", new JsSourceEngine(source, null).call("probe", List.of(), false, true));
            assertEquals("await Promise.resolve(\"正文\")", script.get());
        });
    }

    @Test void sandboxFailureIsVisibleAndNeverFallsBackToRhino() throws Exception {
        withService("{\"success\":false,\"error\":\"EVALUATION_TIMEOUT\"}", new AtomicReference<>(), () -> {
            var error = assertThrows(IllegalStateException.class, () -> new QuickJsSandboxBridge().evalString("'would work in Rhino'"));
            assertEquals("QuickJS sandbox failed: EVALUATION_TIMEOUT", error.getMessage());
        });
        for (String response : List.of("{}", "{\"success\":true,\"value\":42}", "{\"success\":false}")) {
            withService(response, new AtomicReference<>(), () -> {
                var error = assertThrows(IllegalStateException.class, () -> new QuickJsSandboxBridge().evalString("'text'"));
                assertEquals("QuickJS sandbox failed: INVALID_RESPONSE", error.getMessage());
            });
        }
    }

    @Test void invalidInputAndServiceConfigurationFailWithoutScriptExecution() {
        var error = assertThrows(IllegalStateException.class, () -> new QuickJsSandboxBridge().evalString(" ".repeat(384001)));
        assertEquals("QuickJS sandbox failed: INPUT_TOO_LARGE", error.getMessage());
        String previousToken = config.getBrowserToken(); config.setBrowserToken("");
        try {
            error = assertThrows(IllegalStateException.class, () -> new QuickJsSandboxBridge().evalString("'text'"));
            assertEquals("QuickJS sandbox failed: BIND_FAILED", error.getMessage());
        } finally { config.setBrowserToken(previousToken); }
    }

    private BookSource source(String script) {
        BookSource source = new BookSource(); source.setBookSourceUrl("https://quickjs-fixture.example"); source.setMainJs(script);
        return source;
    }

    private void withService(String response, AtomicReference<String> script, Runnable check) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quickjs", exchange -> {
            assertEquals("Bearer quickjs-private-token", exchange.getRequestHeaders().getFirst("Authorization"));
            JsonObject input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(1, input.size()); script.set(input.get("script").getAsString());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String previousUrl = config.getBrowserUrl(), previousToken = config.getBrowserToken();
        config.setBrowserUrl("http://127.0.0.1:" + server.getAddress().getPort()); config.setBrowserToken("quickjs-private-token");
        try { check.run(); }
        finally { config.setBrowserUrl(previousUrl); config.setBrowserToken(previousToken); server.stop(0); }
    }
}
