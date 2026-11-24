package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.TimeZone

/** OpenAI /v1 协议 ↔ ChatGPT backend conversation 协议 转换。 */
object OpenAiCompat {

    /**
     * 实测通过的能力档位（2026-11-24）。
     * 每个别名对应一组 system_hints，客户端选模型即获得对应能力。
     * 未列出的档位（search / image_gen / computer_use / thinking_effort）在免费号上无效，不暴露。
     */
    val MODEL_PROFILES: Map<String, List<String>> = linkedMapOf(
        "gpt-6" to emptyList(),
        "gpt-6-think" to listOf("reason"),
        "gpt-6-deep" to listOf("deep_research"),
        "gpt-6-search" to listOf("reason", "web_search"),
        // 需要 RelayConfig.connectorId；为空时自动降级为 gpt-6-think 的行为
        "gpt-6-tools" to listOf("reason")
    )

    /** gpt-6-tools 的 hints 依赖已授权 connector id */
    fun toolHints(connectorId: String): List<String> =
        if (connectorId.isBlank()) listOf("reason")
        else listOf("reason", "plugin:connector_" + connectorId)
    val DEFAULT_MODELS = listOf("auto") + MODEL_PROFILES.keys.toList()

    /** 把客户端请求的 model 映射成上游 slug + hints */
    fun resolveProfile(model: String): Pair<String, List<String>> {
        val hints = MODEL_PROFILES[model]
        return if (hints != null) "auto" to hints else model to emptyList()
    }

    fun toConversation(
        openAi: JSONObject,
        upstreamConversationId: String? = null,
        upstreamParentMessageId: String? = null,
        connectorId: String = ""
    ): JSONObject {
        val messages = openAi.optJSONArray("messages") ?: JSONArray()
        val systemInstructions = ArrayList<String>()
        val transcript = ArrayList<String>()
        var lastUser = ""
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role", "user")
            val text = contentText(message)
            if (text.isEmpty()) continue
            when (role) {
                "system", "developer" -> systemInstructions += text
                "assistant" -> transcript += "Assistant: $text"
                else -> {
                    lastUser = text
                    transcript += "User: $text"
                }
            }
        }
        // function calling 模拟：把 tools 定义与工具结果并入 system 段
        if (ToolBridge.hasTools(openAi)) {
            systemInstructions += ToolBridge.instructions(openAi)
        }
        val toolResults = ToolBridge.toolResultsTranscript(openAi)
        val prompt = if (!upstreamConversationId.isNullOrBlank() || transcript.size <= 1) {
            (systemInstructions.map { "System: $it" } + toolResults + lastUser).joinToString("\n\n")
        } else {
            (systemInstructions.map { "System: $it" } + toolResults + transcript).joinToString("\n\n")
        }

