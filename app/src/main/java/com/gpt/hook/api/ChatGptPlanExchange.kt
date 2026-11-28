package com.gpt.hook.api

import java.util.UUID

/**
 * 把「回调 URL → 加密保存账号」这一段抽成共享逻辑。
 *
 * 两条登录路径都要用它：
 * 1. 系统浏览器 + 回环 socket（[ChatGptPlanLoopbackServer]）；
 * 2. App 内 WebView 拦截回调（[ChatGptPlanWebLoginActivity]）。
 *
 * 后者不监听任何端口，也不依赖进程存活，是 MIUI 上最稳的路径。
 */
internal object ChatGptPlanExchange {

    fun complete(
        attempt: ChatGptPlanOAuth.Attempt,
        callback: ChatGptPlanOAuth.Callback,
        store: ChatGptPlanProfileStore,
        http: ChatGptPlanHttp,
        previous: ChatGptPlanProfile?,
    ): ChatGptPlanProfile {
        // 新注册的 client 先落盘，避免 code 换 token 失败后丢失注册关系。
        if (previous == null || callback.clientId != previous.clientId) {
            store.savePendingClientId(callback.clientId)
        }
        val tokenResponse = http.exchangeCode(attempt, callback)
        require(tokenResponse.status in 200..299) {
            "ChatGPT token exchange failed (HTTP ${tokenResponse.status})" +
                tokenResponse.requestId?.let { ", request_id=$it" }.orEmpty()
        }
        val tokenSet = ChatGptPlanOAuth.parseTokenResponse(tokenResponse.body, System.currentTimeMillis())
        require(tokenSet.idToken.isNotBlank()) { "ChatGPT did not return an ID token; sign-in cannot be verified" }
        val metadata = http.discover()
        val jwks = http.fetchJwks(metadata)
        val identity = ChatGptPlanOidcVerifier.verify(
            tokenSet.idToken,
            callback.clientId,
            attempt.nonce,
            jwks,
        )
        if (previous != null) {
            require(previous.subject == identity.subject && previous.clientId == callback.clientId) {
                "This sign-in returned a different ChatGPT account or registration"
            }
        }
        val profile = ChatGptPlanProfile(
            id = previous?.id ?: UUID.randomUUID().toString(),
            clientId = callback.clientId,
            subject = identity.subject,
            email = identity.email,
            name = identity.name,
            idToken = tokenSet.idToken,
            accessToken = tokenSet.accessToken,
            refreshToken = tokenSet.refreshToken,
            scopes = tokenSet.scopes,
            expiresAtMillis = tokenSet.expiresAtMillis,
            earliestRefreshAtMillis = tokenSet.earliestRefreshAtMillis,
        )
        store.save(profile, makeActive = true)
        store.clearPendingClientId()
        return profile
    }
}
