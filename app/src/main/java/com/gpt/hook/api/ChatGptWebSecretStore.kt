package com.gpt.hook.api

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Web-session credentials are kept separately from SIWC profiles and encrypted with Android Keystore. */
internal data class ChatGptWebCredentials(
    val accessToken: String,
    val sessionToken: String,
)

internal class ChatGptWebSecretStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh_web_secure", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): ChatGptWebCredentials? {
        val encoded = prefs.getString(KEY_DATA, null) ?: return null
        val parts = encoded.split('.')
        require(parts.size == 3 && parts[0] == FORMAT) { "Encrypted Web credentials have an unknown format" }
        val iv = Base64.getDecoder().decode(parts[1])
        val ciphertext = Base64.getDecoder().decode(parts[2])
        require(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty()) { "Encrypted Web credentials are malformed" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return ChatGptWebCredentials(
            accessToken = json.optString("accessToken"),
            sessionToken = json.optString("sessionToken"),
        )
    }

    @Synchronized
    fun write(credentials: ChatGptWebCredentials) {
        if (credentials.accessToken.isBlank() && credentials.sessionToken.isBlank()) {
            clear()
            return
        }
        val payload = JSONObject()
            .put("accessToken", credentials.accessToken)
            .put("sessionToken", credentials.sessionToken)
            .toString()
        val iv = ByteArray(GCM_IV_BYTES).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val encoded = listOf(
            FORMAT,
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(ciphertext),
        ).joinToString(".")
        check(prefs.edit().putString(KEY_DATA, encoded).commit()) {
            "Could not durably save encrypted Web credentials"
        }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(KEY_DATA).commit()) { "Could not clear encrypted Web credentials" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dsh.web.credentials.aes.v1"
        const val KEY_DATA = "credentials"
        const val FORMAT = "v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
