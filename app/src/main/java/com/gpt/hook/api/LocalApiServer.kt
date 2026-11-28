package com.gpt.hook.api

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.gpt.hook.ObservationProvider
import com.gpt.hook.diagnostics.HookConfigParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 本地 OpenAI 兼容 API + backend-api 透传网关。
 * 无第三方依赖，手写 HTTP/1.1，SSE 用 Connection: close 定界。
 */
class LocalApiServer(private val context: Context, @Volatile var cfg: RelayConfig, private val conversations: ConversationRepository) {

    private var server: ServerSocket? = null
    // 固定 8 线程会被慢上游占满：一次上游卡住就吃到 readTimeout（分钟级），
    // 之后连 /health 都排不上队。改成可伸缩池并限制并发上限。
    private val pool = java.util.concurrent.Executors.newCachedThreadPool()
    private val inFlight = java.util.concurrent.Semaphore(64)
    private val conversationLocks = Array(64) { Any() }
    /**
     * 标准 OpenAI 客户端不会带 X-DSH-Conversation-Id，每轮都发全量 messages。
     * 这里用「会话起点的指纹」把多轮请求绑到同一个 localConversation，
     * 从而复用 upstreamConversationId，只向上游发增量。
     */
    private val signatureIndex = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** (send 解禁时间戳, 查询时间) —— 30 秒内复用，避免每个请求都探测 */
    @Volatile private var blockedCache: Pair<Long, Long>? = null
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
                val requestLine = readHttpLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]

                var contentLength = 0
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }
                if (contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
                    json(out, 413, err("request_too_large", "request body exceeds 1 MiB"))
                    return
                }
                if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                val body = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                        readChunkedBody(input)
                    contentLength > 0 -> {
                        val buf = ByteArray(contentLength)
                        var off = 0
                        while (off < contentLength) {
                            val n = input.read(buf, off, contentLength - off)
                            if (n < 0) throw java.io.EOFException("incomplete request body")
                            off += n
                        }
                        buf
                    }
                    else -> null
                }

                when {
                    method == "OPTIONS" -> corsPreflight(out)
                    target == "/health" -> json(out, 200, health())
                    cfg.apiKey.isBlank() -> json(out, 503, err("configuration_required", "configure a gateway API key before using protected API routes"))
                    !authorized(headers) -> json(out, 401, err("invalid_api_key", "missing or bad Authorization"))
                    target == "/v1/models" -> json(out, 200, OpenAiCompat.modelsJson(OpenAiCompat.DEFAULT_MODELS))
                    target == "/v1/capabilities" && method == "GET" -> json(out, 200, OpenAiCompat.capabilitiesJson())
                    // MCP 通道：不消耗每小时消息额度，可直接调工具/读网页
                    // Codex 通道：模型名 codex-* 时走 /backend-api/codex/responses
                    target == "/v1/codex/status" && method == "GET" -> json(out, 200,
                        JSONObject().put("configured", CodexBridge(cfg).available()).toString())
                    target == "/v1/mcp/tools" && method == "GET" -> mcpTools(out)
                    target == "/v1/mcp/resources" && method == "GET" -> mcpResources(out)
                    target == "/v1/mcp/resource" && method == "POST" -> mcpResourceRead(out, body)
                    target == "/v1/mcp/call" && method == "POST" -> mcpCall(out, body)
                    target == "/v1/web/open" && method == "POST" -> webOpen(out, body)
                    target == "/v1/hook/status" && method == "GET" -> hookStatus(out)
                    target == "/v1/hook/events" && method == "GET" -> hookEvents(out)
                    target == "/v1/hook/startup-discovery" && method == "GET" -> hookStartupDiscovery(out)
                    target == "/v1/hook/config" -> hookConfig(out, method, body)
                    target == "/v1/hook/enabled" && method == "PUT" -> hookEnabled(out, body)
                    target == "/v1/hook/discover" && method == "POST" -> hookDiscover(out, body)
                    target == "/v1/hook/inspect" && method == "POST" -> hookDiscover(out, body, requireExactClass = true)
                    target.startsWith("/v1/hook/discovery/") -> hookDiscovery(out, method, target)
                    target == "/v1/conversations" || target.startsWith("/v1/conversations/") ->
                        conversationRoute(out, method, target, body, headers)
                    target == "/v1/usage" && method == "GET" -> json(out, 200, UsageMeter.snapshot(context).toString())
                    method == "POST" && target == "/v1/chat/completions" ->
                        measureUsage(body ?: ByteArray(0)) { chat(out, body ?: ByteArray(0), headers) }
                    method == "POST" && target == "/v1/responses" ->
                        measureUsage(body ?: ByteArray(0)) { responses(out, body ?: ByteArray(0), headers) }
                    target.startsWith("/backend-api") -> passthrough(out, method, target, body, headers)
                    else -> json(out, 404, err("not_found", "route not found"))
                }
            } catch (t: Throwable) {
                try { socket.close() } catch (_: Throwable) { }
            }
        }
    }


    private fun measureUsage(body: ByteArray, action: () -> Unit) {
        val started = System.currentTimeMillis()
        val request = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        val model = request?.optString("model", "unknown")?.ifBlank { "unknown" } ?: "unknown"
        val channel = if (model.startsWith("codex-")) "codex" else "chatgpt"
        try {
            action()
        } finally {
            UsageMeter.record(context, model, channel, System.currentTimeMillis() - started)
        }
    }

    private fun providerCall(method: String, extras: Bundle? = null): Bundle? =
        context.contentResolver.call(HOOK_PROVIDER_URI, method, null, extras)

    private fun hookStatus(out: OutputStream) {
        try {
            val state = providerCall(ObservationProvider.METHOD_API_SNAPSHOT)
                ?: throw IllegalStateException("Hook control provider unavailable")
            val config = JSONObject(state.getString(ObservationProvider.KEY_CONFIG).orEmpty())
            val reportUpdatedAt = state.getLong(ObservationProvider.KEY_UPDATED, 0L)
            val report = JSONObject(state.getString(ObservationProvider.KEY_REPORT).orEmpty())
            val runtime = report.optJSONObject("runtime") ?: JSONObject()
            val installedHooks = runtime.optJSONArray("hooks") ?: org.json.JSONArray()
            val taskRaw = state.getString(ObservationProvider.KEY_DISCOVERY_TASK)
            val result = JSONObject()
                .put("ok", true)
                .put("controlPlane", "local-gpt-api")
                .put("targetPackage", "com.openai.chatgpt")
                .put("enabled", state.getBoolean(ObservationProvider.KEY_ENABLED, true))
                .put("configRevision", state.getLong(ObservationProvider.KEY_CONFIG_UPDATED, 0L))
                .put("configuredRuleCount", config.optJSONArray("rules")?.length() ?: 0)
                .put("targetReportUpdatedAtMs", reportUpdatedAt)
                .put("targetObserved", reportUpdatedAt > 0L)
                .put("runtimeState", runtime.optString("state", "unavailable"))
                .put("runtimeSnapshotAtMs", runtime.optLong("capturedAtMs", 0L))
                .put("activeHookCount", runtime.optInt("activeHookCount", 0))
                .put("hookInventoryTruncated", runtime.optBoolean("inventoryTruncated", false))
                .put("installedHooks", installedHooks)
                .put("classLoadWatcherEnabled", report.optBoolean("classLoadWatcherEnabled", false))
                .put("lastApplyReport", report.optJSONObject("lastApplyReport") ?: JSONObject()
                    .put("state", "unavailable"))
                .put("discoveryTask", taskRaw?.let { JSONObject(it) } ?: JSONObject.NULL)
            json(out, 200, result.toString())
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Hook status unavailable"))
        }
    }

    private fun hookStartupDiscovery(out: OutputStream) {
        try {
            val state = providerCall(ObservationProvider.METHOD_API_SNAPSHOT)
                ?: throw IllegalStateException("Hook control provider unavailable")
            val raw = state.getString(ObservationProvider.KEY_STARTUP_DISCOVERY)
            if (raw.isNullOrBlank()) {
                json(out, 404, err("not_found", "startup discovery has not completed"))
                return
            }
            json(out, 200, JSONObject()
                .put("ok", true)
                .put("updatedAtMs", state.getLong(ObservationProvider.KEY_STARTUP_DISCOVERY_UPDATED, 0L))
                .put("result", JSONObject(raw))
                .toString())
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Startup discovery unavailable"))
        }
    }

    private fun hookEvents(out: OutputStream) {
        try {
            val state = providerCall(ObservationProvider.METHOD_API_SNAPSHOT)
                ?: throw IllegalStateException("Hook control provider unavailable")
            val report = JSONObject(state.getString(ObservationProvider.KEY_REPORT).orEmpty())
            json(out, 200, JSONObject().put("ok", true).put("report", report).toString())
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Hook events unavailable"))
        }
    }

    private fun hookConfig(out: OutputStream, method: String, body: ByteArray?) {
        try {
            if (method == "GET") {
                val state = providerCall(ObservationProvider.METHOD_API_SNAPSHOT)
                    ?: throw IllegalStateException("Hook control provider unavailable")
                json(out, 200, JSONObject()
                    .put("ok", true)
                    .put("config", JSONObject(state.getString(ObservationProvider.KEY_CONFIG).orEmpty()))
                    .put("revision", state.getLong(ObservationProvider.KEY_CONFIG_UPDATED, 0L))
                    .toString())
                return
            }
            if (method != "PUT") {
                json(out, 405, err("method_not_allowed", "use GET or PUT"))
                return
            }
            val source = String(body ?: ByteArray(0), Charsets.UTF_8)
            HookConfigParser.parse(source)
            val extras = Bundle().apply { putString(ObservationProvider.KEY_CONFIG, source) }
            val result = providerCall(ObservationProvider.METHOD_API_SET_CONFIG, extras)
                ?: throw IllegalStateException("Hook config update was not acknowledged")
            if (!result.getBoolean(ObservationProvider.KEY_ACCEPTED)) {
                json(out, 500, err("storage_failed", "Hook config was not saved"))
                return
            }
            json(out, 200, JSONObject()
                .put("ok", true)
                .put("accepted", true)
                .put("revision", result.getLong(ObservationProvider.KEY_CONFIG_UPDATED, 0L))
                .put("note", "Accepted by the local control plane; target-process application must be verified separately.")
                .toString())
        } catch (e: org.json.JSONException) {
            json(out, 400, err("invalid_hook_config", e.message ?: "invalid Hook config"))
        } catch (e: IllegalArgumentException) {
            json(out, 400, err("invalid_hook_config", e.message ?: "invalid Hook config"))
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Hook config unavailable"))
        }
    }

    private fun hookEnabled(out: OutputStream, body: ByteArray?) {
        try {
            val request = JSONObject(String(body ?: ByteArray(0), Charsets.UTF_8))
            require(request.has("enabled")) { "enabled is required" }
            val extras = Bundle().apply {
                putBoolean(ObservationProvider.KEY_ENABLED, request.getBoolean("enabled"))
            }
            val result = providerCall(ObservationProvider.METHOD_API_SET_ENABLED, extras)
                ?: throw IllegalStateException("Hook enable state was not acknowledged")
            if (!result.getBoolean(ObservationProvider.KEY_ACCEPTED)) {
                json(out, 500, err("storage_failed", "Hook enable state was not saved"))
                return
            }
            json(out, 200, JSONObject()
                .put("ok", true)
                .put("accepted", true)
                .put("enabled", request.getBoolean("enabled"))
                .toString())
        } catch (e: org.json.JSONException) {
            json(out, 400, err("invalid_request", e.message ?: "invalid JSON body"))
        } catch (e: IllegalArgumentException) {
            json(out, 400, err("invalid_request", e.message ?: "invalid request"))
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Hook control unavailable"))
        }
    }

    private fun hookDiscover(out: OutputStream, body: ByteArray?, requireExactClass: Boolean = false) {
        try {
            val request = JSONObject(String(body ?: ByteArray(0), Charsets.UTF_8))
            val prefix = request.optString("prefix", "com.openai.")
            val className = request.optString("className").takeIf { it.isNotBlank() && it != "null" }
            val limit = request.optInt("limit", 100)
            val cursor = request.optInt("cursor", 0)
            require(!requireExactClass || className != null) { "className is required for exact inspection" }
            require(prefix.startsWith("com.openai.") && prefix.length <= 180) { "prefix must be within com.openai.*" }
            require(className == null || (className.startsWith("com.openai.") && className.length <= 240)) {
                "className must be within com.openai.*"
            }
            require(limit in 1..500 && cursor >= 0) { "invalid discovery page bounds" }
            val requestId = request.optString("requestId")
                .takeIf { it.matches(Regex("[A-Fa-f0-9-]{16,64}")) }
                ?: UUID.randomUUID().toString()
            val payload = JSONObject()
                .put("requestId", requestId)
                .put("prefix", prefix)
                .put("className", className ?: JSONObject.NULL)
                .put("limit", limit)
                .put("cursor", cursor)
                .toString()
            val extras = Bundle().apply {
                putString(ObservationProvider.KEY_DISCOVERY_REQUEST, payload)
            }
            val result = providerCall(ObservationProvider.METHOD_HOST_DISCOVER, extras)
                ?: throw IllegalStateException("Discovery request was not acknowledged")
            if (!result.getBoolean(ObservationProvider.KEY_ACCEPTED)) {
                json(out, 500, err("discovery_rejected", "Discovery request was not accepted"))
                return
            }
            json(out, 202, JSONObject()
                .put("ok", true)
                .put("requestId", requestId)
                .put("task", JSONObject(result.getString(ObservationProvider.KEY_DISCOVERY_TASK).orEmpty()))
                .toString())
        } catch (e: org.json.JSONException) {
            json(out, 400, err("invalid_discovery_request", e.message ?: "invalid JSON body"))
        } catch (e: IllegalArgumentException) {
            json(out, 400, err("invalid_discovery_request", e.message ?: "invalid discovery request"))
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Discovery unavailable"))
        }
    }

    private fun hookDiscovery(out: OutputStream, method: String, target: String) {
        val requestId = target.removePrefix("/v1/hook/discovery/").substringBefore("/")
        if (!requestId.matches(Regex("[A-Fa-f0-9-]{16,64}"))) {
            json(out, 400, err("invalid_request", "invalid discovery request ID"))
            return
        }
        try {
            val extras = Bundle().apply { putString(ObservationProvider.KEY_DISCOVERY_ID, requestId) }
            when (method) {
                "GET" -> {
                    val result = providerCall(ObservationProvider.METHOD_HOST_DISCOVERY_RESULT, extras)
                        ?: throw IllegalStateException("Discovery status unavailable")
                    val task = result.getString(ObservationProvider.KEY_DISCOVERY_TASK)
                    if (task == null) {
                        json(out, 404, err("not_found", "discovery request not found"))
                    } else {
                        val response = JSONObject().put("ok", true).put("task", JSONObject(task))
                        result.getString(ObservationProvider.KEY_DISCOVERY_RESULT)?.let {
                            response.put("result", JSONObject(it))
                        }
                        json(out, 200, response.toString())
                    }
                }
                "DELETE" -> {
                    val result = providerCall(ObservationProvider.METHOD_HOST_DISCOVERY_CANCEL, extras)
                        ?: throw IllegalStateException("Discovery cancellation unavailable")
                    json(out, 200, JSONObject()
                        .put("ok", true)
                        .put("accepted", result.getBoolean(ObservationProvider.KEY_ACCEPTED))
                        .put("task", result.getString(ObservationProvider.KEY_DISCOVERY_TASK)?.let { JSONObject(it) } ?: JSONObject.NULL)
                        .toString())
                }
                else -> json(out, 405, err("method_not_allowed", "use GET or DELETE"))
            }
        } catch (e: IllegalArgumentException) {
            json(out, 400, err("invalid_request", e.message ?: "invalid request"))
        } catch (t: Throwable) {
            json(out, 503, err("hook_control_unavailable", t.message ?: "Discovery control unavailable"))
        }
    }


    private fun conversationRoute(out: OutputStream, method: String, target: String, body: ByteArray?, headers: Map<String, String>) {
        if (cfg.apiKey.isBlank()) {
            json(out, 503, err("configuration_required", "configure a gateway API key before using conversation endpoints"))
            return
        }
        val caller = callerId(headers)
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


    /** Read one CRLF-delimited HTTP header line without buffering bytes from the body. */
    private fun readHttpLine(input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (value == 10) break
            if (value != 13) bytes.write(value)
        }
        if (bytes.size() > MAX_HEADER_LINE_BYTES) throw IllegalArgumentException("HTTP header line too long")
        return bytes.toString("ISO-8859-1")
    }

    /** Standard OpenAI clients only send Authorization; the DSH header remains optional. */
    private fun callerId(headers: Map<String, String>): String {
        val supplied = headers["x-dsh-client-id"]?.trim()
        if (!supplied.isNullOrBlank() && supplied.length <= 96 &&
            supplied.matches(Regex("[A-Za-z0-9._:-]+"))) return supplied
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cfg.apiKey.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        return "openai-$digest"
    }

    private fun corsPreflight(out: OutputStream) {
        writeHead(out, 204, "text/plain; charset=utf-8", 0L)
    }

    private fun readChunkedBody(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readHttpLine(input) ?: throw java.io.EOFException("missing chunk size")
            val size = sizeLine.substringBefore(";").trim().toLongOrNull(16)
                ?: throw IllegalArgumentException("invalid chunk size")
            if (size < 0 || size > MAX_REQUEST_BYTES || output.size().toLong() + size > MAX_REQUEST_BYTES) {
                throw IllegalArgumentException("request body exceeds 1 MiB")
            }
            if (size == 0L) {
                while (true) {
                    val trailer = readHttpLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = size.toInt()
            val buffer = ByteArray(minOf(8192, remaining))
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) throw java.io.EOFException("incomplete chunk")
                output.write(buffer, 0, count)
                remaining -= count
            }
            if (input.read() != 13 || input.read() != 10) {
                throw IllegalArgumentException("invalid chunk terminator")
            }
        }
        return output.toByteArray()
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val expected = cfg.apiKey
        if (expected.isBlank()) return false
        val value = headers["authorization"] ?: return false
        val prefix = "Bearer "
        if (!value.startsWith(prefix, ignoreCase = true)) return false
        val supplied = value.substring(prefix.length).trim()
        return supplied.isNotEmpty() && supplied == expected
    }

    private fun health(): String = JSONObject()
        .put("ok", true).put("port", cfg.port)
        .put("ready", cfg.ready).put("upstream", cfg.upstreamBase)
        .toString()

    private fun responses(out: OutputStream, body: ByteArray, headers: Map<String, String>) {
        val request = try { JSONObject(String(body, Charsets.UTF_8)) } catch (t: Throwable) {
            json(out, 400, err("invalid_request", "body is not JSON")); return
        }
        OpenAiCompat.unsupportedResponsesFeature(request)?.let {
            json(out, 400, err("unsupported_feature", it)); return
        }
        val messages = org.json.JSONArray()
        val instructions = request.optString("instructions").trim()
        if (instructions.isNotEmpty()) messages.put(JSONObject().put("role", "system").put("content", instructions))
        when (val input = request.opt("input")) {
            is String -> if (input.isNotBlank()) messages.put(JSONObject().put("role", "user").put("content", input))
            is org.json.JSONArray -> {
                val looseText = StringBuilder()
                for (i in 0 until input.length()) {
                    when (val item = input.opt(i)) {
                        is String -> if (item.isNotBlank()) messages.put(JSONObject().put("role", "user").put("content", item))
                        is JSONObject -> {
                            val content = responseInputText(item.opt("content") ?: item.opt("text"))
                            if (content.isNotBlank()) {
                                if (item.has("role") || item.optString("type") == "message") {
                                    messages.put(JSONObject().put("role", item.optString("role", "user")).put("content", content))
                                } else {
                                    if (looseText.isNotEmpty()) looseText.append("\n")
                                    looseText.append(content)
                                }
                            }
                        }
                    }
                }
                if (looseText.isNotEmpty()) messages.put(JSONObject().put("role", "user").put("content", looseText.toString()))
            }
            else -> {
                json(out, 400, err("invalid_request", "input must be a string or an array of messages")); return
            }
        }
        if (messages.length() == 0) {
            json(out, 400, err("invalid_request", "input must contain text")); return
        }
        val chatRequest = JSONObject()
            .put("model", request.optString("model", "auto"))
            .put("messages", messages)
            .put("stream", request.optBoolean("stream", false))
        chat(out, chatRequest.toString().toByteArray(Charsets.UTF_8), headers, responsesMode = true)
    }

    private fun responseInputText(value: Any?): String {
        if (value is String) return value
        if (value is org.json.JSONArray) {
            val result = StringBuilder()
            for (i in 0 until value.length()) {
                val part = value.opt(i)
                if (part is String) result.append(part)
                else if (part is JSONObject) result.append(part.optString("text"))
            }
            return result.toString()
        }
        if (value is JSONObject) return value.optString("text")
        return ""
    }

    private fun writeResponseEvent(out: OutputStream, type: String, payload: JSONObject) {
        payload.put("type", type)
        writeChunk(out, "event: $type\ndata: $payload\n\n")
    }

    private fun chat(
        out: OutputStream,
        body: ByteArray,
        headers: Map<String, String>,
        responsesMode: Boolean = false
    ) {
        val req = try { JSONObject(String(body, Charsets.UTF_8)) } catch (t: Throwable) {
            json(out, 400, err("invalid_request", "body is not JSON")); return
        }
        OpenAiCompat.unsupportedChatFeature(req)?.let {
            json(out, 400, err("unsupported_feature", it)); return
        }
        val caller = callerId(headers)
        val model = req.optString("model", "auto").ifBlank { "auto" }
        val userText = OpenAiCompat.lastUserText(req)
        if (userText.isBlank()) {
            json(out, 400, err("invalid_request", "messages must contain a non-empty user message")); return
        }
        val requestedConversationId = headers["x-dsh-conversation-id"]?.takeIf { it.isNotBlank() }
        // 隐式会话绑定：显式头 > 客户端 user 字段 > 首条 user 消息指纹
        val implicitSignature = run {
            val explicit = req.optString("user").takeIf { it.isNotBlank() }
            if (explicit != null) return@run "u:" + sha256(explicit)
            val firstUser = firstUserText(req)
            if (firstUser.isNullOrBlank()) null else "m:" + sha256(firstUser)
        }
        val localConversation = try {
            if (requestedConversationId != null) {
                conversations.get(caller, requestedConversationId)
                    ?: run { json(out, 404, err("not_found", "conversation not found")); return }
            } else {
                val boundId = implicitSignature?.let { signatureIndex[caller + "|" + it] }
                val bound = boundId?.let { runCatching { conversations.get(caller, it) }.getOrNull() }
                if (bound != null) bound
                else {
                    // DSH 每轮的 messages 结构不固定（有时只发当前条），签名无法命中。
                    // 兜底：同一 caller 在 CONTINUATION_WINDOW_MS 内复用最近一个会话，
                    // 保证「同一个对话接同一个对话」，不再每条消息都新建。
                    val now = System.currentTimeMillis()
                    val recent = runCatching { conversations.list(caller, 1).firstOrNull() }.getOrNull()
                    val reuse = recent?.takeIf { now - it.updatedAt < CONTINUATION_WINDOW_MS }
                    reuse ?: conversations.create(caller, userText.take(160), model).also { created ->
                        if (implicitSignature != null) signatureIndex[caller + "|" + implicitSignature] = created.id
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            json(out, 400, err("invalid_request", e.message ?: "invalid conversation identity")); return
        }
        val localId = localConversation.id
        val lock = conversationLocks[(localId.hashCode() and Int.MAX_VALUE) % conversationLocks.size]
        synchronized(lock) {
        val conversationForTurn = conversations.get(caller, localId) ?: run {
            json(out, 404, err("not_found", "conversation not found")); return
        }
        val responseHeaders = mapOf("X-DSH-Conversation-Id" to localId)
        try {
            conversations.appendMessage(
                caller, localId,
                JSONObject().put("role", "user").put("content", userText).put("created_at", System.currentTimeMillis())
            )
        } catch (e: IllegalArgumentException) {
            json(out, 413, err("conversation_limit", e.message ?: "conversation limit reached"), responseHeaders); return
        }

        val codexModel = req.optString("model").startsWith("codex-")
        if (codexModel) {
            codexChat(out, req, responseHeaders)
            return
        }

        // 先看账号是否被限流，被限时直接回带 Retry-After 的 429
        val blockedUntil = sendBlockedUntil()
        if (blockedUntil > System.currentTimeMillis()) {
            val waitSec = ((blockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            json(out, 429, JSONObject().put("error", JSONObject()
                .put("type", "rate_limited")
                .put("message", "ChatGPT 账号每小时消息数已用完，请 " + waitSec + " 秒后重试")
                .put("retry_after_seconds", waitSec)).toString(),
                responseHeaders + mapOf("Retry-After" to waitSec.toString()))
            return
        }

        val stream = req.optBoolean("stream", false)
        // Tool-call detection requires the complete assistant payload. Do not leak the
        // private <tool_call> envelope as content before deciding the OpenAI finish_reason.
        val deferredToolStream = stream && ToolBridge.hasTools(req) && !responsesMode
        val id = (if (responsesMode) "resp-" else "chatcmpl-") +
            UUID.randomUUID().toString().replace("-", "").take(24)
        val responseItemId = "msg_" + UUID.randomUUID().toString().replace("-", "").take(24)
        val client = ChatGptClient(cfg)
        val payload = OpenAiCompat.toConversation(
            req, conversationForTurn.upstreamConversationId, conversationForTurn.upstreamParentMessageId, cfg.connectorId, cfg.temporaryChat
        ).toString().toByteArray(Charsets.UTF_8)

        val conn = try {
            client.open("POST", "/backend-api/conversation", payload, "text/event-stream")
        } catch (t: Throwable) {
            json(out, 502, err("upstream_unreachable", t.message ?: "connect failed"), responseHeaders); return
        }
        val code = conn.responseCode
        if (code >= 400) {
            val text = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText().orEmpty()
            json(out, code, text.ifBlank { err("upstream_error", "HTTP $code") }, responseHeaders)
            try { conn.disconnect() } catch (_: Throwable) { }
            return
        }

        val textOut = StringBuilder()
        val upstreamState = UpstreamState()
        var sent = ""
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            if (stream) {
                if (!deferredToolStream) {
                    writeHead(out, 200, "text/event-stream; charset=utf-8", null, responseHeaders)
                }
                if (responsesMode) {
                    writeResponseEvent(out, "response.created", JSONObject()
                        .put("response", OpenAiCompat.responseObject(id, model, "", "in_progress", responseItemId)))
                    writeResponseEvent(out, "response.in_progress", JSONObject()
                        .put("response", OpenAiCompat.responseObject(id, model, "", "in_progress", responseItemId)))
                    writeResponseEvent(out, "response.output_item.added", JSONObject()
                        .put("output_index", 0)
                        .put("item", JSONObject().put("id", responseItemId).put("type", "message")
                            .put("status", "in_progress").put("role", "assistant").put("content", org.json.JSONArray())))
                    writeResponseEvent(out, "response.content_part.added", JSONObject()
                        .put("item_id", responseItemId).put("output_index", 0).put("content_index", 0)
                        .put("part", JSONObject().put("type", "output_text").put("text", "").put("annotations", org.json.JSONArray())))
                }
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l == "data: [DONE]") break
                    if (!l.startsWith("data:")) continue
                    val event = l.substring(5).trim()
                    captureUpstreamState(event, upstreamState)
                    val delta = extractDelta(event, sent) ?: continue
                    sent += delta
                    textOut.append(delta)
                    if (responsesMode) {
                        writeResponseEvent(out, "response.output_text.delta", JSONObject()
                            .put("item_id", responseItemId).put("output_index", 0)
                            .put("content_index", 0).put("delta", delta))
                    } else if (!deferredToolStream) {
                        writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, delta) + "\n\n")
                    }
                }
                if (responsesMode) {
                    val full = textOut.toString()
                    writeResponseEvent(out, "response.output_text.done", JSONObject()
                        .put("item_id", responseItemId).put("output_index", 0).put("content_index", 0).put("text", full))
                    writeResponseEvent(out, "response.content_part.done", JSONObject()
                        .put("item_id", responseItemId).put("output_index", 0).put("content_index", 0)
                        .put("part", JSONObject().put("type", "output_text").put("text", full).put("annotations", org.json.JSONArray())))
                    writeResponseEvent(out, "response.output_item.done", JSONObject()
                        .put("output_index", 0)
                        .put("item", JSONObject().put("id", responseItemId).put("type", "message")
                            .put("status", "completed").put("role", "assistant")
                            .put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text")
                                .put("text", full).put("annotations", org.json.JSONArray())))))
                    writeResponseEvent(out, "response.completed", JSONObject()
                        .put("response", OpenAiCompat.responseObject(id, model, full, itemId = responseItemId)))
                } else {
                    val full = textOut.toString()
                    val calls = if (ToolBridge.hasTools(req)) ToolBridge.parseAll(full) else emptyList()
                    val toolError = ToolBridge.validateCalls(req, calls, full)
                    if (toolError != null) {
                        json(out, 502, err("invalid_tool_call", toolError), responseHeaders)
                        return
                    }
                    if (calls.isNotEmpty()) {
                        if (deferredToolStream) {
                            writeHead(out, 200, "text/event-stream; charset=utf-8", null, responseHeaders)
                        }
                        // 工具调用：一次可带多个，DSH 侧并行执行
                        val deltas = org.json.JSONArray()
                        calls.forEachIndexed { i, c ->
                            deltas.put(JSONObject()
                                .put("index", i).put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name)
                                    .put("arguments", c.arguments)))
                        }
                        val frame = JSONObject()
                            .put("id", id).put("object", "chat.completion.chunk")
                            .put("created", System.currentTimeMillis() / 1000).put("model", model)
                            .put("choices", org.json.JSONArray().put(JSONObject().put("index", 0)
                                .put("delta", JSONObject().put("role", "assistant")
                                    .put("content", JSONObject.NULL)
                                    .put("tool_calls", deltas))
                                .put("finish_reason", "tool_calls")))
                        writeChunk(out, "data: " + frame + "\n\n")
                    } else {
                        if (deferredToolStream) {
                            writeHead(out, 200, "text/event-stream; charset=utf-8", null, responseHeaders)
                            if (textOut.isNotEmpty()) {
                                writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, textOut.toString()) + "\n\n")
                            }
                        }
                        writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, "", "stop") + "\n\n")
                    }
                    writeChunk(out, "data: [DONE]\n\n")
                }
                out.flush()
            } else {
                reader.forEachLine { l ->
                    if (l.startsWith("data:")) {
                        val event = l.substring(5).trim()
                        captureUpstreamState(event, upstreamState)
                        val delta = extractDelta(event, sent)
                        if (delta != null) { sent += delta; textOut.append(delta) }
                    }
                }
                val full = textOut.toString()
                val calls = if (ToolBridge.hasTools(req)) ToolBridge.parseAll(full) else emptyList()
                val toolError = ToolBridge.validateCalls(req, calls, full)
                if (toolError != null) {
                    json(out, 502, err("invalid_tool_call", toolError), responseHeaders)
                    return
                }
                json(out, 200,
                    if (responsesMode) OpenAiCompat.responseObject(id, model, full).toString()
                    else OpenAiCompat.completion(id, model, full, 0, calls),
                    responseHeaders)
            }
            // 上游 id 与消息一起提交，避免每个 SSE 事件都写一次库
            if (upstreamState.conversationId != null || upstreamState.parentMessageId != null) {
                runCatching {
                    conversations.updateUpstreamState(
                        caller, localId, upstreamState.conversationId, upstreamState.parentMessageId
                    )
                }
            }
            if (textOut.isNotEmpty()) {
                conversations.appendMessage(
                    caller, localId,
                    JSONObject().put("role", "assistant").put("content", textOut.toString())
                        .put("created_at", System.currentTimeMillis())
                )
            }
        } catch (t: Throwable) {
            try { socketSafeFlush(out) } catch (_: Throwable) { }
        } finally {
            // 必须 disconnect：实测不释放会让后续请求阻塞（上游 7.5s 变成 42.6s）。
            // keep-alive 的收益在这里不成立，连接必须显式归还。
            try { conn.disconnect() } catch (_: Throwable) { }
        }
        }
    }

    /**
     * 只解析 SSE 事件，把上游 id 记到 holder 里，不写库。
     *
     * 之前每个事件都写一次 SQLite：一次回答有几百个事件就是几百次写，
     * 实测把 1 秒的上游响应拖到 32 秒。现在由调用方在结束时统一提交一次。
     */
    private class UpstreamState {
        var conversationId: String? = null
        var parentMessageId: String? = null
    }

    private fun captureUpstreamState(payload: String, state: UpstreamState) {
        if (payload.isEmpty() || payload == "[DONE]") return
        val obj = try { JSONObject(payload) } catch (_: Throwable) { return }
        obj.optString("conversation_id").takeIf { it.isNotBlank() && it != "null" }
            ?.let { state.conversationId = it }
        val message = obj.optJSONObject("message")
        if (message?.optJSONObject("author")?.optString("role") == "assistant") {
            message.optString("id").takeIf { it.isNotBlank() && it != "null" }
                ?.let { state.parentMessageId = it }
        }
    }

    /**
     * 读取账号当前的限流状态。
     *
     * 实测 POST /backend-api/conversation/init 不消耗消息额度，
     * 返回 blocked_features[].resets_after 精确到秒。
     * "send" 被 block 时，生成请求必定 429，提前告诉客户端比让它干等更好。
     */
    /** 走 Codex 通道的对话，与 ChatGPT 消费端互不影响 */
    private fun codexChat(out: OutputStream, req: JSONObject, headers: Map<String, String>) {
        val bridge = CodexBridge(cfg)
        if (!bridge.available()) {
            json(out, 503, err("codex_not_configured",
                "Codex 通道需要单独 OAuth token，ChatGPT 的 token 无权限。在 API 页填写 codex token 后启用。"), headers)
            return
        }
        val id = "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        val model = req.optString("model", "codex-gpt-5.1").removePrefix("codex-")
        val stream = req.optBoolean("stream", false)
        val copy = JSONObject(req.toString()).put("model", model)
        runCatching {
            if (stream) {
                val input = bridge.chatStream(copy)
                writeHead(out, 200, "text/event-stream; charset=utf-8", null, headers)
                var acc = ""
                input.bufferedReader().forEachLine { line ->
                    if (!line.startsWith("data:")) return@forEachLine
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]" || payload.isEmpty()) return@forEachLine
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEachLine
                    if (obj.optString("type") == "response.output_text.delta") {
                        val d = obj.optString("delta")
                        if (d.isNotEmpty()) {
                            acc += d
                            writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, d) + "\n\n")
                        }
                    }
                }
                writeChunk(out, "data: " + OpenAiCompat.chunk(id, model, "", "stop") + "\n\n")
                writeChunk(out, "data: [DONE]\n\n")
                out.flush()
            } else {
                val resp = bridge.chat(copy)
                json(out, 200, bridge.toChatCompletion(resp, id, model).toString(), headers)
            }
        }.onFailure {
            json(out, 502, err("codex_error", it.message ?: "codex call failed"), headers)
        }
    }

    private fun mcpTools(out: OutputStream) {
        runCatching { McpBridge(cfg).listTools() }
            .onSuccess { json(out, 200, JSONObject().put("object", "list").put("data", it).toString()) }
            .onFailure { json(out, 502, err("mcp_error", it.message ?: "list failed")) }
    }

    private fun mcpResources(out: OutputStream) {
        runCatching { McpBridge(cfg).listResources() }
            .onSuccess { json(out, 200, JSONObject().put("object", "list").put("data", it).toString()) }
            .onFailure { json(out, 502, err("mcp_error", it.message ?: "list failed")) }
    }

    private fun mcpResourceRead(out: OutputStream, body: ByteArray?) {
        val req = runCatching { JSONObject(body?.toString(Charsets.UTF_8) ?: "") }.getOrNull()
        val uri = req?.optString("uri").orEmpty()
        if (uri.isBlank()) { json(out, 400, err("invalid_request", "uri required")); return }
        runCatching { McpBridge(cfg).readResource(uri) }
            .onSuccess { json(out, 200, JSONObject().put("uri", uri).put("content", it).toString()) }
            .onFailure { json(out, 502, err("mcp_error", it.message ?: "read failed")) }
    }

    private fun mcpCall(out: OutputStream, body: ByteArray?) {
        val req = runCatching { JSONObject(body?.toString(Charsets.UTF_8) ?: "") }.getOrNull()
        val name = req?.optString("name").orEmpty()
        if (name.isBlank()) { json(out, 400, err("invalid_request", "name required")); return }
        val args = req?.optJSONObject("arguments") ?: JSONObject()
        runCatching { McpBridge(cfg).callTool(name, args) }
            .onSuccess { json(out, 200, it.toString()) }
            .onFailure { json(out, 502, err("mcp_error", it.message ?: "call failed")) }
    }

    private fun webOpen(out: OutputStream, body: ByteArray?) {
        val req = runCatching { JSONObject(body?.toString(Charsets.UTF_8) ?: "") }.getOrNull()
        val url = req?.optString("url").orEmpty()
        if (url.isBlank()) { json(out, 400, err("invalid_request", "url required")); return }
        runCatching { McpBridge(cfg).openUrl(url) }
            .onSuccess { json(out, 200, JSONObject().put("url", url).put("text", it).toString()) }
            .onFailure { json(out, 502, err("mcp_error", it.message ?: "open failed")) }
    }

    private fun sendBlockedUntil(): Long {
        val now = System.currentTimeMillis()
        val cached = blockedCache
        if (cached != null && now - cached.second < 30_000L) return cached.first
        var until = 0L
        runCatching {
            val body = JSONObject().put("conversation_id", JSONObject.NULL)
                .put("gizmo_id", JSONObject.NULL).put("model", "auto")
                .toString().toByteArray(Charsets.UTF_8)
            val conn = ChatGptClient(cfg).open("POST", "/backend-api/conversation/init", body, "application/json")
            try {
                if (conn.responseCode == 200) {
                    val root = JSONObject(conn.inputStream.bufferedReader().readText())
                    val list = root.optJSONArray("blocked_features") ?: JSONArray()
                    for (i in 0 until list.length()) {
                        val f = list.optJSONObject(i) ?: continue
                        if (f.optString("name") == "send") {
                            val iso = f.optString("resets_after")
                            until = runCatching {
                                java.time.Instant.parse(iso).toEpochMilli()
                            }.getOrDefault(0L)
                        }
                    }
                }
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        blockedCache = until to now
        return until
    }

    private fun sha256(value: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** 取 messages 里第一条 user 文本，作为会话起点指纹的来源 */
    private fun firstUserText(req: JSONObject): String? {
        val messages = req.optJSONArray("messages") ?: return null
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val text = OpenAiCompat.contentText(m)
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun socketSafeFlush(out: OutputStream) {
        out.flush()
    }

    /** 上游每条 assistant 消息携带完整文本，取与已发送部分的增量。 */
    /**
     * 从 SSE 事件里取增量文本。
     *
     * 实测一次回答有几百个事件，之前对每个事件都做完整 JSONObject 解析、
     * parts.optString 复制整段累积文本、再用 sent += 拼接，整体 O(n²)，
     * 把上游 0.5 秒的响应拖到 5 秒。这里改成字符串扫描，命中才解析。
     */
    private fun extractDelta(payload: String, sent: String): String? {
        if (payload.length < 16) return null
        if (!payload.contains("assistant")) return null
        val marker = "\"parts\":[\""
        val idx = payload.indexOf(marker)
        if (idx < 0) return null
        var i = idx + marker.length
        val sb = StringBuilder()
        while (i < payload.length) {
            val c = payload[i]
            if (c == '"') break
            if (c == '\\' && i + 1 < payload.length) {
                val e = payload[i + 1]
                when (e) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        if (i + 5 < payload.length) {
                            val hex = payload.substring(i + 2, i + 6)
                            runCatching { sb.append(hex.toInt(16).toChar()) }
                            i += 4
                        }
                    }
                    else -> sb.append(e)
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        val full = sb.toString()
        if (full.length <= sent.length) return null
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

    private fun writeHead(
        out: OutputStream,
        code: Int,
        contentType: String,
        length: Long?,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val sb = StringBuilder()
        val reason = when (code) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Response"
        }
        sb.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Private-Network: true\r\n")
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, X-DSH-Client-Id, X-DSH-Conversation-Id\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
        sb.append("Access-Control-Max-Age: 86400\r\n")
        sb.append("Vary: Origin\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        extraHeaders.forEach { (name, value) ->
            if (name.matches(Regex("[A-Za-z0-9-]{1,64}")) && value.none { it == '\r' || it == '\n' }) {
                sb.append(name).append(": ").append(value).append("\r\n")
            }
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeChunk(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun json(
        out: OutputStream,
        code: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, code, "application/json; charset=utf-8", bytes.size.toLong(), extraHeaders)
        out.write(bytes)
        out.flush()
    }

    private fun err(type: String, message: String): String =
        JSONObject().put("error", JSONObject().put("type", type).put("message", message)).toString()

    companion object {
        private val HOOK_PROVIDER_URI: Uri = Uri.parse("content://com.gpt.hook.observations")
        // 客户端（DSH）每轮会重发完整历史；这里必须能收下，再由 conversation_id 增量转发给上游。
        // 上游本身限制约 1 MiB，但那是转发出去的 payload，与被接收的请求体不是一回事。
        /** 同一 caller 在此窗口内不新建会话，直接续用最近一个 */
        private const val CONTINUATION_WINDOW_MS = 30 * 60 * 1000L
        private const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
    }
}
