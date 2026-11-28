package com.gpt.hook.api

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** ChatGPT upstream client: Android DPoP or web Bearer session. */
class ChatGptClient(private val cfg: RelayConfig) {

    fun open(method: String, path: String, body: ByteArray?, accept: String): HttpURLConnection {
        val web = cfg.proxyMode == "web"
        val base = (if (web) cfg.webBase else cfg.upstreamBase).trimEnd('/')
        val normalizedPath = when {
            path.startsWith("/backend-api") -> path
            path.startsWith("/") -> "/backend-api$path"
            else -> "/backend-api/$path"
        }
        val conn = URL(base + normalizedPath).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.doInput = true
        if (web) {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.webAccessToken}")
            if (cfg.webSessionToken.isNotBlank()) {
                conn.setRequestProperty("Cookie", "__Secure-next-auth.session-token=${cfg.webSessionToken}")
            }
            conn.setRequestProperty("User-Agent", cfg.webUserAgent)
        } else {
            conn.setRequestProperty("Authorization", "DPoP ${cfg.accessToken}")
            conn.setRequestProperty("oai-did", cfg.oaiDid)
            if (cfg.sessionId.isNotBlank()) conn.setRequestProperty("oai-session-id", cfg.sessionId)
            conn.setRequestProperty("User-Agent", cfg.userAgent)
        }
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
