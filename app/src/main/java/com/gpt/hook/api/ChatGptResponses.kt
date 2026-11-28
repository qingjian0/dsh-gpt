package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * OpenAI Chat Completions ⇄ OpenAI Responses 的双向转换（Codex 后端只认 Responses）。
 *
 * 网关对外契约是 OpenAI Chat，OAuth 通道上游是 Responses，两者消息形状、工具形状、
 * 流事件都不同。以下每条「不能带什么」都是上游硬约束：
 *
 * - `store` 必须 false，`include` 必须含 `reasoning.encrypted_content`；
 * - 不能带 `max_output_tokens` / `temperature` / `top_p`（会被 400）；
 * - system 提示要用 `developer` 角色放进 `input`，不能用 `instructions`；
 * - 工具必须显式 `strict: false`，否则 schema 里可选参数会被当成必填；
 * - 上游只支持流式，非流式在网关侧聚合。
 */
object ChatGptResponses {

    /** Chat Completions 请求体 → Codex `/responses` 请求体。 */
    fun toResponsesRequest(chatBody: JSONObject, model: String): JSONObject {
        val input = JSONArray()
        val messages = chatBody.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            appendMessage(msg, input)
        }
        val out = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("store", false)
            .put("input", input)
            .put("include", JSONArray().put("reasoning.encrypted_content"))

        val reasoning = JSONObject().put("summary", "auto")
        val effort = chatBody.optString("reasoning_effort").takeIf { it.isNotBlank() }
        // 快速模式不写 effort（调用方已保证），这里只在显式给出且非 empty 时透传
        if (effort != null && !effort.equals("none", ignoreCase = true)) reasoning.put("effort", effort)
        out.put("reasoning", reasoning)

