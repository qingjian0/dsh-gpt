package com.gpt.hook.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/**
 * Orchestrates browser-based SIWC login without WebView or manual token entry.
 * Call begin() on the UI thread; call PendingLogin.complete() on a worker thread.
 */
internal class ChatGptPlanLoginCoordinator(
    private val http: ChatGptPlanHttp = ChatGptPlanHttp(),
) {
    fun begin(
        context: Context,
        selectedProfileId: String? = null,
        forceNewRegistration: Boolean = false,
    ): PendingLogin {
        val store = ChatGptPlanProfileStore(context)
        val previous = selectedProfileId?.let { id ->
            store.snapshot().profiles.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Selected ChatGPT profile was not found")
        }
        val savedRegistration = when {
            forceNewRegistration -> null
            previous != null -> previous.clientId
            else -> store.pendingClientId()
        }
        val listener = ChatGptPlanLoopbackServer.create(store.hostId(), savedRegistration)
        try {
            val attempt = listener.attempt
            val authorizationUrl = ChatGptPlanOAuth.buildAuthorizationUrl(
                attempt = attempt,
                appName = APP_NAME,
                idTokenHint = previous?.idToken,
                loginHint = previous?.email,
            )
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Never log or expose authorizationUrl: returning-account URLs can contain id_token_hint.
            context.startActivity(intent)
            return PendingLogin(listener, store, previous, http)
        } catch (error: Throwable) {
            listener.close()
            throw error
        }
    }

    internal class PendingLogin(
        private val listener: ChatGptPlanLoopbackServer,
        private val store: ChatGptPlanProfileStore,
        private val previous: ChatGptPlanProfile?,
        private val http: ChatGptPlanHttp,
    ) : AutoCloseable {
        private var completed = false

        /** The attempt frozen at begin() time; usable for manual callback fallback. */
        fun attempt(): ChatGptPlanOAuth.Attempt = listener.attempt

        @Synchronized
        fun complete(timeoutMillis: Long = 3 * 60 * 1000L): ChatGptPlanProfile {
            check(!completed) { "This ChatGPT sign-in attempt has already been consumed" }
            completed = true
            try {
                finish(listener.await(timeoutMillis))
            } finally {
                listener.close()
            }
            return completedProfile!!
        }

        /**
         * Manual fallback: the browser reached 127.0.0.1 but the loopback socket was already gone
         * (process frozen or the waiting coroutine cancelled). Paste the full callback URL here.
         */
        @Synchronized
        fun completeWithCallbackUrl(callbackUrl: String): ChatGptPlanProfile {
            check(!completed) { "This ChatGPT sign-in attempt has already been consumed" }
            completed = true
            try {
                val attempt = listener.attempt
                val callback = ChatGptPlanOAuth.validateCallback(
                    callbackUrl.trim(),
                    attempt.state,
                    attempt.redirectUri,
                    if (attempt.isFirstRegistration) null else attempt.clientId,
                )
                finish(callback)
            } finally {
                listener.close()
            }
            return completedProfile!!
        }

        @Volatile
        private var completedProfile: ChatGptPlanProfile? = null

        private fun finish(callback: ChatGptPlanOAuth.Callback) {
            run {
                // Save a newly issued registration before the one-time code exchange.
                if (previous == null || callback.clientId != previous.clientId) {
                    store.savePendingClientId(callback.clientId)
                }
                val tokenResponse = http.exchangeCode(listener.attempt, callback)
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
                    listener.attempt.nonce,
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
                completedProfile = profile
            }
        }

        override fun close() {
            listener.close()
        }
    }

    private companion object {
        const val APP_NAME = "DSH GPT"
    }
}
