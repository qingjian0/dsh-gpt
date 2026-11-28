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

    /**
     * 回调端口必须稳定。
     *
     * 上游会逐字符校验 redirect_uri，而注册出来的 `oaiapp_...` client 是绑定在
     * 首次注册时那个 redirect_uri 上的。若每次登录都换随机端口，复用 client 时
     * redirect_uri 就对不上，token 交换直接 400 —— 表现就是「授权成功但拿不到 token」。
     */
    @Synchronized
    fun callbackPort(): Int {
        val prefs = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt(CALLBACK_PORT, 0)
        if (saved in 1024..65535) return saved
        // 首次：优先用 SIWC 习惯的 1455，被占用再随机，一旦定了就永久复用。
        val chosen = runCatching {
            java.net.ServerSocket(PREFERRED_CALLBACK_PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
                .use { PREFERRED_CALLBACK_PORT }
        }.getOrElse {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }
        check(prefs.edit().putInt(CALLBACK_PORT, chosen).commit()) { "Could not persist ChatGPT callback port" }
        // 旧版本每次用随机端口，注册出来的 oaiapp_... 已绑在那个端口上，复用只会 400。
        // 端口第一次确定时一并作废旧注册，让下次走全新注册并绑到固定端口。
        runCatching { clearPendingClientId() }
        return chosen
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

    /**
     * 登录失败诊断。release 包没有 logcat，把关键节点写进独立 prefs，
     * 便于在设备上直接读出 token 端点的状态码与响应片段。
     */
    @Synchronized
    fun recordDiagnostic(stage: String, detail: String) {
        runCatching {
            val prefs = context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
            val line = System.currentTimeMillis().toString() + " [" + stage + "] " +
                detail.replace('\n', ' ').take(600)
            val prev = prefs.getString(DIAG_LOG, "").orEmpty()
            val merged = (line + "\n" + prev).take(4000)
            prefs.edit().putString(DIAG_LOG, merged).commit()
        }
    }

    /** 读取最近的登录诊断日志（新版在前）。 */
    @Synchronized
    fun diagnosticLog(): String =
        context.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
            .getString(DIAG_LOG, "")
            .orEmpty()

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
    fun clearTokens(profileId: String) {
        val current = snapshot()
        val profile = current.profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("ChatGPT profile not found")
        val cleared = profile.copy(
            idToken = null,
            accessToken = null,
            refreshToken = null,
            scopes = emptySet(),
            expiresAtMillis = null,
            earliestRefreshAtMillis = null,
        )
        write(current.profiles.map { if (it.id == profileId) cleared else it }, current.activeProfileId)
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
        const val CALLBACK_PORT = "callback_port"
        const val DIAG_PREFS = "dsh_siwc_diag"
        const val DIAG_LOG = "log"
        const val PREFERRED_CALLBACK_PORT = 1455
    }
}