        val tools = chatBody.optJSONArray("tools")
        if (tools != null) {
            val converted = JSONArray()
            for (i in 0 until tools.length()) convertTool(tools.optJSONObject(i), converted)
            if (converted.length() > 0) out.put("tools", converted)
        }
        chatBody.opt("tool_choice")?.let { out.put("tool_choice", convertToolChoice(it)) }
        chatBody.opt("parallel_tool_calls")?.let { out.put("parallel_tool_calls", it) }
        return out
    }

    private fun appendMessage(msg: JSONObject, input: JSONArray) {
        val role = msg.optString("role").lowercase()
        when (role) {
            "system", "developer" -> {
                val text = textOf(msg.opt("content")) ?: return
                input.put(JSONObject().put("role", "developer").put("content", text))
            }
            "user" -> input.put(userItem(msg))
            "assistant" -> {
                val text = textOf(msg.opt("content"))
                if (!text.isNullOrEmpty()) {
                    input.put(
                        JSONObject().put("type", "message").put("role", "assistant")
                            .put(
                                "content",
                                JSONArray().put(JSONObject().put("type", "output_text").put("text", text))
                            )
                    )
                }
                val calls = msg.optJSONArray("tool_calls") ?: return
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val fn = call.optJSONObject("function") ?: continue
                    input.put(
                        JSONObject().put("type", "function_call")
                            .put("call_id", call.optString("id"))
                            .put("name", fn.optString("name"))
                            .put("arguments", fn.optString("arguments", "{}"))
                    )
                }
            }
            // 工具结果：Chat 是 role=tool + tool_call_id，Responses 是 function_call_output
            "tool", "function" -> input.put(
                JSONObject().put("type", "function_call_output")
                    .put("call_id", msg.optString("tool_call_id").ifEmpty { msg.optString("name") })
                    .put("output", textOf(msg.opt("content")) ?: "")
            )
        }
    }

    private fun userItem(msg: JSONObject): JSONObject {
        val content = msg.opt("content")
        if (content == null || content == JSONObject.NULL || content is String) {
            return JSONObject().put("role", "user").put("content", textOf(content) ?: "")
        }
        val parts = JSONArray()
        if (content is JSONArray) {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text", "input_text" -> textOf(part.opt("text"))?.let {
                        parts.put(JSONObject().put("type", "input_text").put("text", it))
                    }
                    "image_url" -> imageUrlOf(part.opt("image_url"))?.let {
                        parts.put(JSONObject().put("type", "input_image").put("image_url", it))
                    }
                }
            }
        }
        return JSONObject().put("role", "user").apply {
            if (parts.length() > 0) put("content", parts) else put("content", textOf(content) ?: "")
        }
    }

    private fun imageUrlOf(element: Any?): String? = when (element) {
        null, JSONObject.NULL -> null
        is String -> element
        is JSONObject -> element.optString("url").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun textOf(element: Any?): String? {
        if (element == null || element == JSONObject.NULL) return null
        if (element is String) return element
        if (element !is JSONArray) return null
        val sb = StringBuilder()
        for (i in 0 until element.length()) {
            val part = element.optJSONObject(i) ?: continue
            val type = part.optString("type")
            if (type == "text" || type == "input_text" || type == "output_text") {
                sb.append(part.optString("text"))
            }
        }
        return sb.toString()
    }

    private fun convertTool(tool: JSONObject?, out: JSONArray) {
        if (tool == null) return
        if (!tool.optString("type").equals("function", ignoreCase = true)) return
        val fn = tool.optJSONObject("function") ?: return
        out.put(
            JSONObject().put("type", "function")
                .put("name", fn.optString("name"))
                .apply {
                    fn.optString("description").takeIf { it.isNotBlank() }?.let { put("description", it) }
                    fn.opt("parameters")?.let { put("parameters", it) }
                    // 必须显式 false：省略时上游按 strict=true 处理
                    put("strict", false)
                }
        )
    }

    private fun convertToolChoice(element: Any?): Any {
        val obj = element as? JSONObject ?: return element ?: JSONObject.NULL
        val fn = obj.optJSONObject("function") ?: return obj
        return JSONObject().put("type", "function").put("name", fn.optString("name"))
    }

    /**
     * Codex SSE → OpenAI chat.completion.chunk 逐行翻译。
     *
     * 事件类型优先取 data JSON 的 `type`，缺失时回退 `event:` 行（个别代理只透传一种）。
     */
    class SseTranslator(
        private val completionId: String,
        private val model: String,
        private val createdSeconds: Long = System.currentTimeMillis() / 1000,
        private val mode: String = "oauth",
    ) {
        private val toolIndexByOutput = mutableMapOf<Int, Int>()
        private var nextToolIndex = 0
        private var sawToolCall = false
        private var finished = false
        private var lastEvent: String? = null
        private var usage: JSONObject? = null

        fun translate(line: String): List<String> {
            if (line.startsWith("event:")) {
                lastEvent = line.removePrefix("event:").trim()
                return emptyList()
            }
            if (!line.startsWith("data:")) return emptyList()
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return emptyList()
            val event = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
            val type = event.optString("type").ifEmpty { lastEvent.orEmpty() }
            return when (type) {
                "response.created" -> listOf(chunk(JSONObject().put("role", "assistant")))
                "response.output_item.added" -> onItemAdded(event)
                "response.output_text.delta" -> {
                    val delta = event.optString("delta")
                    if (delta.isEmpty()) emptyList() else listOf(chunk(JSONObject().put("content", delta)))
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    val delta = event.optString("delta")
                    if (delta.isEmpty()) emptyList() else listOf(chunk(JSONObject().put("reasoning_content", delta)))
                }
                "response.function_call_arguments.delta" -> onArgumentsDelta(event)
                "response.completed", "response.incomplete" -> onTerminal(event)
                "response.failed", "error" -> onFailed(event)
                else -> emptyList()
            }
        }

        /** 流结束时补帧：上游没发 completed 也要让客户端收到结束块与 [DONE]。 */
        fun onFinish(): List<String> {
            if (finished) return emptyList()
            finished = true
            return listOf(chunk(JSONObject(), "stop", usage), DONE)
        }

        fun lastUsage(): JSONObject? = usage

        private fun onItemAdded(event: JSONObject): List<String> {
            val item = event.optJSONObject("item") ?: return emptyList()
            if (!item.optString("type").equals("function_call", ignoreCase = true)) return emptyList()
            val outputIndex = event.optInt("output_index", 0)
            val index = nextToolIndex++
            toolIndexByOutput[outputIndex] = index
            sawToolCall = true
            val callId = item.optString("call_id").ifEmpty { item.optString("id") }
            val toolCall = JSONObject().put("index", index).put("id", callId).put("type", "function")
                .put("function", JSONObject().put("name", item.optString("name")).put("arguments", ""))
            return listOf(chunk(JSONObject().put("tool_calls", JSONArray().put(toolCall))))
        }

        private fun onArgumentsDelta(event: JSONObject): List<String> {
            val outputIndex = event.optInt("output_index", 0)
            val index = toolIndexByOutput[outputIndex] ?: 0
            val delta = event.optString("delta")
            if (delta.isEmpty()) return emptyList()
            val toolCall = JSONObject().put("index", index)
                .put("function", JSONObject().put("arguments", delta))
            return listOf(chunk(JSONObject().put("tool_calls", JSONArray().put(toolCall))))
        }

        private fun onTerminal(event: JSONObject): List<String> {
            if (finished) return emptyList()
            finished = true
            val response = event.optJSONObject("response")
            usage = usageOf(response?.optJSONObject("usage"))
            val reason = when {
                sawToolCall -> "tool_calls"
                response?.optString("status") == "incomplete" -> {
                    when (response.optJSONObject("incomplete_details")?.optString("reason")) {
                        "max_output_tokens" -> "length"
                        "content_filter" -> "content_filter"
                        else -> "stop"
                    }
                }
                else -> "stop"
            }
            return listOf(chunk(JSONObject(), reason, usage), DONE)
        }

        private fun onFailed(event: JSONObject): List<String> {
            if (finished) return emptyList()
            finished = true
            val response = event.optJSONObject("response")
            val message = response?.optJSONObject("error")?.optString("message")
                ?: event.optJSONObject("error")?.optString("message")
                ?: event.optString("message")
                ?: "上游流式响应失败"
            // 流内错误没法用 HTTP 状态表达：把原因作为最后一段内容交给客户端并正常收尾
            return listOf(chunk(JSONObject().put("content", "[ChatGPT 上游错误] $message"), "stop"), DONE)
        }

        private fun usageOf(obj: JSONObject?): JSONObject? {
            if (obj == null) return null
            val prompt = obj.optLong("input_tokens", 0L)
            val completion = obj.optLong("output_tokens", 0L)
            return JSONObject()
                .put("prompt_tokens", prompt)
                .put("completion_tokens", completion)
                .put("total_tokens", obj.optLong("total_tokens", prompt + completion))
        }

        private fun chunk(delta: JSONObject, finishReason: String? = null, usage: JSONObject? = null): String {
            val choice = JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finishReason ?: JSONObject.NULL)
            val payload = JSONObject()
                .put("id", completionId)
                .put("object", "chat.completion.chunk")
                .put("created", createdSeconds)
                .put("model", model)
                .put("choices", JSONArray().put(choice))
            if (usage != null) payload.put("usage", usage)
            return "data: $payload\n\n"
        }

        private companion object {
            const val DONE = "data: [DONE]\n\n"
        }
    }

    /** 模型目录：只保留 `visibility = list` 的条目。 */
    fun parseModels(raw: String): List<String> {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val items = obj.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val visibility = m.optString("visibility")
            if (visibility.isNotEmpty() && !visibility.equals("list", ignoreCase = true)) continue
            val id = m.optString("slug").ifEmpty { m.optString("id") }
            if (id.isNotBlank()) out += id
        }
        return out
    }

    /**
     * 订阅用量 → 两个窗口（5 小时 / 周）。
     *
     * Codex 的用量是滚动窗口百分比而非剩余次数：把 `used_percent` 投影成 `limit=100` 的额度包。
     */
    fun parseUsage(raw: String): JSONObject {
        val out = JSONObject()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return out
        val rate = obj.optJSONObject("rate_limit") ?: obj
        packOf(rate.optJSONObject("primary_window"), "5 小时窗口", out)
        packOf(rate.optJSONObject("secondary_window"), "周窗口", out)
        return out
    }

    private fun packOf(window: JSONObject?, name: String, out: JSONObject) {
        if (window == null) return
        if (!window.has("used_percent")) return
        val used = window.optDouble("used_percent", 0.0).coerceIn(0.0, 100.0)
        out.put(
            name,
            JSONObject()
                .put("limit", 100)
                .put("used", used.toLong())
                .put("remain", (100.0 - used).toLong().coerceAtLeast(0))
                .put("reset_at", window.optLong("reset_at", 0L))
        )
    }

    fun newCompletionId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
}
