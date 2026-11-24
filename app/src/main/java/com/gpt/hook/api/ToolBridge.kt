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
        sb.append("你可以调用下列工具来完成任务。需要调用时，只输出一个 JSON 对象，包在 ")
        sb.append(OPEN).append(" 与 ").append(CLOSE).append(" 之间，不要输出其它内容：\n")
        sb.append(OPEN)
        sb.append("{\"name\":\"工具名\",\"arguments\":{...}}")
        sb.append(CLOSE).append("\n\n可用工具：\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name")
            if (name.isBlank()) continue
            sb.append("- ").append(name).append(": ")
            sb.append(fn.optString("description").replace("\n", " ").take(400))
            val params = fn.optJSONObject("parameters")
            if (params != null) sb.append("\n  参数 schema: ").append(params.toString().take(1200))
            sb.append("\n")
        }
        sb.append("\n规则：\n")
        sb.append("1. 需要工具时，整条回复只能是 ").append(OPEN).append("...").append(CLOSE).append("，前后不加文字。\n")
        sb.append("2. arguments 必须是合法 JSON 对象，键名与参数 schema 一致。\n")
        sb.append("3. 如果不需要工具，直接正常回答，不要输出 tool_call。\n")
        sb.append("4. 工具结果会以「工具返回：」前缀提供给你，据此继续。\n")
        return sb.toString()
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

    /** OpenAI 标准 tool_calls 数组 */
    fun toolCallsJson(call: ParsedCall): JSONArray = JSONArray().put(
        JSONObject()
            .put("id", call.id)
            .put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
    )
}
