package com.gpt.hook.api

import java.util.concurrent.ConcurrentHashMap

/** Session lifecycle operations for SIWC profiles. Network calls must run off the main thread. */
internal class ChatGptPlanSessionManager(
    private val http: ChatGptPlanHttp = ChatGptPlanHttp(),
) {
    /**
     * Return a usable profile, refreshing just before expiry when the provider's
     * earliest_refresh_at gate permits it. Rotating refresh tokens are committed together.
     */
    fun getUsableProfile(
        store: ChatGptPlanProfileStore,
        profileId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatGptPlanProfile {
        val initial = store.snapshot().profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("ChatGPT profile not found")
        val lockKey = initial.clientId + "|" + initial.subject
        val lock = refreshLocks.computeIfAbsent(lockKey) { Any() }
        return synchronized(lock) {
            // Reload under the per-registration lock so concurrent callers cannot reuse
            // a refresh token that another request has already rotated.
            val profile = store.snapshot().profiles.firstOrNull { it.id == profileId }
                ?: throw IllegalArgumentException("ChatGPT profile not found")
            require(profile.canUseChatGptPlan) {
                "This ChatGPT account has not granted chatgpt.tokens.use.direct"
            }
            val expiresAt = profile.expiresAtMillis
                ?: throw IllegalStateException("ChatGPT access-token expiry is missing")
            val earliestRefreshAt = profile.earliestRefreshAtMillis ?: Long.MIN_VALUE
            if (nowMillis < earliestRefreshAt) {
                if (nowMillis >= expiresAt) {
                    throw IllegalStateException("ChatGPT token expired; refresh is unavailable until the provider's earliest_refresh_at")
                }
                return@synchronized profile
            }
            if (nowMillis < expiresAt - ChatGptPlanOAuth.REFRESH_LEEWAY_MS) {
                return@synchronized profile
            }

            val refreshToken = profile.refreshToken
                ?: throw IllegalStateException("ChatGPT refresh token is missing")
            val response = http.refresh(profile.clientId, refreshToken)
            require(response.status in 200..299) {
                "ChatGPT token refresh failed (HTTP ${response.status})" +
                    response.requestId?.let { ", request_id=$it" }.orEmpty()
            }
            val tokens = ChatGptPlanOAuth.parseTokenResponse(
                response.body,
                nowMillis,
                previousScopes = profile.scopes,
            )
            require(tokens.canUseChatGptPlan()) {
                "Refreshed ChatGPT credentials no longer grant chatgpt.tokens.use.direct"
            }
            // Keep the previously verified ID token; a refreshed ID token has a different
            // validation context and must not replace identity data without verification.
            val updated = profile.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                scopes = tokens.scopes,
                expiresAtMillis = tokens.expiresAtMillis,
                earliestRefreshAtMillis = tokens.earliestRefreshAtMillis,
            )
            store.save(updated)
            updated
        }
    }

    /**
     * Revoke the renewable session when possible, then always clear local credentials.
     * The registration/client mapping is retained so the account can sign in again.
     *
     * @return true only when the provider confirmed revocation with HTTP 200.
     */
    fun signOut(store: ChatGptPlanProfileStore, profileId: String): Boolean {
        val profile = store.snapshot().profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("ChatGPT profile not found")
        val revoked = profile.refreshToken?.let { token ->
            runCatching {
                http.revoke(profile.clientId, token).status == 200
            }.getOrDefault(false)
        } ?: false
        store.clearTokens(profileId)
        return revoked
    }

    private companion object {
        private val refreshLocks = ConcurrentHashMap<String, Any>()
    }
}
