package com.gpt.hook.api

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted SIWC profile storage. There is intentionally no plaintext fallback:
 * if Android Keystore is unavailable, credentials are not persisted.
 */
internal class ChatGptPlanSecretStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh_siwc_secure", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): String? {
        val encoded = prefs.getString(KEY_DATA, null) ?: return null
        val parts = encoded.split('.')
        require(parts.size == 3 && parts[0] == FORMAT) { "Encrypted ChatGPT profile data has an unknown format" }
        val iv = Base64.getDecoder().decode(parts[1])
        val ciphertext = Base64.getDecoder().decode(parts[2])
        require(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty()) { "Encrypted ChatGPT profile data is malformed" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    @Synchronized
    fun write(value: String) {
        require(value.isNotBlank())
        val iv = ByteArray(GCM_IV_BYTES).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = listOf(FORMAT, Base64.getEncoder().encodeToString(iv), Base64.getEncoder().encodeToString(ciphertext)).joinToString(".")
        check(prefs.edit().putString(KEY_DATA, encoded).commit()) { "Could not durably save encrypted ChatGPT credentials" }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(KEY_DATA).commit()) { "Could not clear encrypted ChatGPT credentials" }
    }

    fun isAvailable(): Boolean = runCatching { getOrCreateKey(); true }.getOrDefault(false)

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
        const val KEY_ALIAS = "dsh.siwc.profile.aes.v1"
        const val KEY_DATA = "profiles"
        const val FORMAT = "v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

internal data class ChatGptPlanProfile(
    val id: String,
    val clientId: String,
    val subject: String,
    val email: String?,
    val name: String?,
    val idToken: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val scopes: Set<String>,
    val expiresAtMillis: Long?,
    val earliestRefreshAtMillis: Long?,
) {
    val canUseChatGptPlan: Boolean
        get() = ChatGptPlanOAuth.REQUIRED_SCOPE in scopes && !accessToken.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("clientId", clientId)
        .put("subject", subject)
        .put("email", email)
        .put("name", name)
        .put("idToken", idToken)
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("scopes", JSONArray(scopes.sorted()))
        .put("expiresAtMillis", expiresAtMillis)
        .put("earliestRefreshAtMillis", earliestRefreshAtMillis)

    companion object {
        fun fromJson(json: JSONObject): ChatGptPlanProfile {
            val id = json.optString("id")
            val clientId = json.optString("clientId")
            val subject = json.optString("subject")
            ChatGptPlanOAuth.validateIssuedClientId(clientId)
            require(id.matches(Regex("[A-Za-z0-9-]{1,100}")) && subject.isNotBlank()) {
                "Saved ChatGPT profile identity is invalid"
            }
            val scopesArray = json.optJSONArray("scopes") ?: JSONArray()
            val scopes = buildSet {
                for (i in 0 until scopesArray.length()) {
                    val scope = scopesArray.optString(i)
                    if (scope.isNotBlank()) add(scope)
                }
            }
            val access = json.optString("accessToken").takeIf { it.isNotBlank() }
            val refresh = json.optString("refreshToken").takeIf { it.isNotBlank() }
            require(access == null || refresh != null) { "Saved ChatGPT profile is missing its refresh token" }
            return ChatGptPlanProfile(
                id = id,
                clientId = clientId,
                subject = subject,
                email = json.optString("email").takeIf { it.isNotBlank() },
                name = json.optString("name").takeIf { it.isNotBlank() },
                idToken = json.optString("idToken").takeIf { it.isNotBlank() },
                accessToken = access,
                refreshToken = refresh,
                scopes = scopes,
                expiresAtMillis = json.optLong("expiresAtMillis").takeIf { it > 0L },
                earliestRefreshAtMillis = json.optLong("earliestRefreshAtMillis").takeIf { it > 0L },
            )
        }
    }
}

