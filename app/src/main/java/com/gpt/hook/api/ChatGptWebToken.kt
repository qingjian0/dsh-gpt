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

    const val SESSION_URL = "https://chatgpt.com/api/auth/session"

    fun openSessionPage(context: Context): Boolean {
        val viaTabs = runCatching {
            val tabs = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            tabs.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            tabs.launchUrl(context, Uri.parse(SESSION_URL))
            true
        }.getOrDefault(false)
        if (viaTabs) return true
        return runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(SESSION_URL))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
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
