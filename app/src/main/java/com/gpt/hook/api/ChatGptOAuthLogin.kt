package com.gpt.hook.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号授权登录：OAuth 授权后由服务端把凭证交回 App。
 *
 * 这是唯一能"自动拿到凭证"的通路 —— 网页版的会话 cookie 由 Chrome 用
 * Android Keystore 加密，第三方取不到；网页端也没有 OAuth 回调机制。
 *
 * 等待逻辑跑在进程级作用域，不绑 Composable，避免打开浏览器后协程被取消
 * 导致回环端口被关（那会让回调变成 ERR_CONNECTION_REFUSED）。
 */
object ChatGptOAuthLogin {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val TIMEOUT_MS = 15L * 60 * 1000

    @Volatile
    var inFlight: Boolean = false
        private set

    @Volatile
    var lastStatus: String? = null

    fun start(context: Context, onDone: (Boolean, String) -> Unit) {
        if (inFlight) return
        inFlight = true
        lastStatus = "正在打开授权页…"
        ChatGptLoginWaitService.start(context.applicationContext)

        scope.launch {
            val server = OAuthLoopbackServer()
            val message = try {
                if (!server.start()) {
                    "回调端口 1455 被占用：" + (server.failureMessage() ?: "请稍后重试")
                } else {
                    val oauth = ChatGptOAuth()
                    val state = oauth.newState()
                    val pkce = oauth.newPkce()
                    openLoginPage(context, oauth.buildAuthUrl(state, pkce))
                    lastStatus = "已打开授权页，等待确认…"
                    val callback = withContext(Dispatchers.IO) { server.await(TIMEOUT_MS) }
                    if (callback == null) {
                        server.failureMessage() ?: "授权超时或已取消"
                    } else {
                        val credential = withContext(Dispatchers.IO) {
                            oauth.exchangeCode(callback.code, pkce.verifier)
                        }
                        ChatGptCredentialStore.attach(AndroidSecretBox(context.applicationContext))
                        ChatGptCredentialStore.save(credential)
                        "授权成功，凭证已加密保存"
                    }
                }
            } catch (error: Throwable) {
                error.message ?: error.javaClass.simpleName
            } finally {
                server.stop()
            }
            inFlight = false
            lastStatus = message
            ChatGptLoginWaitService.stop(context.applicationContext)
            onDone(message.startsWith("授权成功"), message)
        }
    }

    /** Custom Tab 打开授权页：共享浏览器登录态，用户不用重新输账号密码。 */
    private fun openLoginPage(context: Context, url: String) {
        val opened = runCatching {
            val tabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tabs.launchUrl(context, Uri.parse(url))
            true
        }.getOrDefault(false)
        if (opened) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
