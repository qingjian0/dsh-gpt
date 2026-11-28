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
 * 账号登录：Custom Tabs 打开授权页，回调打到 127.0.0.1:1455，
 * 由 [OAuthLoopbackServer] 接住，页面直接显示「登录成功 / 账号 X 已添加」。
 *
 * 等待逻辑跑在进程级作用域，不绑 Composable，避免打开浏览器后协程被取消、
 * 回环端口被关掉（那会让回调变成 ERR_CONNECTION_REFUSED）。
 */
object ChatGptOAuthLogin {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val TIMEOUT_MS = 15L * 60 * 1000

    @Volatile
    var inFlight: Boolean = false
        private set

    @Volatile
    var lastStatus: String? = null

    @Volatile
    var lastAccount: String? = null

    fun start(context: Context, onDone: (Boolean, String) -> Unit) {
        if (inFlight) return
        inFlight = true
        lastStatus = "正在打开授权页…"
        ChatGptLoginWaitService.start(context.applicationContext)

        scope.launch {
            val oauth = ChatGptOAuth()
            ChatGptCredentialStore.attach(AndroidSecretBox(context.applicationContext))
            val state = oauth.newState()
            val pkce = oauth.newPkce()
            var accountName: String? = null

            val server = OAuthLoopbackServer(
                onCode = { code ->
                    // 回调当场换 token：拿得到账号名，结果页就能直接显示；
                    // 换失败返回 null，页面退化为通用的成功提示。
                    runCatching {
                        val credential = oauth.exchangeCode(code, pkce.verifier)
                        ChatGptCredentialStore.save(credential)
                        val who = credential.email.ifBlank { credential.accountId }
                        accountName = who
                        who
                    }.getOrNull()
                }
            )

            val message = try {
                if (!server.start()) {
                    "回调端口 1455 被占用：" + (server.failureMessage() ?: "请稍后重试")
                } else {
                    openLoginPage(context, oauth.buildAuthUrl(state, pkce))
                    lastStatus = "已打开授权页，等待确认…"
                    withContext(Dispatchers.IO) { server.await(TIMEOUT_MS) }
                    val stored = ChatGptCredentialStore.load()
                    if (stored == null) {
                        server.failureMessage() ?: "授权未完成"
                    } else {
                        lastAccount = accountName ?: stored.email
                        "登录成功：账号 " + (lastAccount ?: "已添加")
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
            onDone(message.startsWith("登录成功"), message)
        }
    }

    /** Custom Tab 打开授权页：共享浏览器登录态，不用重新输账号密码。 */
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
