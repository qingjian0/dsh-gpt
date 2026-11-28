package com.gpt.hook.api

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth 凭证生命周期中心。
 *
 * 职责只有四件，全部围绕「一次登录，长期可用」：
 * 1. **加密落盘**：access / refresh token 不再明文存 SharedPreferences；
 * 2. **临期刷新**：距过期 5 分钟内才刷，且同一账号 30 秒内只真刷一次（并发去重）；
 * 3. **刷新轮换回存**：上游轮换 refresh_token 后必须立刻持久化，否则下次必 invalid_grant；
 * 4. **401 有限恢复**：请求被判 token 失效时，最多强制刷新并重试 1 次，之后拉黑避免风暴。
 *
 * 设计约束（照抄 Android-AI-gateway 第 5 节铁律的反面教训）：
 * 任何让刷新频率变高的改动都是错的。刷新失败要冷却 60 秒，refresh_token 失效要拉黑，
 * 不能形成「401 → 刷 → 再 401 → 再刷」的风暴，那会加速上游吊销。
 */
object ChatGptCredentialStore {

    private const val PREFS = "chatgpt_oauth"
    private const val KEY_CREDENTIAL = "credential"

    @Volatile private var secretBox: SecretBox? = null

    private val lastRefreshedAt = ConcurrentHashMap<String, Long>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val revoked = ConcurrentHashMap.newKeySet<String>()
    private val refreshLock = Any()

    /** 进程启动时注入加密存储；未注入时退化为不落盘（仅内存）。 */
    fun attach(box: SecretBox) {
        secretBox = box
    }

    fun load(): OAuthCredential? = OAuthCredential.fromJson(secretBox?.read(KEY_CREDENTIAL))

    fun save(credential: OAuthCredential?) {
        val box = secretBox ?: return
        if (credential == null) box.erase(KEY_CREDENTIAL) else box.write(KEY_CREDENTIAL, credential.toJson().toString())
    }

    fun isRevoked(accessToken: String): Boolean = accessToken in revoked

    /**
     * 确保返回一个可用凭证。
     *
     * @param force 401 后的强制刷新（跳过临期判断，但仍受冷却与复用窗口约束）
     * @return 可用凭证；无法恢复时返回 null，由调用方回 401 并提示重新登录
     */
    fun ensureUsable(
        current: OAuthCredential?,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
        oauth: ChatGptOAuth = ChatGptOAuth(),
    ): OAuthCredential? {
        if (current == null) return null
        if (!current.hasRefreshToken) return current.takeIf { !it.isExpiringWithin(0, now) || force }
        if (current.accessToken in revoked) return null

        val uid = current.accountId.ifEmpty { current.email.ifEmpty { "chatgpt-unknown" } }
        if (!force && !current.isExpiringWithin(ChatGptOAuth.Constants.REFRESH_LEEWAY_MS, now)) return current
        if (now < (cooldownUntil[uid] ?: 0L)) return if (force) null else current

        synchronized(refreshLock) {
            val last = lastRefreshedAt[uid] ?: 0L
            // 并发去重：同一窗口内只有第一个真去刷；其余返回最新落盘值
            if (now - last < ChatGptOAuth.Constants.REFRESH_REUSE_MS) return load() ?: current
            return try {
                val fresh = oauth.refresh(current.refreshToken, current)
                val merged = fresh.copy(
                    accountId = fresh.accountId.ifEmpty { current.accountId },
                    email = fresh.email.ifEmpty { current.email },
                    plan = fresh.plan.ifEmpty { current.plan },
                )
                lastRefreshedAt[uid] = now
                cooldownUntil.remove(uid)
                // 轮换后的 refresh_token 必须落盘，否则下次只能重新登录
                save(merged)
                merged
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                if (message.contains("invalid_grant", true) || message.contains("refresh_token", true)) {
                    // refresh_token 失效是终态：拉黑，避免每个请求都去撞 auth 端点
                    revoked += current.accessToken
                    save(null)
                } else {
                    cooldownUntil[uid] = now + ChatGptOAuth.Constants.REFRESH_COOLDOWN_MS
                }
                null
            }
        }
    }

    /** 测试用：清空进程内的冷却/复用/拉黑状态。 */
    fun resetRuntimeStateForTest() {
        lastRefreshedAt.clear()
        cooldownUntil.clear()
        revoked.clear()
    }

    /** 加密存取抽象：Android 侧用 EncryptedSharedPreferences 实现，JVM 测试用内存实现。 */
    interface SecretBox {
        fun read(key: String): String?
        fun write(key: String, value: String)
        fun erase(key: String)
    }

    /** 纯内存实现（无 Android 依赖，供单测与 attach 之前的兜底）。 */
    class MemoryBox : SecretBox {
        private val map = ConcurrentHashMap<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) { map[key] = value }
        override fun erase(key: String) { map.remove(key) }
    }
}

/** OAuth 通道的上游 HTTP：codex 后端只认 Responses，且必须带 originator / account id。 */
class ChatGptCodexClient(private val credential: OAuthCredential) {

    fun open(method: String, path: String, body: ByteArray?, accept: String): HttpURLConnection {
        val base = ChatGptOAuth.Constants.CODEX_BASE.trimEnd('/')
        val url = URL(base + (if (path.startsWith("/")) path else "/$path"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 180_000
        conn.doInput = true
        conn.setRequestProperty("Authorization", "Bearer " + credential.accessToken)
        conn.setRequestProperty("originator", ChatGptOAuth.Constants.ORIGINATOR)
        conn.setRequestProperty("User-Agent", ChatGptOAuth.Constants.USER_AGENT)
        if (credential.accountId.isNotEmpty()) conn.setRequestProperty("ChatGPT-Account-ID", credential.accountId)
        conn.setRequestProperty("Accept", accept)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    fun readAll(conn: HttpURLConnection): String {
        val status = conn.responseCode
        val stream = if (status >= 400) (conn.errorStream ?: conn.inputStream) else conn.inputStream
        val out = ByteArrayOutputStream()
        stream?.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        /** 模型目录 30 分钟缓存，避免每个请求都拉一次。 */
        @Volatile private var cachedModels: List<String> = emptyList()
        @Volatile private var cachedAt = 0L
        private const val MODEL_TTL_MS = 30 * 60_000L

        fun cachedModelsOrEmpty(now: Long = System.currentTimeMillis()): List<String> =
            if (now - cachedAt < MODEL_TTL_MS) cachedModels else emptyList()

        fun cacheModels(models: List<String>, now: Long = System.currentTimeMillis()) {
            cachedModels = models
            cachedAt = now
        }

        fun invalidateModels() {
            cachedModels = emptyList()
            cachedAt = 0L
        }
    }
}
