package com.gpt.hook.api

import android.content.Context

/** 本地 API / 反代网关的可持久化配置。 */
data class RelayConfig(
    val enabled: Boolean = false,
    val port: Int = 8766,
    val lan: Boolean = false,
    val apiKey: String = "",
    val upstreamBase: String = "https://android.chat.openai.com",
    val accessToken: String = "",
    val oaiDid: String = "",
    val sessionId: String = "",
    val userAgent: String = "ChatGPT/1.2026.321 (Android 15; 22081212C)",
) {
    val ready: Boolean get() = accessToken.isNotBlank() && oaiDid.isNotBlank()

    companion object {
        private const val PREFS = "relay"

        fun load(ctx: Context): RelayConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = RelayConfig()
            return RelayConfig(
                enabled = p.getBoolean("enabled", defaults.enabled),
                port = p.getInt("port", defaults.port),
                lan = p.getBoolean("lan", defaults.lan),
                apiKey = p.getString("apiKey", defaults.apiKey) ?: defaults.apiKey,
                upstreamBase = p.getString("upstreamBase", defaults.upstreamBase) ?: defaults.upstreamBase,
                accessToken = p.getString("accessToken", "") ?: "",
                oaiDid = p.getString("oaiDid", "") ?: "",
                sessionId = p.getString("sessionId", "") ?: "",
                userAgent = p.getString("userAgent", defaults.userAgent) ?: defaults.userAgent,
            )
        }

        fun save(ctx: Context, cfg: RelayConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", cfg.enabled)
                .putInt("port", cfg.port)
                .putBoolean("lan", cfg.lan)
                .putString("apiKey", cfg.apiKey)
                .putString("upstreamBase", cfg.upstreamBase)
                .putString("accessToken", cfg.accessToken)
                .putString("oaiDid", cfg.oaiDid)
                .putString("sessionId", cfg.sessionId)
                .putString("userAgent", cfg.userAgent)
                .apply()
        }

        /** 从 kit.json 导入（{access_token, oai_did, session_id}），设备侧或 adb push 均可。 */
        fun importKit(ctx: Context, json: String): RelayConfig {
            val o = org.json.JSONObject(json)
            val cur = load(ctx)
            val next = cur.copy(
                accessToken = o.optString("access_token", cur.accessToken),
                oaiDid = o.optString("oai_did", o.optString("oai-did", cur.oaiDid)),
                sessionId = o.optString("session_id", o.optString("sessionId", cur.sessionId)),
            )
            save(ctx, next)
            return next
        }
    }
}
