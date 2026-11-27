package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codex 通道（/backend-api/codex/responses）。
 *
 * 与 ChatGPT 消费端的 conversation 接口是两条独立产品线：
 *  - 认证：需要 Codex 自己的 OAuth token（scope 里要有 codex 权限），
 *          ChatGPT App 的 token 会 401
 *  - 协议：标准 OpenAI Responses API
 *  - 能力：原生 tools / function calling，不需要 system_hints 模拟
 *
 * token 未配置时所有调用直接返回明确错误，不影响其他通道。
 */
class CodexBridge(private val cfg: RelayConfig) {

    fun available(): Boolean = cfg.codexToken.isNotBlank()

    private fun requireToken(): String {
        val t = cfg.codexToken.trim()
        if (t.isEmpty()) error("codex token not configured")
        return t
    }

    /**
     * 非流式：把 OpenAI Chat Completions 请求转成 Codex Responses 请求。
     */
    fun chat(openAi: JSONObject): JSONObject {
        val token = requireToken()
        val model = openAi.optString("model", "gpt-5.1-codex").ifBlank { "gpt-5.1-codex" }
        val input = toResponsesInput(openAi)
        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("stream", false)
        openAi.optJSONArray("tools")?.let { body.put("tools", normalizeTools(it)) }
        openAi.opt("temperature")?.let { body.put("temperature", it) }
        val conn = ChatGptClient(cfg).open("POST", "/backend-api/codex/responses",
            body.toString().toByteArray(Charsets.UTF_8), "application/json")
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.readTimeout = 180_000
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code >= 400) error("codex HTTP $code: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 流式：返回 SSE 输入流，由调用方转发 */
    fun chatStream(openAi: JSONObject): java.io.InputStream {
        val token = requireToken()
        val model = openAi.optString("model", "gpt-5.1-codex").ifBlank { "gpt-5.1-codex" }
        val body = JSONObject()
            .put("model", model)
            .put("input", toResponsesInput(openAi))
            .put("stream", true)
        openAi.optJSONArray("tools")?.let { body.put("tools", normalizeTools(it)) }
        val conn = ChatGptClient(cfg).open("POST", "/backend-api/codex/responses",
            body.toString().toByteArray(Charsets.UTF_8), "text/event-stream")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.readTimeout = 300_000
        val code = conn.responseCode
        if (code >= 400) {
            val t = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText().orEmpty()
            error("codex HTTP $code: ${t.take(200)}")
        }
        return conn.inputStream
    }

    /** Chat Completions 的 messages 转 Responses 的 input 数组 */
    private fun toResponsesInput(openAi: JSONObject): JSONArray {
        val out = JSONArray()
        val messages = openAi.optJSONArray("messages") ?: return out
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val text = OpenAiCompat.contentText(m)
            if (text.isEmpty()) continue
            val type = when (role) {
                "system", "developer" -> "input_text"
                "assistant" -> "output_text"
                else -> "input_text"
            }
            out.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", if (role == "system" || role == "developer") "system" else role)
                    .put("content", JSONArray().put(
                        JSONObject().put("type", type).put("text", text)
                    ))
            )
        }
        return out
    }

    /** Responses API 的 tool 是扁平结构，Chat Completions 是 {type,function:{...}} */
    private fun normalizeTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function")
            if (fn != null) {
                out.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", fn.optString("name"))
                        .put("description", fn.optString("description"))
                        .put("parameters", fn.optJSONObject("parameters") ?: JSONObject())
                )
            } else {
                out.put(t)
            }
        }
        return out
    }

    /** 把 Codex 的 Responses 结果转回 Chat Completions 形状 */
    fun toChatCompletion(resp: JSONObject, id: String, model: String): JSONObject {
        val sb = StringBuilder()
        val toolCalls = JSONArray()
        resp.optJSONArray("output")?.let { output ->
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "message" -> item.optJSONArray("content")?.let { c ->
                        for (j in 0 until c.length()) {
                            val part = c.optJSONObject(j) ?: continue
                            if (part.optString("type") == "output_text") sb.append(part.optString("text"))
                        }
                    }
                    "function_call" -> toolCalls.put(
                        JSONObject()
                            .put("id", item.optString("call_id", item.optString("id")))
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", item.optString("name"))
                                .put("arguments", item.optString("arguments", "{}")))
                    )
                }
            }
        }
        val msg = JSONObject().put("role", "assistant")
        val choice = JSONObject().put("index", 0)
        if (toolCalls.length() > 0) {
            msg.put("content", JSONObject.NULL).put("tool_calls", toolCalls)
            choice.put("message", msg).put("finish_reason", "tool_calls")
        } else {
            msg.put("content", sb.toString())
            choice.put("message", msg).put("finish_reason", "stop")
        }
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put("usage", JSONObject().put("prompt_tokens", 0)
                .put("completion_tokens", sb.length / 4)
                .put("total_tokens", sb.length / 4))
    }
}
