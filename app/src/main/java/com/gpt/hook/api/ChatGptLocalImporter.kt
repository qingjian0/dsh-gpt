package com.gpt.hook.api

/**
 * 直接读取本机 ChatGPT App 已登录的会话凭证（DPoP）。
 *
 * 这是免费账号唯一"点一下就自动"的通路：
 * 凭证就是 ChatGPT App 自己正在用的那一份，对免费号完全有效。
 * 网页版的 cookie 由 Chrome 用 Android Keystore 加密，第三方取不到，
 * 因此网页通道无法做到自动。
 *
 * 本机是 root 环境，通过 `su -c cat` 读取；用户在 Magisk 里授权一次即可长期自动。
 */
object ChatGptLocalImporter {

    private const val AUTH_PREFS =
        "/data/data/com.openai.chatgpt/shared_prefs/com.auth0.authentication.storage.xml"
    private const val INSTALLATION = "/data/data/com.openai.chatgpt/files/INSTALLATION"

    data class Credentials(val accessToken: String, val deviceId: String)

    sealed interface Outcome {
        data class Ok(val credentials: Credentials) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun import(): Outcome {
        val xml = runCatching { readAsRoot(AUTH_PREFS) }.getOrNull()
            ?: return Outcome.Failed("读不到 ChatGPT 数据（请在 Magisk 里允许本应用使用 root）")
        val token = ACCESS_TOKEN.find(xml)?.groupValues?.getOrNull(1)?.trim()
        if (token.isNullOrBlank()) return Outcome.Failed("ChatGPT 尚未登录，请先打开 ChatGPT App 登录")
        val did = runCatching { readAsRoot(INSTALLATION) }.getOrNull()?.trim()
        if (did.isNullOrBlank()) return Outcome.Failed("读不到设备标识 INSTALLATION")
        return Outcome.Ok(Credentials(token, did))
    }

    private fun readAsRoot(path: String): String {
        val process = ProcessBuilder("su", "-c", "cat '$path'")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0 || text.isBlank()) throw IllegalStateException("su 读取失败: $path")
        return text
    }

    private val ACCESS_TOKEN = Regex("name=\"[^\"]*com\\.auth0\\.access_token\">([^<]+)<")
}
