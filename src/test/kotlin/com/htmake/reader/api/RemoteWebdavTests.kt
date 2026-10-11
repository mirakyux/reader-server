package com.htmake.reader.api

import com.htmake.reader.api.service.*
import com.htmake.reader.api.service.advanced.*
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Base64

class RemoteWebdavTests {
    @TempDir lateinit var root: Path
    @Test fun savedCredentialsStayPrivateAndDavUsesEncodedPathsAndActualListing() {
        val calls = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(calls) { calls.add(exchange.requestMethod to exchange.requestURI.path) }
            assertEquals("Basic " + Base64.getEncoder().encodeToString("reader:secret".toByteArray()), exchange.requestHeaders.getFirst("Authorization"))
            val body = when (exchange.requestMethod) {
                "PROPFIND" -> "<d:multistatus xmlns:d='DAV:'><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response><d:response><d:href>/dav/backup%20%E4%B8%AD%E6%96%87.zip</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>3</d:getcontentlength></d:prop></d:propstat></d:response><d:response><d:href>http://foreign.test/dav/leak.zip</d:href></d:response></d:multistatus>"
                "GET" -> "zip"
                else -> "ok"
            }.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestMethod == "PROPFIND") 207 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = RemoteWebdav(UserDataFiles(root))
            val base = "http://127.0.0.1:${server.address.port}/dav"
            client.save("alice", jsonObject("url" to base, "username" to "reader", "password" to "secret", "dailyBackup" to true))
            assertFalse(client.config("alice").has("password")); assertTrue(client.config("alice").flag("passwordConfigured"))
            client.save("alice", jsonObject("url" to base, "username" to "reader", "dailyBackup" to true))
            assertEquals("secret", client.config("alice", true).string("password"))
            val entries = client.list("alice", "")
            assertEquals(1, entries.size); assertEquals("backup 中文.zip", entries[0].name)
            client.mkdir("alice", "bookProgress"); client.upload("alice", "bookProgress/中文 书.json", "{}".toByteArray())
            assertArrayEquals("zip".toByteArray(), client.download("alice", entries[0].path))
            assertTrue(calls.contains("MKCOL" to "/dav/bookProgress/")); assertTrue(calls.contains("PUT" to "/dav/bookProgress/中文 书.json"))
            assertThrows(IllegalArgumentException::class.java) { client.download("alice", "../escape") }
            assertFalse(client.config("bob").flag("passwordConfigured"))
        } finally { server.stop(0) }
    }
    @Test fun redirectsAndXmlEntitiesAreRejectedInsteadOfForwardingCredentials() {
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            if (exchange.requestMethod == "GET") {
                exchange.responseHeaders.set("Location", "http://foreign.test/leak"); exchange.sendResponseHeaders(302, -1)
            } else {
                val xml = "<!DOCTYPE x [<!ENTITY steal SYSTEM 'file:///secret'>]><d:multistatus xmlns:d='DAV:'>&steal;</d:multistatus>".toByteArray()
                exchange.sendResponseHeaders(207, xml.size.toLong()); exchange.responseBody.use { it.write(xml) }
            }
            exchange.close()
        }
        server.start()
        try {
            val client = RemoteWebdav(UserDataFiles(root)); client.save("alice", jsonObject("url" to "http://127.0.0.1:${server.address.port}/dav", "username" to "reader", "password" to "secret"))
            assertThrows(IllegalArgumentException::class.java) { client.download("alice", "backup.zip") }
            assertThrows(org.xml.sax.SAXParseException::class.java) { client.list("alice", "") }
            assertEquals(2, requests)
        } finally { server.stop(0) }
    }
}
