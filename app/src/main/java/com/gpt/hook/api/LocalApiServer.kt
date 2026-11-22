package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 本地 OpenAI 兼容 API + backend-api 透传网关。
 * 无第三方依赖，手写 HTTP/1.1，SSE 用 Connection: close 定界。
 */
class LocalApiServer(@Volatile var cfg: RelayConfig) {
    private data class RelaySession(
        val conversationId: String,
        val parentMessageId: String,
        val updatedAt: Long
    )

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val sessions = ConcurrentHashMap<String, RelaySession>()
    private val sessionLocks = Array(64) { Any() }
    @Volatile private var running = false

    val port: Int get() = cfg.port

    fun start(): Boolean {
        if (running) return true
        return try {
            val addr = InetAddress.getByName(if (cfg.lan) "0.0.0.0" else "127.0.0.1")
            server = ServerSocket(cfg.port, 32, addr)
            running = true
            Thread({
                while (running) {
                    val s = try { server?.accept() } catch (t: Throwable) { null } ?: continue
                    pool.execute { handle(s) }
                }
            }, "dsh-local-api").apply { isDaemon = true }.start()
            true
        } catch (t: Throwable) {
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) { }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 180000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]

                var contentLength = 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }
                if (contentLength !in 0..(8 * 1024 * 1024)) {
                    json(out, 413, err("request_too_large", "body exceeds 8 MiB"))
                    return
                }
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val n = input.read(buf, off, contentLength - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off != contentLength) {
                        json(out, 400, err("invalid_request", "incomplete request body"))
                        return
                    }
                    buf
                } else null

                when {
                    target == "/health" -> json(out, 200, health())
                    !authorized(headers) -> json(out, 401, err("invalid_api_key", "missing or bad Authorization"))
                    target == "/v1/models" -> json(out, 200, OpenAiCompat.modelsJson(OpenAiCompat.DEFAULT_MODELS))
                    method == "GET" && target == "/v1/sessions" -> json(out, 200, sessionsJson())
                    method == "POST" && target.startsWith("/v1/chat/completions") ->
                        chat(out, body ?: ByteArray(0))
                    target.startsWith("/backend-api") -> passthrough(out, method, target, body, headers)
                    else -> json(out, 404, err("not_found", "route not found"))
                }
            } catch (t: Throwable) {
                try { socket.close() } catch (_: Throwable) { }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        if (cfg.apiKey.isBlank()) return true
        val h = headers["authorization"] ?: return false
        return h.removePrefix("Bearer ").trim() == cfg.apiKey
    }

    private fun health(): String = JSONObject()
        .put("ok", true).put("port", cfg.port)
        .put("ready", cfg.ready).put("upstream", cfg.upstreamBase)
        .toString()

    private fun sessionKey(req: JSONObject): String {
        val supplied = req.opt("user")
        val key = when (supplied) {
            is String -> supplied.trim()
            is JSONObject -> supplied.toString()
            else -> ""
        }
        return (key.ifBlank { "model:" + req.optString("model", "auto") }).take(256)
    }

    private fun chat(out: OutputStream, body: ByteArray) {
        val req = try { JSONObject(String(body, Charsets.UTF_8)) } catch (t: Throwable) {
            json(out, 400, err("invalid_request", "body is not JSON")); return
        }
        val key = sessionKey(req)
        val lock = sessionLocks[(key.hashCode() and Int.MAX_VALUE) % sessionLocks.size]
        synchronized(lock) { chatLocked(out, req, key) }
    }

    private fun chatLocked(out: OutputStream, req: JSONObject, key: String) {
        val now = System.currentTimeMillis()
        val previous = sessions[key]?.takeIf { now - it.updatedAt <= SESSION_TTL_MS }
        if (previous == null) sessions.remove(key)
        val stream = req.optBoolean("stream", false)
        val model = req.optString("model", "auto").ifBlank { "auto" }
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val payload = OpenAiCompat.toConversation(
            req, previous?.conversationId, previous?.parentMessageId
        ).toString().toByteArray(Charsets.UTF_8)
        val conn = try {
            ChatGptClient(cfg).open("POST", "/backend-api/conversation", payload, "text/event-stream")
        } catch (t: Throwable) {
            json(out, 502, err("upstream_unreachable", t.message ?: "connect failed")); return
        }
        val code = conn.responseCode
        if (code >= 400) {
            val text = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText().orEmpty()
            json(out, code, text.ifBlank { err("upstream_error", "HTTP $code") })
            conn.disconnect()
            return
        }

        val textOut = StringBuilder()
        var sent = ""
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            if (stream) {
                writeHead(out, 200, "text/event-stream; charset=utf-8", null)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l == "data: [DONE]") break
                    if (!l.startsWith("data:")) continue
                    val event = extractDelta(l.substring(5).trim(), sent, key)
                    if (event != null) {
                        sent += event
                        textOut.append(event)
                        writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, event) + "\n\n")
                    }
                }
                writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, "", "stop") + "\n\n")
                writeChunk(out, "data: [DONE]\n\n")
                out.flush()
            } else {
                reader.forEachLine { l ->
                    if (l.startsWith("data:")) {
                        val event = extractDelta(l.substring(5).trim(), sent, key)
                        if (event != null) { sent += event; textOut.append(event) }
                    }
                }
                json(out, 200, OpenAiCompat.completion(id, model, textOut.toString()))
            }
        } finally {
            runCatching { reader.close() }
            runCatching { conn.disconnect() }
            pruneSessions()
        }
    }

    /** Extract assistant text and persist backend conversation/message IDs for the next turn. */
    private fun extractDelta(payload: String, sent: String, key: String): String? {
        if (payload.isEmpty() || payload == "[DONE]") return null
        val obj = try { JSONObject(payload) } catch (t: Throwable) { return null }
        val msg = obj.optJSONObject("message") ?: return null
        val role = msg.optJSONObject("author")?.optString("role")
        if (role != "assistant") return null
        val conversationId = obj.optString("conversation_id")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: sessions[key]?.conversationId.orEmpty()
        val messageId = msg.optString("id").takeIf { it.isNotBlank() && it != "null" }
        if (conversationId.isNotBlank() && messageId != null) {
            sessions[key] = RelaySession(conversationId, messageId, System.currentTimeMillis())
        }
        val parts = msg.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val full = parts.optString(0, "")
        if (full.isEmpty() || full.length <= sent.length) return null
        return full.substring(sent.length)
    }

    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.updatedAt > SESSION_TTL_MS }
        if (sessions.size > MAX_SESSIONS) {
            sessions.entries.sortedBy { it.value.updatedAt }
                .take(sessions.size - MAX_SESSIONS)
                .forEach { sessions.remove(it.key, it.value) }
        }
    }

    private fun sessionsJson(): String {
        val rows = JSONArray()
        sessions.forEach { (key, value) ->
            rows.put(JSONObject()
                .put("user", key)
                .put("conversation_id", value.conversationId)
                .put("parent_message_id", value.parentMessageId)
                .put("updated_at", value.updatedAt))
        }
        return JSONObject().put("object", "list").put("data", rows).toString()
    }

    private fun passthrough(out: OutputStream, method: String, target: String, body: ByteArray?, headers: Map<String, String>) {
        val conn = try {
            ChatGptClient(cfg).open(method, target, body, headers["accept"] ?: "application/json")
        } catch (t: Throwable) {
            json(out, 502, err("upstream_unreachable", t.message ?: "connect failed")); return
        }
        val code = conn.responseCode
        val ct = conn.contentType ?: "application/json"
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        writeHead(out, code, ct, null)
        stream?.copyTo(out)
        out.flush()
        conn.disconnect()
    }

    private fun writeHead(out: OutputStream, code: Int, contentType: String, length: Long?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(" X\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeChunk(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun json(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, code, "application/json; charset=utf-8", bytes.size.toLong())
        out.write(bytes)
        out.flush()
    }

    private fun err(type: String, message: String): String =
        JSONObject().put("error", JSONObject().put("type", type).put("message", message)).toString()

    private companion object {
        const val SESSION_TTL_MS = 6L * 60 * 60 * 1000
        const val MAX_SESSIONS = 256
    }
}
