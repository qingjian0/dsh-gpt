package com.gpt.hook.api

import android.content.Context
import java.util.UUID

/** Persisted ChatGPT gateway configuration. */
data class RelayConfig(
    val enabled: Boolean = false,
    val port: Int = 8766,
    val lan: Boolean = true,
    val apiKey: String = "",
    val proxyMode: String = "hook",
    val upstreamBase: String = "https://android.chat.openai.com",
    val accessToken: String = "",
    val oaiDid: String = "",
    val sessionId: String = "",
    /**
     * 网页版通道改为 **OAuth 授权码 + PKCE**（Sign in with ChatGPT）。
     *
     * 不能再复用 chatgpt.com 网页 cookie：官方订阅访问走 api.openai.com/v1/responses，
     * 旧 webAccessToken 字段仅为兼容旧配置保留（迁移后由 OAuth 凭证接管）。
     */
    val webBase: String = "https://chatgpt.com",
    val webAccessToken: String = "",
    val webSessionToken: String = "",
    val webUserAgent: String = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
    val connectorId: String = "",
    val model: String = "auto",
    val fastMode: Boolean = false,
    val reasoningEffort: String = "medium",
    val searchEnabled: Boolean = false,
    val temporaryChat: Boolean = false,
    val userAgent: String = "ChatGPT/1.2026.321 (Android 15; 22081212C)",
) {
    val ready: Boolean
        get() = if (proxyMode == "web") webAccessToken.isNotBlank()
        else accessToken.isNotBlank() && oaiDid.isNotBlank()

    /** web 通道的真实就绪判断：OAuth 凭证（加密存储）是否存在且未失效。 */
    fun readyWithOauth(oauth: OAuthCredential?): Boolean =
        if (proxyMode == "web") oauth != null && !ChatGptCredentialStore.isRevoked(oauth.accessToken)
        else accessToken.isNotBlank() && oaiDid.isNotBlank()

    companion object {
        private const val PREFS = "relay"

        fun load(ctx: Context): RelayConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.contains("codexToken")) p.edit().remove("codexToken").apply()
            val defaults = RelayConfig()
            val storedApiKey = p.getString("apiKey", null)
            val apiKey = storedApiKey?.takeIf { it.isNotBlank() }
                ?: "sk-local-" + UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", "")
            if (storedApiKey != apiKey) p.edit().putString("apiKey", apiKey).apply()
            return RelayConfig(
                enabled = p.getBoolean("enabled", defaults.enabled),
                port = p.getInt("port", defaults.port),
                lan = p.getBoolean("lan", defaults.lan),
                apiKey = apiKey,
                proxyMode = p.getString("proxyMode", defaults.proxyMode) ?: defaults.proxyMode,
                upstreamBase = p.getString("upstreamBase", defaults.upstreamBase) ?: defaults.upstreamBase,
                accessToken = p.getString("accessToken", "") ?: "",
                oaiDid = p.getString("oaiDid", "") ?: "",
                sessionId = p.getString("sessionId", "") ?: "",
                webBase = p.getString("webBase", defaults.webBase) ?: defaults.webBase,
                webAccessToken = p.getString("webAccessToken", "") ?: "",
                webSessionToken = p.getString("webSessionToken", "") ?: "",
                webUserAgent = p.getString("webUserAgent", defaults.webUserAgent) ?: defaults.webUserAgent,
                connectorId = p.getString("connectorId", "") ?: "",
                model = p.getString("model", defaults.model) ?: defaults.model,
                fastMode = p.getBoolean("fastMode", defaults.fastMode),
                reasoningEffort = p.getString("reasoningEffort", defaults.reasoningEffort) ?: defaults.reasoningEffort,
                searchEnabled = p.getBoolean("searchEnabled", defaults.searchEnabled),
                temporaryChat = p.getBoolean("temporaryChat", defaults.temporaryChat),
                userAgent = p.getString("userAgent", defaults.userAgent) ?: defaults.userAgent,
            )
        }

        fun save(ctx: Context, cfg: RelayConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", cfg.enabled)
                .putInt("port", cfg.port)
                .putBoolean("lan", cfg.lan)
                .putString("apiKey", cfg.apiKey)
                .putString("proxyMode", if (cfg.proxyMode == "web") "web" else "hook")
                .putString("upstreamBase", cfg.upstreamBase)
                .putString("accessToken", cfg.accessToken)
                .putString("oaiDid", cfg.oaiDid)
                .putString("sessionId", cfg.sessionId)
                .putString("webBase", cfg.webBase)
                .putString("webAccessToken", cfg.webAccessToken)
                .putString("webSessionToken", cfg.webSessionToken)
                .putString("webUserAgent", cfg.webUserAgent)
                .putString("connectorId", cfg.connectorId)
                .putString("model", cfg.model)
                .putBoolean("fastMode", cfg.fastMode)
                .putString("reasoningEffort", normalizeEffort(cfg.reasoningEffort))
                .putBoolean("searchEnabled", cfg.searchEnabled)
                .putBoolean("temporaryChat", cfg.temporaryChat)
                .putString("userAgent", cfg.userAgent)
                .remove("codexToken")
                .apply()
        }

        fun normalizeEffort(value: String): String = when (value.lowercase()) {
            "low", "minimal" -> "low"
            "high" -> "high"
            else -> "medium"
        }

        /** Import a ChatGPT kit.json. Secrets remain local and are never returned by status APIs. */
        fun importKit(ctx: Context, json: String): RelayConfig {
            val o = org.json.JSONObject(json)
            val cur = load(ctx)
            // 兼容 oauth 形状：{oauth:{access_token,refresh_token,expires_at}} 或顶层 access_token+refresh_token
            val oauthObj = o.optJSONObject("oauth") ?: o
            OAuthCredential.fromJson(oauthObj.toString())?.let { ChatGptCredentialStore.save(it) }
            val next = cur.copy(
                accessToken = o.optString("access_token", cur.accessToken),
                oaiDid = o.optString("oai_did", o.optString("oai-did", cur.oaiDid)),
                sessionId = o.optString("session_id", o.optString("sessionId", cur.sessionId)),
                connectorId = o.optString("connectorId", cur.connectorId),
                webAccessToken = o.optString("web_access_token", cur.webAccessToken),
                webSessionToken = o.optString("web_session_token", cur.webSessionToken),
                proxyMode = o.optString("proxy_mode", cur.proxyMode).let { if (it == "web") "web" else "hook" },
                temporaryChat = o.optBoolean("temporaryChat", cur.temporaryChat),
            )
            save(ctx, next)
            return next
        }
    }
}
