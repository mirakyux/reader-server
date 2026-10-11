package io.legado.app.help.source

import com.htmake.reader.init.appCtx
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The cache's local key is independent of user/source identifiers. Clearing it logs sources out. */
object SourceSecrets {
    private val random = SecureRandom()
    @Synchronized private fun key(): SecretKeySpec {
        val file = File(appCtx.cacheDir, ".source-secrets.key")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeBytes(ByteArray(32).also(random::nextBytes))
        }
        return SecretKeySpec(file.readBytes(), "AES")
    }
    fun encrypt(text: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(encoded: String): String {
        return decryptWithKey(encoded, key().encoded)
    }
    fun exportKey(): String = Base64.getEncoder().encodeToString(key().encoded)
    fun decryptWithKey(encoded: String, keyBytes: ByteArray): String {
        val data = Base64.getDecoder().decode(encoded)
        require(data.size > 12) { "登录缓存损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
}
