package com.gpt.hook.api

/** Session lifecycle operations for SIWC profiles. Network calls must run off the main thread. */
internal class ChatGptPlanSessionManager(
    private val http: ChatGptPlanHttp = ChatGptPlanHttp(),
) {
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
}
