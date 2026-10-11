package io.legado.app.model

import com.google.gson.JsonParser
import com.htmake.reader.api.service.UserDataFiles
import com.htmake.reader.utils.getStoragePath
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID

class SourceFuBridgeCompatibilityTests {
    private fun source() = BookSource(bookSourceUrl = "https://bridge.example", enabledCookieJar = true)
        .apply { runtimeUserNamespace = "bridge-${UUID.randomUUID()}" }

    @Test
    fun `FU encoding overloads and log preserve bytes and original values from Rhino`() {
        val source = source()
        val encoded = java.util.Base64.getEncoder().encodeToString("中文正文".toByteArray(charset("GBK")))
        assertEquals("中文正文", source.evalScript("java.base64Decode('$encoded', 'GBK')"))
        assertEquals("中文😀", source.evalScript("java.hexDecodeToString(java.hexEncodeToString('中文😀'))"))
        assertEquals(42.0, (source.evalScript("java.log(42)") as Number).toDouble())
        assertEquals(true, source.evalScript("java.log(true)"))
        assertEquals("ok", source.evalScript("java.log('ok')"))
        assertTrue(source.evalScript("java.getWebViewUA().indexOf('Mozilla/') === 0") as Boolean)
        assertThrows(Exception::class.java) { source.hexDecodeToString("zz") }
    }

