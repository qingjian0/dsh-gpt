package com.gpt.hook.api

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * 网页版凭证的获取：Chrome Custom Tabs 打开 chatgpt.com/api/auth/session，
 * 该页带着浏览器已登录会话，直接把 session JSON（含 accessToken）显示出来。
 * 用户全选复制后回到 App，这里自动从剪贴板解析，不用手敲。
 */
object ChatGptWebCapture {

    const val SESSION_URL = "https://chatgpt.com/api/auth/session"

    /** 用 Custom Tab 打开凭证页；拿不到 Custom Tab 时退回系统浏览器。 */
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

    /**
     * 从剪贴板解析 accessToken。支持两种形态：
     * 1. 完整的 `/api/auth/session` JSON：{"accessToken":"eyJ...","user":{...}}
     * 2. 直接复制的裸 token 字符串：eyJhbGciOi...
     */
    fun fromClipboard(context: Context): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val text = manager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            ?: return null
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val token = runCatching { JSONObject(text).optString("accessToken") }.getOrNull()
            return token?.takeIf { it.isNotBlank() }
        }
        // 裸 JWT：网页 accessToken 以 eyJ 开头，且包含两段点分
        return text.takeIf { it.startsWith("eyJ") && it.count { ch -> ch == '.' } >= 2 && it.length > 100 }
    }
}
