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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 App 内 WebView 完成 chatgpt.com 登录，自动取到网页版 API 凭证。
 *
 * 为什么需要它：网页通道（`chatgpt.com/backend-api`）要的是网页会话，手工去
 * `/api/auth/session` 复制 accessToken 太麻烦。这里让用户在 WebView 里正常登录一次，
 * `CookieManager` 直接读到 `__Secure-*-session-token`，再用它请求 `/api/auth/session`
 * 换 accessToken —— 用户不用碰任何 cookie 字符串。
 */
class ChatGptWebLoginActivity : Activity() {

    private var webView: WebView? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var harvesting = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 20)
            setBackgroundColor(Color.parseColor("#F6F8FB"))
        }
        status = TextView(this).apply {
            text = "登录 ChatGPT 后会自动完成，无需手动复制任何内容。"
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
            // 去掉 wv 标记，降低被判定为"不安全浏览器"的概率
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(" Version/4.0", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                progress?.visibility = android.view.View.GONE
                harvest()
            }
        }

        root.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(
            header,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)

        wv.loadUrl("https://chatgpt.com/")
    }

    /** 只要 cookie 里出现会话 token 就尝试换取 accessToken。 */
    private fun harvest() {
        if (harvesting || finished) return
        val cookie = CookieManager.getInstance().getCookie("https://chatgpt.com") ?: return
        val session = SESSION_TOKEN.find(cookie)?.groupValues?.getOrNull(1)?.trim()
        if (session.isNullOrBlank()) return
        harvesting = true
        status?.text = "已获取登录态，正在换取 API 凭证…"
        Thread {
            val token = runCatching { fetchAccessToken(cookie) }.getOrNull()
            runOnUiThread {
                if (token.isNullOrBlank()) {
                    harvesting = false
                    status?.text = "已登录，但换取 accessToken 失败；请稍等页面自动重试。"
                    return@runOnUiThread
                }
                val current = RelayConfig.load(this)
                RelayConfig.save(
                    this,
                    current.copy(
                        proxyMode = "web",
                        webAccessToken = token,
                        webSessionToken = session,
                    ),
                )
                finished = true
                status?.text = "完成：网页通道已配置"
                setResult(RESULT_OK)
                finish()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetchAccessToken(cookie: String): String? {
        val conn = URL(SESSION_ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("User-Agent", WEB_UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Referer", "https://chatgpt.com/")
        if (conn.responseCode !in 200..299) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).optString("accessToken").takeIf { it.isNotBlank() }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ENDPOINT = "https://chatgpt.com/api/auth/session"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** Auth.js v4 与 v5 的 cookie 名不同，两种都认。 */
        private val SESSION_TOKEN = Regex(
            "__Secure-(?:next-auth|authjs)\\.session-token=([^;]+)"
        )

        fun intent(context: Context): Intent =
            Intent(context, ChatGptWebLoginActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