        val rawModel = openAi.optString("model", "auto").ifBlank { "auto" }
        val (requestedModel, baseHints) = resolveProfile(rawModel)
        val profileHints = if (rawModel == "gpt-6-tools") toolHints(connectorId) else baseHints
        val body = JSONObject()
            .put("action", "next")
            .put("model", requestedModel)
            .put("conversation_mode", JSONObject().put("kind", "primary_assistant"))
            .put("parent_message_id", upstreamParentMessageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
            .put("timezone_offset_min", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
            .put("history_and_training_disabled", false)

        // On the free ChatGPT Android backend, the bare "gpt-6" slug can
        // return an empty response. The measured working path is the same
        // request with system_hints=["reason"], which resolves to the
        // available gpt-6-t-mini-mini reasoning model.
        val finalHints = JSONArray()
        for (h in profileHints) finalHints.put(h)
        val clientHints = openAi.optJSONArray("system_hints")
        if (clientHints != null) {
            for (i in 0 until clientHints.length()) {
                val v = clientHints.optString(i)
                var dup = false
                for (j in 0 until finalHints.length()) if (finalHints.optString(j) == v) dup = true
                if (!dup) finalHints.put(v)
            }
        }
        if (finalHints.length() > 0) body.put("system_hints", finalHints)

        if (!upstreamConversationId.isNullOrBlank()) body.put("conversation_id", upstreamConversationId)
        val messagesOut = JSONArray()
        // 实测：工具调用协议必须放在独立的 system 消息里，拼进 user 文本模型会拒绝。
        if (systemInstructions.isNotEmpty()) {
            messagesOut.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("author", JSONObject().put("role", "system"))
                    .put(
                        "content",
                        JSONObject().put("content_type", "text")
                            .put("parts", JSONArray().put(systemInstructions.joinToString("\n\n")))
                    )
            )
        }
        val userParts = (toolResults + if (upstreamConversationId.isNullOrBlank() && transcript.size > 1) transcript else listOf(lastUser))
        messagesOut.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("author", JSONObject().put("role", "user"))
                .put(
                    "content",
                    JSONObject().put("content_type", "text")
                        .put("parts", JSONArray().put(userParts.filter { it.isNotBlank() }.joinToString("\n\n")))
                )
        )
        body.put("messages", messagesOut)
        return body
    }

    fun lastUserText(openAi: JSONObject): String {
        val messages = openAi.optJSONArray("messages") ?: return ""
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role", "user") == "user") {
                return contentText(message)
            }
        }
        return ""
    }

    fun contentText(m: JSONObject): String {
        val c = m.opt("content") ?: return ""
        if (c is String) return c
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val part = c.opt(i)
                if (part is JSONObject) sb.append(part.optString("text", ""))
                else if (part is String) sb.append(part)
            }
            return sb.toString()
        }
        if (c is JSONObject) {
            val parts = c.optJSONArray("parts")
            if (parts != null) {
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.optString(i, ""))
                return sb.toString()
            }
        }
        return ""
    }

    fun modelsJson(upstreamIds: List<String>): String {
        val arr = JSONArray()
        upstreamIds.forEachIndexed { i, id ->
            arr.put(
                JSONObject().put("id", id).put("object", "model")
                    .put("created", 0).put("owned_by", "openai-session")
            )
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }


    fun responseObject(
        id: String,
        model: String,
        text: String,
        status: String = "completed",
        itemId: String = "msg_" + id.removePrefix("resp-")
    ): JSONObject {
        val part = JSONObject()
            .put("type", "output_text")
            .put("text", text)
            .put("annotations", JSONArray())
        val item = JSONObject()
            .put("id", itemId)
            .put("type", "message")
            .put("status", status)
            .put("role", "assistant")
            .put("content", JSONArray().put(part))
        return JSONObject()
            .put("id", id)
            .put("object", "response")
            .put("created_at", System.currentTimeMillis() / 1000)
            .put("status", status)
            .put("error", JSONObject.NULL)
            .put("incomplete_details", JSONObject.NULL)
            .put("model", model)
            .put("output", JSONArray().put(item))
            .put("output_text", text)
    }

    fun chunk(id: String, model: String, delta: String, finish: String? = null): String {
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", if (delta.isEmpty()) JSONObject() else JSONObject().put("content", delta))
            .put("finish_reason", finish ?: JSONObject.NULL)
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return obj.toString()
    }

    fun completion(id: String, model: String, text: String, promptTokens: Int = 0, toolCall: ToolBridge.ParsedCall? = null): String {
        val msg = JSONObject().put("role", "assistant")
        val choice = JSONObject().put("index", 0)
        if (toolCall != null) {
            msg.put("content", JSONObject.NULL).put("tool_calls", ToolBridge.toolCallsJson(toolCall))
            choice.put("message", msg).put("finish_reason", "tool_calls")
        } else {
            msg.put("content", text)
            choice.put("message", msg).put("finish_reason", "stop")
        }
        return JSONObject()
            .put("id", id).put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put(
                "usage",
                JSONObject().put("prompt_tokens", promptTokens)
                    .put("completion_tokens", text.length / 4).put("total_tokens", promptTokens + text.length / 4)
            )
            .toString()
    }
}
