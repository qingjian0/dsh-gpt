package com.gpt.hook.api

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * 网页版凭证：用 Chrome Custom Tabs 打开 chatgpt.com/api/auth/session。
 *
 * 该页在 Chrome 内带着用户已登录的会话，直接把 session JSON（含 accessToken）
 * 渲染出来，全程不涉及任何第三方授权客户端。用户复制后回到 App，这里从剪贴板解析。
 */
object ChatGptWebToken {

    /**
     * 官方网页登录入口。未登录时先走登录流程，完成后落到 callback_path；
     * 这里指到 /api/auth/session，于是最终页面直接是含 accessToken 的 JSON。
     */
    const val SESSION_URL = "https://chatgpt.com/auth/login_with?callback_path=%2Fapi%2Fauth%2Fsession"

    const val HOME_URL = "https://chatgpt.com/"

    /** 打开首页：用户可以在 Custom Tab 里正常登录。 */
    fun openHome(context: Context): Boolean = launch(context, HOME_URL)

    /** 打开登录入口，完成后自动落到显示 accessToken 的 JSON 页。 */
    fun openSessionPage(context: Context): Boolean = launch(context, SESSION_URL)

    private fun launch(context: Context, url: String): Boolean {
        val viaTabs = runCatching {
            val tabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            tabs.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            tabs.launchUrl(context, Uri.parse(url))
            true
        }.getOrDefault(false)
        if (viaTabs) return true
        return runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /** 捕获结果：token 与账号显示名。 */
    data class Captured(val accessToken: String, val account: String)

    /**
     * 从剪贴板取凭证。整段 session JSON 时还能拿到邮箱/昵称，
     * 界面就能显示「登录成功：账号 xxx」。
     */
    fun capture(context: Context): Captured? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val text = manager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            ?: return null
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val token = obj.optString("accessToken").takeIf { it.isNotBlank() } ?: return null
            val user = obj.optJSONObject("user")
            val account = user?.optString("email").orEmpty().ifBlank {
                user?.optString("name").orEmpty()
            }
            return Captured(token, account)
        }
        return text.takeIf { it.startsWith("eyJ") && it.count { ch -> ch == '.' } >= 2 }
            ?.let { Captured(it, "") }
    }

    /** 剪贴板里既可能是整段 session JSON，也可能是裸的 accessToken。 */
    fun fromClipboard(context: Context): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val text = manager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            ?: return null
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text).optString("accessToken") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        return text.takeIf { it.startsWith("eyJ") && it.count { ch -> ch == '.' } >= 2 && it.length > 100 }
    }
}
