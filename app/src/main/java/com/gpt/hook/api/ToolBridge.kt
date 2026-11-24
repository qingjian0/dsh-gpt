package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatGPT 消费端后端不支持 OpenAI function calling（`tools` 字段被静默忽略）。
 *
 * 这里用提示词模拟：把 tools 定义注入 system prompt，要求模型在需要时
 * 输出 `<tool_call>{json}</tool_call>`，网关解析后转成标准 tool_calls 返回。
 * 工具结果由客户端以 role="tool" 发回，网关转成文本继续对话。
 */
object ToolBridge {

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    data class ParsedCall(val name: String, val arguments: String, val id: String)

    fun hasTools(req: JSONObject): Boolean {
        val tools = req.optJSONArray("tools") ?: return false
        return tools.length() > 0
    }

    /** 把 tools 定义 + 输出协议拼成要注入的 system 文本 */
    fun instructions(req: JSONObject): String {
        val tools = req.optJSONArray("tools") ?: return ""
        val sb = StringBuilder()
        // 实测：TTFB 与请求体近似线性（每 1 KB 约 +700 ms），所以这里用最省的写法。
        sb.append("需要工具时只输出：").append(OPEN)
        sb.append("{\"name\":\"..\",\"arguments\":{}}").append(CLOSE).append("\n")
        sb.append("可用：\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name")
            if (name.isBlank()) continue
            sb.append(name)
            val desc = fn.optString("description").replace("\n", " ").trim()
            if (desc.isNotEmpty()) sb.append(": ").append(desc.take(40))
            val props = fn.optJSONObject("parameters")?.optJSONObject("properties")
            if (props != null && props.length() > 0) {
                val keys = props.keys()
                val names = ArrayList<String>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    names += k
                }
                sb.append(" (").append(names.joinToString(",")).append(")")
            }
            sb.append("\n")
        }
        sb.append("规则：整条回复只能是多个 ").append(OPEN)
            .append(" 块（可并行），不要加文字；不需要工具就直接回答。\n")
        return sb.toString()
    }

    /** 解析全部工具调用（模型可一次输出多个，减少往返轮次） */
    fun parseAll(text: String): List<ParsedCall> {
        val out = ArrayList<ParsedCall>()
        var from = 0
        while (true) {
            val start = text.indexOf(OPEN, from)
            if (start < 0) break
            val end = text.indexOf(CLOSE, start)
            if (end < 0) break
            val parsed = parseOne(text.substring(start + OPEN.length, end).trim())
            if (parsed != null) out += parsed
            from = end + CLOSE.length
        }
        return out
    }

    private fun parseOne(raw: String): ParsedCall? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val name = obj.optString("name")
        if (name.isBlank()) return null
        val args = obj.opt("arguments")
        val argsText = when (args) {
            null, JSONObject.NULL -> "{}"
            is JSONObject -> args.toString()
            is String -> args
            else -> args.toString()
        }
        return ParsedCall(name, argsText, "call_" + System.nanoTime().toString(36))
    }

    /** 从模型输出里解析工具调用；没有或格式非法时返回 null */
    fun parse(text: String): ParsedCall? {
        val start = text.indexOf(OPEN)
        if (start < 0) return null
        val end = text.indexOf(CLOSE, start)
        if (end < 0) return null
        val raw = text.substring(start + OPEN.length, end).trim()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val name = obj.optString("name")
        if (name.isBlank()) return null
        val args = obj.opt("arguments")
        val argsText = when (args) {
            null, JSONObject.NULL -> "{}"
            is JSONObject -> args.toString()
            is String -> args
            else -> args.toString()
        }
        return ParsedCall(name, argsText, "call_" + System.nanoTime().toString(36))
    }

    /** 把 role=tool 的历史消息压成模型可读的文本 */
    fun toolResultsTranscript(req: JSONObject): List<String> {
        val messages = req.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "tool") continue
            val name = m.optString("name").ifBlank { "tool" }
            out += "工具返回（$name）：" + OpenAiCompat.contentText(m).take(6000)
        }
        return out
    }

    fun toolCallsJson(calls: List<ParsedCall>): JSONArray {
        val arr = JSONArray()
        for (c in calls) arr.put(
            JSONObject().put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
        )
        return arr
    }

    /** OpenAI 标准 tool_calls 数组 */
    fun toolCallsJson(call: ParsedCall): JSONArray = JSONArray().put(
        JSONObject()
            .put("id", call.id)
            .put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
    )
}
