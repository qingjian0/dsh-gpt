package com.gpt.hook.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * App 内 WebView 完成 SIWC 授权。
 *
 * 相比「系统浏览器 + 回环 socket」的优势：
 * - 不监听任何端口，回调 URL 由 [WebViewClient.shouldOverrideUrlLoading] 直接截获，
 *   因此不存在 MIUI 把进程降为 idle、socket 不再 accept 的问题；
 * - 不依赖前台服务保活，页面始终在前台；
 * - 授权完成后 Activity 自己 finish，用户直接回到主界面，全程不跳出 App。
 *
 * 授权尝试由 [ChatGptPlanLoginCoordinator.beginWeb] 建好后放进 [ChatGptPlanWebLoginSession]，
 * 本 Activity 只负责展示与拦截。
 */
class ChatGptPlanWebLoginActivity : Activity() {

    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ChatGptPlanWebLoginSession
        val attempt = session.attempt
        if (attempt == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 24)
            setBackgroundColor(Color.parseColor("#F6F8FB"))
        }
        status = TextView(this).apply {
            text = "在下方完成 ChatGPT 授权，成功后会自动返回。"
            setTextColor(Color.parseColor("#1B1C1E"))
            textSize = 14f
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        bar.addView(status)
        bar.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8))

        val wv = WebView(this)
        webView = wv
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            @Suppress("DEPRECATION")
            allowFileAccess = false
            @Suppress("DEPRECATION")
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(" Version/4.0", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                intercept(request.url.toString())

            @Deprecated("兼容旧回调")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                intercept(url)

            override fun onPageFinished(view: WebView, url: String) {
                progress?.visibility = android.view.View.GONE
            }
        }

        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)

        wv.loadUrl(session.authorizationUrl.orEmpty())
    }

    /** 命中 redirect_uri 就当场换 token，不再把请求发出去。 */
    private fun intercept(url: String): Boolean {
        if (handled) return true
        val attempt = ChatGptPlanWebLoginSession.attempt ?: return false
        val prefix = attempt.redirectUri.substringBefore('?')
        if (!url.startsWith(prefix)) return false
        handled = true
        progress?.visibility = android.view.View.GONE
        status?.text = "正在完成登录…"
        Thread {
            val result = runCatching {
                val callback = ChatGptPlanOAuth.validateCallback(
                    url,
                    attempt.state,
                    attempt.redirectUri,
                    if (attempt.isFirstRegistration) null else attempt.clientId,
                )
                ChatGptPlanExchange.complete(
                    attempt,
                    callback,
                    ChatGptPlanWebLoginSession.requireStore(),
                    ChatGptPlanWebLoginSession.requireHttp(),
                    ChatGptPlanWebLoginSession.previous,
                )
            }
            runOnUiThread {
                result.onSuccess { profile ->
                    ChatGptPlanWebLoginSession.profile = profile
                    ChatGptPlanWebLoginSession.error = null
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { error ->
                    ChatGptPlanWebLoginSession.profile = null
                    ChatGptPlanWebLoginSession.error = error.message ?: error.javaClass.simpleName
                    status?.text = "授权未完成：${ChatGptPlanWebLoginSession.error}"
                    setResult(RESULT_CANCELED)
                    // 给用户看清错误再退出
                    status?.postDelayed({ finish() }, 2_500)
                }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            ChatGptPlanWebLoginSession.error = "用户取消授权"
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        fun intent(
            context: Context,
            authorizationUrl: String,
            attempt: ChatGptPlanOAuth.Attempt,
            store: ChatGptPlanProfileStore,
            http: ChatGptPlanHttp,
            previous: ChatGptPlanProfile?,
        ): Intent {
            ChatGptPlanWebLoginSession.attempt = attempt
            ChatGptPlanWebLoginSession.authorizationUrl = authorizationUrl
            ChatGptPlanWebLoginSession.store = store
            ChatGptPlanWebLoginSession.http = http
            ChatGptPlanWebLoginSession.previous = previous
            ChatGptPlanWebLoginSession.profile = null
            ChatGptPlanWebLoginSession.error = null
            return Intent(context, ChatGptPlanWebLoginActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

/** WebView 登录的一次尝试：Activity 重建后仍可取回。 */
internal object ChatGptPlanWebLoginSession {
    @Volatile var attempt: ChatGptPlanOAuth.Attempt? = null
    @Volatile var authorizationUrl: String? = null
    @Volatile var store: ChatGptPlanProfileStore? = null
    @Volatile var http: ChatGptPlanHttp? = null
    @Volatile var previous: ChatGptPlanProfile? = null
    @Volatile var profile: ChatGptPlanProfile? = null
    @Volatile var error: String? = null

    fun requireStore(): ChatGptPlanProfileStore =
        store ?: throw IllegalStateException("登录会话已失效，请重新发起登录")

    fun requireHttp(): ChatGptPlanHttp =
        http ?: throw IllegalStateException("登录会话已失效，请重新发起登录")

    fun clear() {
        attempt = null
        authorizationUrl = null
        store = null
        http = null
        previous = null
        profile = null
        error = null
    }
}
