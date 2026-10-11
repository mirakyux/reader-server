package com.htmake.reader;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReaderApplication.class, properties = {"reader.app.storagePath=build/test-storage", "reader.app.secure=false", "reader.app.secureKey="})
@ContextConfiguration(initializers = ReaderApplicationTests.ServerPortInitializer.class)
class SourceSubscriptionsIntegrationTests {
    @Autowired private org.springframework.core.env.Environment environment;
    @Test void subscriptionUpdatesRulesPreservesPreferencesAndCanBeRemoved() throws Exception {
        String sourceUrl = "https://subscription-" + UUID.randomUUID() + ".example";
        JsonObject source = new JsonObject(); source.addProperty("bookSourceUrl", sourceUrl); source.addProperty("bookSourceName", "old");
        source.addProperty("enabled", false); source.addProperty("bookSourceGroup", "local"); source.addProperty("customOrder", 9);
        source.add("ruleContent", new JsonObject());
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/sources", exchange -> {
            JsonObject update = source.deepCopy(); update.addProperty("bookSourceName", "updated"); update.addProperty("enabled", true); update.addProperty("bookSourceGroup", "remote");
            byte[] bytes = ("[" + update + "]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); remote.start();
        JsonObject subscription = new JsonObject(); subscription.addProperty("name", "Fixture"); subscription.addProperty("url", "http://127.0.0.1:" + remote.getAddress().getPort() + "/sources");
        JsonObject id = new JsonObject();
        try {
            call("saveBookSource", source);
            JsonObject saved = call("saveBookSourceSubscription", subscription).getAsJsonObject("data");
            assertTrue(saved.has("id") && !saved.get("id").isJsonNull(), saved.toString()); id.add("id", saved.get("id"));
            JsonObject updated = call("updateBookSourceSubscription", id).getAsJsonObject("data");
            assertEquals(1, updated.get("count").getAsInt());
            assertEquals(id.get("id"), updated.getAsJsonObject("subscription").get("id"));
            JsonObject request = new JsonObject(); request.addProperty("bookSourceUrl", sourceUrl);
            JsonObject actual = call("getBookSource", request).getAsJsonObject("data");
            assertEquals("updated", actual.get("bookSourceName").getAsString()); assertFalse(actual.get("enabled").getAsBoolean());
            assertEquals("local", actual.get("bookSourceGroup").getAsString()); assertEquals(9, actual.get("customOrder").getAsInt());
            assertTrue(call("getBookSourceSubscriptions", new JsonObject()).getAsJsonArray("data").asList().stream().anyMatch(value -> value.getAsJsonObject().get("id").equals(id.get("id"))));
        } finally {
            try {
                if (id.has("id") && !id.get("id").isJsonNull()) call("deleteBookSourceSubscription", id);
                call("deleteBookSource", source);
            } finally { remote.stop(0); }
        }
    }
    private JsonObject call(String path, JsonObject input) throws Exception {
        int port = environment.getRequiredProperty("reader.server.port", Integer.class);
        HttpResponse<String> response = null;
        for (int retry = 0; retry < 100; retry++) {
            try { response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reader3/" + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(), HttpResponse.BodyHandlers.ofString()); break; }
            catch (ConnectException failure) { if (retry == 99) throw failure; Thread.sleep(100); }
        }
        assertEquals(200, response.statusCode(), response.body()); JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(result.get("isSuccess").getAsBoolean(), response.body()); return result;
    }
}