    @Test
    fun `FU crypto factories support published AES and HMAC vectors and RSA key selection`() {
        val source = source()
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", source.evalScript("""
            java.createSymmetricCrypto('AES/ECB/NoPadding', java.hexDecodeToByteArray('000102030405060708090a0b0c0d0e0f'))
                .encryptHex(java.hexDecodeToByteArray('00112233445566778899aabbccddeeff'));
        """.trimIndent()))
        assertEquals("中文正文", source.evalScript("""
            var cipher = java.createSymmetricCrypto('AES/CBC/PKCS5Padding', '0123456789abcdef', 'abcdef0123456789');
            cipher.decryptStr(cipher.encryptBase64('中文正文'));
        """.trimIndent()))
        val hmac = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        assertEquals(hmac, source.evalScript("java.HMacHex('what do ya want for nothing?', 'HmacSHA256', 'Jefe')"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(source.hexDecodeToByteArray(hmac)), source.HMacBase64("what do ya want for nothing?", "HmacSHA256", "Jefe"))
        assertEquals("RSA 正文", source.evalScript("""
            var rsa = java.createAsymmetricCrypto('RSA/ECB/PKCS1Padding');
            rsa.decryptStr(rsa.encryptBase64('RSA 正文'), false);
        """.trimIndent()))
        assertEquals("私钥签名", source.evalScript("""
            var rsa = java.createAsymmetricCrypto('RSA/ECB/PKCS1Padding');
            rsa.decryptStr(rsa.encryptHex('私钥签名', false));
        """.trimIndent()))
        assertEquals(true, source.evalScript("""
            var signer = java.createSign('SHA256withRSA');
            var bytes = java.strToBytes('书源签名');
            signer.verify(bytes, signer.sign(bytes));
        """.trimIndent()))
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = source.createSign("SHA256withRSA").setPrivateKey(pair.private.encoded)
        val message = "独立签名验证".toByteArray()
        val signature = signer.sign(message)
        assertTrue(java.security.Signature.getInstance("SHA256withRSA").run { initVerify(pair.public); update(message); verify(signature) })
    }

    @Test
    fun `FU source variables defaults and refresh functions survive the source call boundary`() {
        val source = source().apply { loginUi = """[{"name":"账号","type":"text","default":"guest"},{"name":"口令","type":"password"},{"name":"登录","type":"button","default":"excluded"}]""" }
        val other = source.copy().apply { runtimeUserNamespace = "bridge-${UUID.randomUUID()}" }
        try {
            assertEquals("", source.getVariable())
            assertEquals("value", source.evalScript("source.put('custom','value'); source.get('custom')"))
            assertEquals("", other.get("custom"))
            source.evalScript("source.putVariable('token'); source.putConcurrent('2/100'); source.refreshExplore();")
            assertEquals("token", source.getVariable())
            assertEquals("2/100", source.concurrentRate)
            assertEquals("2/100", source.getScriptCache().get("concurrentRateOverride"))
            assertEquals("true", source.getScriptCache().get("exploreRefreshRequested"))
            assertEquals(true, io.legado.app.model.jsSource.SourceActions.action(source, "event", null, null, "startRead", null, false)["refreshExplore"])
            assertEquals(false, io.legado.app.model.jsSource.SourceActions.action(source, "event", null, null, "startRead", null, false)["refreshExplore"])
            source.putConcurrent("invalid")
            assertEquals("2/100", source.concurrentRate)
            assertEquals(mapOf("账号" to "guest", "口令" to ""), source.getLoginInfoMap())
            assertEquals(mapOf("账号" to "guest", "口令" to ""), source.copy().apply { runtimeUserNamespace = source.getUserNamespace() }.getLoginInfoMap())
            assertEquals("第12章", source.evalScript("java.toNumChapter('第十二章 标题')"))
            assertNull(source.toNumChapter(null))
            assertEquals("尾声", source.toNumChapter("尾声"))
            source.putVariable(null)
            assertEquals("", source.getVariable())
        } finally {
            listOf("v_custom", "sourceVariable", "concurrentRateOverride", "exploreRefreshRequested").forEach { source.getScriptCache().delete(it) }
            source.removeLoginInfo()
            other.removeLoginInfo()
        }
    }

    @Test
    fun `FU openUrl uses the existing browser and video interaction delivery`() {
        val source = source()
        source.evalScript("java.openUrl('/verify'); java.openUrl('/movie.mp4','video/mp4');")
        val actions = io.legado.app.help.source.SourceInteractions.drain(source)
        assertEquals(listOf("browser", "video"), actions.map { it["type"] })
        assertEquals(listOf("https://bridge.example/verify", "https://bridge.example/movie.mp4"), actions.map { it["url"] })
        assertThrows(Exception::class.java) { source.openUrl("javascript:alert(1)") }
    }

    @Test
    fun `single argument cookie overload remains callable from imported FU scripts`() {
        val source = source()
        val store = CookieStore.forSource(source)
        try {
            store.setCookie(source.getKey(), "sid=session")
            assertEquals("sid=session", source.evalScript("java.getCookie(baseUrl)"))
            assertEquals("session", source.evalScript("java.getCookie(baseUrl,'sid')"))
        } finally { store.clear() }
    }

    @Test
    fun `FU URL helper resolves relatives and retains exact query decoding semantics`() {
        val source = source()
        assertEquals("bridge.example|https://bridge.example:8443|/chapter|末章|a b", source.evalScript("""
            var url = java.toURL('../chapter?title=first&ignored&title=%E6%9C%AB%E7%AB%A0&q=a+b', 'https://bridge.example:8443/book/one');
            [url.host,url.origin,url.pathname,url.searchParams.get('title'),url.searchParams.get('q')].join('|');
        """.trimIndent()))
        val url = source.toURL("https://bridge.example/?raw%20key=a%3Db&empty=&flag")
        assertEquals(mapOf("raw%20key" to "a=b", "empty" to ""), url.searchParams)
        assertNull(source.toURL("https://bridge.example/").searchParams)
    }

    @Test
    fun `FU Chinese bridges handle phrases exclusions and supplementary characters`() {
        val source = source()
        assertEquals("頭髮發展，皇后在後面吃麪條。😀𠀀", source.evalScript("java.s2t('头发发展，皇后在后面吃面条。😀𠀀')"))
        assertEquals("鼠标、凤梨、雪糕、电脑与程序。😀𠀀", source.evalScript("java.t2s('滑鼠、鳳梨、雪糕、電腦與程式。😀𠀀')"))
        assertEquals("", source.t2s(""))
        assertEquals("English 123", source.s2t("English 123"))
    }

    @Test
    fun `FU file download preserves binary data source login headers and private ownership`() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Pair<String?, String?>>()
        server.createContext("/") { exchange ->
            requests.add(exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("Cookie"))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = source().apply { bookSourceUrl = base }
        val other = source.copy().apply { runtimeUserNamespace = "bridge-${UUID.randomUUID()}" }
        val store = CookieStore.forSource(source)
        val paths = mutableListOf<String>()
        try {
            source.putLoginHeader("{\"Authorization\":\"Bearer source-session\"}")
            store.setCookie(base, "sid=download")
            val path = source.evalScript("java.downloadFile('$base/file.bin')").toString().also(paths::add)
            assertTrue(path.endsWith(".bin"))
            assertArrayEquals(bytes, source.readFile(path))
            assertFalse(other.getFile(path).exists())
            val optionPath = source.downloadFile("$base/download,{\"type\":\"dat\"}").also(paths::add)
            assertTrue(optionPath.endsWith(".dat"))
            assertArrayEquals(bytes, source.readFile(optionPath))
            assertTrue(requests.all { it.first == "Bearer source-session" && it.second?.contains("sid=download") == true })
            assertEquals(true, source.evalScript("java.deleteFile('$path')"))
            assertTrue(source.deleteFile(path))
        } finally {
            paths.forEach { source.getFile(it).delete() }
            source.removeLoginHeader()
            store.clear()
            server.stop(0)
        }
    }

    @Test
    fun `FU archive content bridges read real 7zip bytes from hex and HTTP with selected charset`() {
        val names = if (System.getProperty("os.name").startsWith("Windows", true)) listOf("7z.exe", "7zz.exe") else listOf("7zz", "7z")
        val tool = System.getenv("READER_7ZIP")?.let(::File)?.takeIf { it.isFile }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).flatMap { directory -> names.map { File(directory, it) } }.firstOrNull { it.isFile }
        org.junit.jupiter.api.Assumptions.assumeTrue(tool != null, "7-Zip runtime is optional")
        val source = source()
        val bytes = "归档章节正文".toByteArray(charset("GBK"))
        val input = source.getFile("bridge-archive/chapter.txt").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val archive = source.getFile("bridge-archive/fixture.7z")
        val process = ProcessBuilder(tool!!.absolutePath, "a", "-t7z", archive.absolutePath, input.name)
            .directory(input.parentFile).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val data = archive.readBytes()
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val hex = source.byteArrayToHex(archive.readBytes())
            assertArrayEquals(bytes, source.get7zByteArrayContent(hex, "chapter.txt"))
            assertEquals("归档章节正文", source.evalScript("java.get7zStringContent('$hex','chapter.txt','GBK')"))
            // FU's two named methods both delegate to its format-detecting libarchive reader.
            assertArrayEquals(bytes, source.getRarByteArrayContent(hex, "chapter.txt"))
            assertEquals("归档章节正文", source.getRarStringContent("http://127.0.0.1:${server.address.port}/fixture", "chapter.txt", "GBK"))
            assertNull(source.get7zByteArrayContent(hex, "missing.txt"))
            assertThrows(IllegalArgumentException::class.java) { source.get7zByteArrayContent(hex, "../../other.txt") }
            assertTrue(source.getFile("archive-content").listFiles().orEmpty().isEmpty())
        } finally { server.stop(0); input.parentFile.deleteRecursively() }
    }

    @Test
    fun `saved FU and Web config bridges respect authenticated user and active styles`() {
        val source = source()
        val other = source()
        val files = UserDataFiles(File(getStoragePath(), "data").toPath())
        val user = source.getUserNamespace()
        try {
            files.write(user, "readConfig.json", JsonParser.parseString("""[{"name":"FU First","textSize":18},{"name":"FU Second","textSize":26,"unknownFUField":"kept"}]"""))
            files.write(user, "userConfig.json", JsonParser.parseString("""{"readStyleSelect":1,"themeMode":"2"}"""))
            files.write(user, "themeConfig.json", JsonParser.parseString("""[{"themeName":"FU Day","isNightTheme":false},{"themeName":"FU Night","isNightTheme":true,"primaryColor":"#123456"}]"""))
            assertEquals("FU Second|26|kept|2|FU Night", source.evalScript("""
                var read = JSON.parse(java.getReadBookConfig());
                var theme = JSON.parse(java.getThemeConfig());
                [read.name, read.textSize, read.unknownFUField, java.getThemeMode(), theme.themeName].join('|');
            """.trimIndent()))
            assertFalse(other.getReadBookConfigMap().containsKey("unknownFUField"))
            files.write(user, "userConfig.json", JsonParser.parseString("""{"reactReadingSettings":{"fontSize":30,"fontWeight":700,"lineHeight":2,"letterSpacing":1,"paragraphSpace":1,"textIndent":2,"pagePadding":12,"pageMode":"page","pageAnimation":"none","theme":"night","customTextColor":"#abcdef","customBackgroundColor":"#123456"}}"""))
            val read = source.getReadBookConfigMap()
            assertEquals(30, read["textSize"])
            assertEquals(1, read["textBold"])
            assertEquals(30, read["lineSpacingExtra"])
            assertEquals(4, read["pageAnim"])
            assertEquals("　　", read["paragraphIndent"])
            assertEquals(12, read["paddingLeft"])
            assertEquals("#abcdef", read["textColor"])
            assertEquals("#123456", source.getThemeConfigMap()["backgroundColor"])
            assertEquals("2", source.getThemeMode())
            for ((stored, expected) in listOf("2" to "2", "4" to "1", "6" to "1", "3" to "3", "99" to "0")) {
                files.write(user, "userConfig.json", JsonParser.parseString("{\"themeMode\":$stored}"))
                assertEquals(expected, source.getThemeMode())
            }
            files.write(user, "userConfig.json", JsonParser.parseString("""{"themeMode":"0","reactReadingSettings":{"theme":"night"}}"""))
            assertEquals(true, source.getThemeConfigMap()["isNightTheme"])
        } finally { files.directory(user).toFile().deleteRecursively() }
    }
}
