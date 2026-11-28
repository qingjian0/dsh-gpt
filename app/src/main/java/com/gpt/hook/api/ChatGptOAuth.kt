package com.gpt.hook.api

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * ChatGPT OAuth（Sign in with ChatGPT）协议实现：PKCE 生成、授权 URL、code 交换、refresh。
 *
 * 事实来源：openai/codex 官方 CLI 的公开注册值（public client + PKCE，无 client_secret），
 * 回调固定 `http://localhost:1455/auth/callback`（上游逐字符校验 redirect_uri）。
 *
 * 两处格式差异，写错只回 `invalid_request`，没有更具体的提示：
 * 1. code 交换是 **form**（`application/x-www-form-urlencoded`）；
 * 2. refresh 是 **JSON**（`application/json`）。
 *
 * 本类不依赖 Android API，可在 JVM 单测里跑（超时、URL 拼装、JWT 解析全部纯函数）。
 */
class ChatGptOAuth(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val transport: Transport = HttpTransport,
) {

    interface Transport {
        /** POST 到 token 端点；返回 (HTTP 状态, body)。 */
        fun post(url: String, contentType: String, body: String, userAgent: String): Pair<Int, String>
    }

    object HttpTransport : Transport {
        override fun post(url: String, contentType: String, body: String, userAgent: String): Pair<Int, String> {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doInput = true
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            return try {
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
                status to String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    data class Pkce(val verifier: String, val challenge: String)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun newPkce(): Pkce {
        val verifier = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64Url(digest))
    }

    fun newState(): String = base64Url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun buildAuthUrl(state: String, pkce: Pkce): String = buildString {
        append(Constants.AUTH_ENDPOINT)
        append('?')
        append(
            listOf(
                "response_type" to "code",
                "client_id" to Constants.CLIENT_ID,
                "redirect_uri" to Constants.REDIRECT_URI,
                "scope" to Constants.SCOPE,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "state" to state,
                "id_token_add_organizations" to "true",
                "codex_cli_simplified_flow" to "true",
                "originator" to Constants.ORIGINATOR,
            ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        )
    }

    /** 用授权码换凭证；失败抛带上游描述的异常。 */
    fun exchangeCode(code: String, verifier: String): OAuthCredential {
        val body = listOf(
            "grant_type" to "authorization_code",
            "client_id" to Constants.CLIENT_ID,
            "code" to code,
            "redirect_uri" to Constants.REDIRECT_URI,
            "code_verifier" to verifier,
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val (status, raw) = transport.post(
            Constants.TOKEN_ENDPOINT, "application/x-www-form-urlencoded", body, Constants.USER_AGENT
        )
        if (status !in 200..299) throw IllegalStateException("OpenAI 授权失败（HTTP $status）：${errorMessage(raw)}")
        return toCredential(raw, previous = null)
    }

    /** 刷新凭证；响应缺 refresh_token 时沿用 [previous]。 */
    fun refresh(refreshToken: String, previous: OAuthCredential? = null): OAuthCredential {
        if (refreshToken.isBlank()) throw IllegalStateException("refresh_token 为空，无法刷新")
        val payload = JSONObject()
            .put("client_id", Constants.CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", refreshToken)
            .toString()
        val (status, raw) = transport.post(
            Constants.TOKEN_ENDPOINT, "application/json", payload, Constants.USER_AGENT
        )
        if (status !in 200..299) throw IllegalStateException("OpenAI 刷新失败（HTTP $status）：${errorMessage(raw)}")
        return toCredential(raw, previous)
    }

    private fun toCredential(raw: String, previous: OAuthCredential?): OAuthCredential {
        val json = JSONObject(raw)
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenAI 授权响应缺少 access_token")
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: previous?.refreshToken.orEmpty()
        val idToken = json.optString("id_token").takeIf { it.isNotBlank() }
        val expiresIn = json.optLong("expires_in", 0L)
        val expiresAt = if (expiresIn > 0) nowMillis() + expiresIn * 1000 else ChatGptJwt.expiresAtMillis(access)
        return OAuthCredential(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = expiresAt,
            accountId = ChatGptJwt.accountId(access).ifEmpty { previous?.accountId.orEmpty() },
            email = ChatGptJwt.email(idToken, access).ifEmpty { previous?.email.orEmpty() },
            plan = ChatGptJwt.planType(access).ifEmpty { previous?.plan.orEmpty() },
        )
    }

    private fun errorMessage(raw: String): String {
        runCatching {
            val obj = JSONObject(raw)
            obj.optString("error_description").takeIf { it.isNotBlank() }?.let { return it }
            obj.optString("error").takeIf { it.isNotBlank() }?.let { return it }
            obj.optJSONObject("error")?.let { nested ->
                listOf("code", "message", "type").firstNotNullOfOrNull { k ->
                    nested.optString(k).takeIf { it.isNotBlank() }
                }?.let { return it }
            }
        }
        return raw.take(200)
    }

    object Constants {
        /** Codex CLI 的公开 OAuth 客户端（public client，PKCE，无 secret）。 */
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ORIGINATOR = "codex_cli_rs"
        const val USER_AGENT = "codex_cli_rs/0.55.0 (Linux; Android) dsh-gpt"

        const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
        const val CODEX_BASE = "https://chatgpt.com/backend-api/codex"
        const val USAGE_ENDPOINT = "https://chatgpt.com/backend-api/wham/usage"

        /** 上游注册值，逐字符校验，不可改。 */
        const val CALLBACK_PORT = 1455
        const val CALLBACK_PATH = "/auth/callback"
        const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
        const val SCOPE = "openid profile email offline_access"

        const val LOGIN_TTL_MS = 10 * 60_000L
        const val REFRESH_LEEWAY_MS = 5 * 60_000L
        const val REFRESH_COOLDOWN_MS = 60_000L
        const val REFRESH_REUSE_MS = 30_000L
        const val DEFAULT_MODEL = "gpt-5.1-codex"
    }
}

/**
 * OAuth 凭证。字段是 TokenResponse 的原样投影。
 *
 * [refreshToken] 在刷新响应缺失时必须沿用旧值——服务端只在轮换时下发新值，置空等于把账号锁死。
 */
data class OAuthCredential(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAtMillis: Long = 0L,
    val accountId: String = "",
    val email: String = "",
    val plan: String = "",
) {
    val hasRefreshToken: Boolean get() = refreshToken.isNotBlank()

    fun isExpiringWithin(thresholdMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis <= 0L || expiresAtMillis - nowMillis <= thresholdMillis

    val planLabel: String get() = when (plan.lowercase()) {
        "free" -> "Free"
        "plus" -> "Plus"
        "pro" -> "Pro (More)"
        "prolite" -> "Pro"
        "promax" -> "Pro (Max)"
        "team" -> "Team"
        "business" -> "Business"
        "enterprise" -> "Enterprise"
        else -> plan.trim()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAtMillis)
        .put("account_id", accountId)
        .put("email", email)
        .put("plan", plan)

    companion object {
        fun fromJson(raw: String?): OAuthCredential? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                val access = obj.optString("access_token").ifEmpty { obj.optString("accessToken") }
                if (access.isBlank()) return null
                OAuthCredential(
                    accessToken = access,
                    refreshToken = obj.optString("refresh_token").ifEmpty { obj.optString("refreshToken") },
                    expiresAtMillis = obj.optLong("expires_at", ChatGptJwt.expiresAtMillis(access)),
                    accountId = obj.optString("account_id").ifEmpty { ChatGptJwt.accountId(access) },
                    email = obj.optString("email").ifEmpty { ChatGptJwt.email(null, access) },
                    plan = obj.optString("plan").ifEmpty { ChatGptJwt.planType(access) },
                )
            }.getOrNull()
        }
    }
}

/** 只解析、不验签的 JWT claim 读取器（仅取展示与路由信息）。 */
object ChatGptJwt {
    private const val API_AUTH_CLAIM = "https://api.openai.com/auth"
    private const val PROFILE_CLAIM = "https://api.openai.com/profile"

    fun claims(token: String): JSONObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(parts[1])
            JSONObject(String(payload, Charsets.UTF_8))
        }.getOrNull()
    }

    fun accountId(accessToken: String): String =
        runCatching { claims(accessToken)?.optJSONObject(API_AUTH_CLAIM)?.optString("chatgpt_account_id") }
            .getOrNull().orEmpty()

    fun planType(accessToken: String): String =
        runCatching { claims(accessToken)?.optJSONObject(API_AUTH_CLAIM)?.optString("chatgpt_plan_type") }
            .getOrNull().orEmpty()

    fun expiresAtMillis(accessToken: String): Long {
        val exp = runCatching { claims(accessToken)?.optLong("exp", 0L) }.getOrNull() ?: 0L
        return if (exp > 0) exp * 1000 else 0L
    }

    fun email(idToken: String?, accessToken: String): String {
        idToken?.let { token ->
            runCatching { claims(token)?.optJSONObject(PROFILE_CLAIM)?.optString("email") }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching { claims(accessToken)?.optString("email") }.getOrNull().orEmpty()
    }
}