/** Atomic encrypted profile document; registrations are keyed by client ID + verified subject. */
internal class ChatGptPlanProfileStore(
    private val context: Context,
    private val secrets: ChatGptPlanSecretStore = ChatGptPlanSecretStore(context),
) {
    data class Snapshot(val activeProfileId: String?, val profiles: List<ChatGptPlanProfile>)

    @Synchronized
    fun hostId(): String {
        val prefs = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
        prefs.getString(HOST_ID, null)?.let { return it }
        val generated = ChatGptPlanOAuth.newHostId()
        check(prefs.edit().putString(HOST_ID, generated).commit()) { "Could not persist ChatGPT host ID" }
        return generated
    }

    @Synchronized
    fun pendingClientId(): String? =
        context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .getString(PENDING_CLIENT_ID, null)
            ?.also(ChatGptPlanOAuth::validateIssuedClientId)

    @Synchronized
    fun savePendingClientId(clientId: String) {
        ChatGptPlanOAuth.validateIssuedClientId(clientId)
        check(context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .edit().putString(PENDING_CLIENT_ID, clientId).commit()) {
            "Could not persist ChatGPT registration ID"
        }
    }

    @Synchronized
    fun clearPendingClientId() {
        check(context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .edit().remove(PENDING_CLIENT_ID).commit()) {
            "Could not clear pending ChatGPT registration ID"
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val raw = secrets.read() ?: return Snapshot(null, emptyList())
        val root = JSONObject(raw)
        require(root.optInt("version") == 1) { "Saved ChatGPT profile document version is unsupported" }
        val array = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = ArrayList<ChatGptPlanProfile>()
        val ids = HashSet<String>()
        val registrations = HashSet<String>()
        for (i in 0 until array.length()) {
            val profile = ChatGptPlanProfile.fromJson(array.getJSONObject(i))
            require(ids.add(profile.id)) { "Duplicate ChatGPT profile ID" }
            require(registrations.add(profile.clientId + "|" + profile.subject)) { "Duplicate ChatGPT registration" }
            profiles += profile
        }
        val active = root.optString("activeProfileId").takeIf { it.isNotBlank() }
        require(active == null || profiles.any { it.id == active }) { "Active ChatGPT profile is missing" }
        return Snapshot(active, profiles)
    }

    @Synchronized
    fun save(profile: ChatGptPlanProfile, makeActive: Boolean = true) {
        ChatGptPlanOAuth.validateIssuedClientId(profile.clientId)
        require(profile.id.matches(Regex("[A-Za-z0-9-]{1,100}")) && profile.subject.isNotBlank())
        require(profile.accessToken.isNullOrBlank() == profile.refreshToken.isNullOrBlank()) {
            "ChatGPT access and refresh credentials must be stored together"
        }
        val current = snapshot()
        val existing = current.profiles.filterNot { it.id == profile.id }
        require(existing.none { it.clientId == profile.clientId && it.subject == profile.subject }) {
            "This ChatGPT registration already exists under another profile ID"
        }
        val next = existing + profile
        write(next, if (makeActive) profile.id else current.activeProfileId)
    }

    @Synchronized
    fun setActive(profileId: String) {
        val current = snapshot()
        require(current.profiles.any { it.id == profileId }) { "ChatGPT profile not found" }
        write(current.profiles, profileId)
    }

    @Synchronized
    fun remove(profileId: String) {
        val current = snapshot()
        val next = current.profiles.filterNot { it.id == profileId }
        val active = if (current.activeProfileId == profileId) next.firstOrNull()?.id else current.activeProfileId
        if (next.isEmpty()) secrets.clear() else write(next, active)
    }

    private fun write(profiles: List<ChatGptPlanProfile>, activeId: String?) {
        val root = JSONObject()
            .put("version", 1)
            .put("activeProfileId", activeId)
            .put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
        secrets.write(root.toString())
    }

    private companion object {
        const val HOST_PREFS = "dsh_siwc_host"
        const val HOST_ID = "ext_agent_host_id"
        const val PENDING_CLIENT_ID = "pending_client_id"
    }
}
