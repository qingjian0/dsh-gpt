package com.gpt.hook.api

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 本地 OpenAI 兼容 API + backend-api 透传网关。
 * 无第三方依赖，手写 HTTP/1.1，SSE 用 Connection: close 定界。
 */
class LocalApiServer(@Volatile var cfg: RelayConfig, private val conversations: ConversationRepository) {

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)
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
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]

                var contentLength = 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val n = input.read(buf, off, contentLength - off)
                        if (n < 0) break
                        off += n
                    }
                    buf
                } else null

                when {
                    target == "/health" -> json(out, 200, health())
                    !authorized(headers) -> json(out, 401, err("invalid_api_key", "missing or bad Authorization"))
                    target == "/v1/models" -> json(out, 200, OpenAiCompat.modelsJson(OpenAiCompat.DEFAULT_MODELS))
                    target == "/v1/conversations" || target.startsWith("/v1/conversations/") ->
                        conversationRoute(out, method, target, body, headers)
                    method == "POST" && target.startsWith("/v1/chat/completions") ->
                        chat(out, body ?: ByteArray(0), headers)
                    target.startsWith("/backend-api") -> passthrough(out, method, target, body, headers)
                    else -> json(out, 404, err("not_found", "route not found"))
                }
            } catch (t: Throwable) {
                try { socket.close() } catch (_: Throwable) { }
            }
        }
    }


    private fun conversationRoute(out: OutputStream, method: String, target: String, body: ByteArray?, headers: Map<String, String>) {
        val caller = headers["x-dsh-client-id"]
        if (caller.isNullOrBlank()) { json(out, 400, err("invalid_request", "X-DSH-Client-Id is required")); return }
        try {
            val base = "/v1/conversations"
            if (target == base && method == "GET") {
                val arr = org.json.JSONArray()
                conversations.list(caller).forEach { arr.put(conversationJson(it, false)) }
                json(out, 200, JSONObject().put("object", "list").put("data", arr).toString()); return
            }
            if (target == base && method == "POST") {
                val req = JSONObject(String(body ?: ByteArray(0), Charsets.UTF_8))
                val item = conversations.create(caller, req.optString("title", "New conversation"), req.optString("model", "auto"), req.optString("parent_id").takeIf { it.isNotBlank() })
                json(out, 201, conversationJson(item).toString()); return
            }
            val parts = target.removePrefix(base).removePrefix("/").split("/")
            val id = parts.firstOrNull().orEmpty()
            if (id.isBlank() || id.length > 64) { json(out, 404, err("not_found", "conversation not found")); return }
            if (parts.size == 1 && method == "GET") {
                val item = conversations.get(caller, id)
                if (item == null) json(out, 404, err("not_found", "conversation not found")) else json(out, 200, conversationJson(item).toString())
                return
            }
            if (parts.size == 1 && method == "DELETE") {
                val deleted = conversations.delete(caller, id)
                if (deleted) json(out, 200, JSONObject().put("deleted", true).put("id", id).toString()) else json(out, 404, err("not_found", "conversation not found"))
                return
            }
            if (parts.size == 2 && parts[1] == "messages" && method == "POST") {
                val item = conversations.appendMessage(caller, id, JSONObject(String(body ?: ByteArray(0), Charsets.UTF_8)))
                if (item == null) json(out, 404, err("not_found", "conversation not found")) else json(out, 200, conversationJson(item).toString())
                return
            }
            json(out, 404, err("not_found", "route not found"))
        } catch (e: IllegalArgumentException) { json(out, 400, err("invalid_request", e.message ?: "invalid request")) }
          catch (t: Throwable) { json(out, 500, err("internal_error", "conversation operation failed")) }
    }

    private fun conversationJson(item: ConversationRepository.Conversation, includeMessages: Boolean = true): JSONObject {
        val obj = JSONObject().put("id", item.id).put("object", "conversation").put("title", item.title).put("model", item.model)
            .put("parent_id", item.parentId ?: JSONObject.NULL).put("created_at", item.createdAt).put("updated_at", item.updatedAt)
        if (includeMessages) obj.put("messages", item.messages)
        return obj
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

    private fun chat(out: OutputStream, body: ByteArray, headers: Map<String, String>) {
        val req = try { JSONObject(String(body, Charsets.UTF_8)) } catch (t: Throwable) {
            json(out, 400, err("invalid_request", "body is not JSON")); return
        }
        val stream = req.optBoolean("stream", false)
        val model = req.optString("model", "auto").ifBlank { "auto" }
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val client = ChatGptClient(cfg)
        val payload = OpenAiCompat.toConversation(req).toString().toByteArray(Charsets.UTF_8)

        val conn = try {
            client.open("POST", "/backend-api/conversation", payload, "text/event-stream")
        } catch (t: Throwable) {
            json(out, 502, err("upstream_unreachable", t.message ?: "connect failed")); return
        }
        val code = conn.responseCode
        if (code >= 400) {
            val text = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText().orEmpty()
            json(out, code, text.ifBlank { err("upstream_error", "HTTP $code") })
            return
        }

        val textOut = StringBuilder()
        var sent = ""
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))

        if (stream) {
            writeHead(out, 200, "text/event-stream; charset=utf-8", null)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l == "data: [DONE]") break
                if (!l.startsWith("data:")) continue
                val delta = extractDelta(l.substring(5).trim(), sent) ?: continue
                sent += delta
                textOut.append(delta)
                writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, delta) + "\n\n")
            }
            writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, "", "stop") + "\n\n")
            writeChunk(out, "data: [DONE]\n\n")
            out.flush()
        } else {
            reader.forEachLine { l ->
                if (l.startsWith("data:")) {
                    val d = extractDelta(l.substring(5).trim(), sent)
                    if (d != null) { sent += d; textOut.append(d) }
                }
            }
            json(out, 200, OpenAiCompat.completion(id, model, textOut.toString()))
        }
        try { conn.disconnect() } catch (_: Throwable) { }
    }

    /** 上游每条 assistant 消息携带完整文本，取与已发送部分的增量。 */
    private fun extractDelta(payload: String, sent: String): String? {
        if (payload.isEmpty() || payload == "[DONE]") return null
        val obj = try { JSONObject(payload) } catch (t: Throwable) { return null }
        val msg = obj.optJSONObject("message") ?: return null
        val role = msg.optJSONObject("author")?.optString("role")
        if (role != "assistant") return null
        val parts = msg.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val full = parts.optString(0, "")
        if (full.isEmpty() || full.length <= sent.length) return null
        return full.substring(sent.length)
    }

    private fun passthrough(out: OutputStream, method: String, target: String, body: ByteArray?, headers: Map<String, String>) {
        val client = ChatGptClient(cfg)
        val conn = try {
            client.open(method, target, body, headers["accept"] ?: "application/json")
        } catch (t: Throwable) {
            json(out, 502, err("upstream_unreachable", t.message ?: "connect failed")); return
        }
        val code = conn.responseCode
        val ct = conn.contentType ?: "application/json"
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        writeHead(out, code, ct, null)
        stream?.copyTo(out)
        out.flush()
        try { conn.disconnect() } catch (_: Throwable) { }
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
}
