package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * 读取 ChatGPT 账号里的真实对话（不是本地网关会话）。
 *
 * 数据源：/backend-api/conversations 与 /backend-api/conversation/{id}
 * 认证沿用 ChatGptClient（DPoP token + oai-did），不经过任何 hook。
 */
class UpstreamConversations(private val cfg: RelayConfig) {

    data class Summary(val id: String, val title: String, val updatedAt: String)
    data class Turn(
        val role: String,
        val text: String,
        val model: String,
        val reasoningSec: Int,
        val hasTool: Boolean,
        val toolLabel: String
    )
    data class Detail(val id: String, val title: String, val model: String, val turns: List<Turn>)

    fun list(limit: Int = 20): List<Summary> {
        val body = get("/backend-api/conversations?offset=0&limit=$limit")
        val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Summary>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            out += Summary(
                id = o.optString("id"),
                title = o.optString("title").ifBlank { "(未命名)" },
                updatedAt = o.optString("update_time").take(19).replace("T", " ")
            )
        }
        return out
    }

    fun detail(id: String): Detail {
        val root = JSONObject(get("/backend-api/conversation/$id"))
        val mapping = root.optJSONObject("mapping") ?: JSONObject()
        val turns = ArrayList<Turn>()
        for (key in mapping.keys()) {
            val node = mapping.optJSONObject(key) ?: continue
            val msg = node.optJSONObject("message") ?: continue
            val role = msg.optJSONObject("author")?.optString("role") ?: continue
            if (role != "user" && role != "assistant") continue
            val parts = msg.optJSONObject("content")?.optJSONArray("parts") ?: continue
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.opt(i)
                if (p is String) sb.append(p)
                else if (p is JSONObject) sb.append("[").append(p.optString("content_type", "asset")).append("]")
            }
            val md = msg.optJSONObject("metadata") ?: JSONObject()
            turns += Turn(
                role = role,
                text = sb.toString(),
                model = md.optString("resolved_model_slug").ifBlank { md.optString("model_slug") },
                reasoningSec = md.optInt("finished_duration_sec", 0),
                hasTool = md.has("tool_invoking_message"),
                toolLabel = md.optString("tool_invoking_message")
            )
        }
        return Detail(
            id = id,
            title = root.optString("title").ifBlank { "(未命名)" },
            model = root.optString("default_model_slug"),
            turns = turns
        )
    }

    private fun get(path: String): String {
        val conn = ChatGptClient(cfg).open("GET", path, null, "application/json")
        try {
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code >= 400) error("HTTP $code ${text.take(120)}")
            return text
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
