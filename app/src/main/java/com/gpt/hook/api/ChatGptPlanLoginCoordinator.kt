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
        val listener = ChatGptPlanLoopbackServer.create(store.hostId(), savedRegistration, store.callbackPort())
        try {
            val attempt = listener.attempt
            val authorizationUrl = ChatGptPlanOAuth.buildAuthorizationUrl(
                attempt = attempt,
                appName = APP_NAME,
                idTokenHint = previous?.idToken,
                loginHint = previous?.email,
            )
            // Never log or expose authorizationUrl: returning-account URLs can contain id_token_hint.
            openLoginPage(context, authorizationUrl)
            return PendingLogin(listener, store, previous, http)
        } catch (error: Throwable) {
            listener.close()
            throw error
        }
    }

    /**
     * 用 Chrome Custom Tabs 打开授权页，而不是裸 ACTION_VIEW。
     *
     * 为什么是 Custom Tab 而不是 WebView：授权页需要复用浏览器里**已登录的 ChatGPT 会话**，
     * 用户点一下就能授权；WebView 是独立 cookie 罐，每次都得重新输账号密码。
     *
     * 为什么还要前台服务：Custom Tab 跑在浏览器进程里，回调仍然由本进程的回环 socket 接住。
     * MIUI 切走三秒就把本应用降为 idle 并冻结，冻结后 socket 不再 accept，浏览器回调必然失败。
     * 所以「Custom Tab（观感 + 登录态）+ 前台服务（进程不被冻结）」两者缺一不可。
     */
    private fun openLoginPage(context: Context, url: String): Boolean {
        val opened = runCatching {
            val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(context, Uri.parse(url))
            true
        }.getOrDefault(false)
        if (opened) return true
        // 设备上没有 Custom Tab 提供方时退回系统浏览器。
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /**
     * App 内 WebView 登录：只创建 attempt 与授权 URL，不绑定任何监听端口。
     * 回调由 [ChatGptPlanWebLoginActivity] 在 WebView 内拦截，因此
     * 不存在「进程被冻结、socket 不再 accept」的问题，也不需要前台服务保活。
     */
    fun beginWebIntent(
        context: Context,
        selectedProfileId: String? = null,
        forceNewRegistration: Boolean = false,
    ): android.content.Intent {
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
        // 端口只为了让 redirect_uri 合法；WebView 在请求发出前就截获它，端口不会被真正监听。
        val port = store.callbackPort()
        val attempt = ChatGptPlanOAuth.createAttempt(
            "http://127.0.0.1:$port/auth/callback",
            store.hostId(),
            savedRegistration,
        )
        val url = ChatGptPlanOAuth.buildAuthorizationUrl(
            attempt = attempt,
            appName = APP_NAME,
            idTokenHint = previous?.idToken,
            loginHint = previous?.email,
        )
        return ChatGptPlanWebLoginActivity.intent(context, url, attempt, store, http, previous)
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
        fun complete(timeoutMillis: Long = 15 * 60 * 1000L): ChatGptPlanProfile {
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
