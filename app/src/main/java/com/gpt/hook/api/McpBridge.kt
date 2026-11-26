package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatGPT 消费端的 MCP 通道（/backend-api/ps/mcp）。
 *
 * 实测特性：
 *  - 标准 JSON-RPC 2.0，走 DPoP 认证
 *  - tools/list 返回 123 个工具（GitHub 91 个 + 其它）
 *  - tools/call 直接执行，延迟 200ms-2s
 *  - **不消耗每小时消息额度**（那个额度只算 conversation 生成）
 *
 * 所以这条路既能用，又不受限流影响。
 */
class McpBridge(private val cfg: RelayConfig) {

    private val client get() = ChatGptClient(cfg)

    private fun rpc(method: String, params: JSONObject?, timeoutMs: Int = 60_000): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", (System.currentTimeMillis() % 100000).toInt())
            .put("method", method)
            .put("params", params ?: JSONObject())
            .toString().toByteArray(Charsets.UTF_8)
        val conn = client.open("POST", "/backend-api/ps/mcp", body, "application/json")
        try {
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (text.isBlank()) error("MCP HTTP $code")
            val root = runCatching { JSONObject(text) }.getOrElse { error("MCP 返回非 JSON: ${text.take(120)}") }
            root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", it.toString())) }
            return root
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun listTools(): JSONArray {
        val root = rpc("tools/list", null)
        return root.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
    }

    fun listResources(): JSONArray {
        val root = rpc("resources/list", null)
        return root.optJSONObject("result")?.optJSONArray("resources") ?: JSONArray()
    }

    fun readResource(uri: String): String {
        val params = JSONObject().put("uri", uri)
        val root = rpc("resources/read", params)
        val contents = root.optJSONObject("result")?.optJSONArray("contents")
        val first = contents?.optJSONObject(0)
        return first?.optString("text").orEmpty()
    }

    /**
     * 调用一个工具。
     * @return 结果 JSON，含 structuredContent / content / isError
     */
    fun callTool(name: String, args: JSONObject): JSONObject {
        val params = JSONObject().put("name", name).put("arguments", args)
        val root = rpc("tools/call", params, 180_000)
        val result = root.optJSONObject("result") ?: JSONObject()
        // 服务端有时把错误塞在 _meta.error 里
        val metaError = result.optJSONObject("_meta")?.optJSONObject("error")
        if (metaError != null) {
            return JSONObject()
                .put("isError", true)
                .put("errorCode", metaError.optString("code"))
                .put("message", metaError.optString("message"))
        }
        return result
    }

    /**
     * 读网页：search_service.web_run 的 open 命令。
     * 实测可用，返回带行号的正文。
     */
    fun openUrl(url: String): String {
        val args = JSONObject().put("open", JSONArray().put(JSONObject().put("ref_id", url)))
        val res = callTool("search_service.web_run", args)
        val content = res.optJSONArray("content")
        val text = content?.optJSONObject(0)?.optString("text").orEmpty()
        if (text.isBlank() && res.optBoolean("isError")) {
            error(res.optString("message", "open failed"))
        }
        return text
    }
}
