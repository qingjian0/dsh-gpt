package com.gpt.hook.api

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 直连 android.chat.openai.com 的最小客户端。
 * 实测只需要两个头：`Authorization: DPoP <token>` 与 `oai-did`。
 */
class ChatGptClient(private val cfg: RelayConfig) {

    fun open(method: String, path: String, body: ByteArray?, accept: String): HttpURLConnection {
        val base = cfg.upstreamBase.trimEnd('/')
        val url = URL(base + if (path.startsWith("/backend")) path else "/backend-api$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 300000
        conn.doInput = true
        conn.setRequestProperty("Authorization", "DPoP ${cfg.accessToken}")
        conn.setRequestProperty("oai-did", cfg.oaiDid)
        if (cfg.sessionId.isNotBlank()) conn.setRequestProperty("oai-session-id", cfg.sessionId)
        conn.setRequestProperty("User-Agent", cfg.userAgent)
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    fun stream(path: String, body: ByteArray, accept: String): InputStream {
        val conn = open("POST", path, body, accept)
        return if (conn.responseCode >= 400) (conn.errorStream ?: conn.inputStream) else conn.inputStream
    }
}
