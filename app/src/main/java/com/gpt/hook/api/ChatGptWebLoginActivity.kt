package com.gpt.hook.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/**
 * 网页登录：WebView 打开 ChatGPT 官方登录入口，登录后页面自动落到
 * `/api/auth/session`（这里直接返回含 accessToken 的 JSON），
 * 应用读出页面文本，解析出凭证并当场保存，然后把视图换成「登录成功」结果页。
 *
 * 与参考实现里 Trae 的做法同构：WebView 拦截页面跳转、由应用侧完成回调处理，
 * 不使用任何第三方授权客户端。
 */
class ChatGptWebLoginActivity : Activity() {

    private var webView: WebView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 24)
            setBackgroundColor(Color.parseColor("#F6F8FB"))
        }
        status = TextView(this).apply {
            text = "登录后会自动完成，无需复制任何内容。"
            setTextColor(Color.parseColor("#1B1C1E"))
            textSize = 13f
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        header.addView(status)
        header.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6))

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
            // 去掉 wv 标记，减少被判定为"不安全浏览器"的概率
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(" Version/4.0", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                progress?.visibility = android.view.View.GONE
                // 登录完成后页面就是 /api/auth/session 的 JSON
                if (url.contains("/api/auth/session")) {
                    harvest()
                }
            }
        }

        root.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        wv.loadUrl(LOGIN_URL)
    }

    /** 读出页面文本，解析 accessToken / 邮箱，保存后显示结果页。 */
    private fun harvest() {
        if (finished) return
        val view = webView ?: return
        view.evaluateJavascript("document.body.innerText") { raw ->
            if (finished) return@evaluateJavascript
            val text = unescapeJson(raw)
            val parsed = parseSession(text)
            if (parsed == null) {
                status?.text = "页面已打开，但还没拿到凭证，请稍候…"
                return@evaluateJavascript
            }
            finished = true
            val (token, account) = parsed
            val current = RelayConfig.load(this)
            RelayConfig.save(
                this,
                current.copy(proxyMode = "web", webAccessToken = token, webSessionToken = ""),
            )
            progress?.visibility = android.view.View.GONE
            setContentView(resultView(account))
        }
    }

    /** evaluateJavascript 返回的是带转义的 JSON 字符串。 */
    private fun unescapeJson(raw: String): String =
        runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault(raw)

    /** 从 session JSON 里取 accessToken 与账号。 */
    private fun parseSession(text: String): Pair<String, String>? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val body = text.substring(start)
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val token = obj.optString("accessToken").takeIf { it.isNotBlank() } ?: return null
        val user = obj.optJSONObject("user")
        val account = user?.optString("email").orEmpty()
            .ifBlank { user?.optString("name").orEmpty() }
        return token to account
    }

    /** 结果页：与参考实现一致的中文提示。 */
    private fun resultView(account: String): android.view.View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F6F8FB"))
            setPadding(64, 64, 64, 64)
        }
        container.addView(TextView(this).apply {
            text = "登录成功"
            setTextColor(Color.parseColor("#1B1C1E"))
            textSize = 20f
            gravity = android.view.Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = if (account.isNotBlank()) "账号 $account 已添加，可以关闭本页并返回应用。"
            else "账号已添加，可以关闭本页并返回应用。"
            setTextColor(Color.parseColor("#5A5F66"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })
        return container
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL =
            "https://chatgpt.com/auth/login_with?callback_path=%2Fapi%2Fauth%2Fsession"

        fun intent(context: Context): Intent =
            Intent(context, ChatGptWebLoginActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
