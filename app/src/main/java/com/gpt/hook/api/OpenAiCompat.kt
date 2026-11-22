package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** OpenAI /v1 协议 ↔ ChatGPT backend conversation 协议 转换。 */
object OpenAiCompat {

    val DEFAULT_MODELS = listOf("auto", "gpt-6", "gpt-6-mini", "gpt-5-6-mini", "research")

    fun toConversation(
        openAi: JSONObject,
        conversationId: String? = null,
        parentMessageId: String? = null
    ): JSONObject {
        val messages = openAi.optJSONArray("messages") ?: JSONArray()
        val parts = JSONArray()
        var lastUser = ""
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val text = contentText(m)
            if (text.isEmpty()) continue
            when (role) {
                "system" -> parts.put("System: $text")
                "assistant" -> parts.put("Assistant: $text")
                else -> lastUser = text
            }
        }
        if (parts.length() > 0 && lastUser.isNotEmpty()) lastUser = parts.join("\n") + "\n\n" + lastUser

        val body = JSONObject()
            .put("action", "next")
            .put("model", openAi.optString("model", "auto").ifBlank { "auto" })
            .put("conversation_mode", JSONObject().put("kind", "primary_assistant"))
            .put("parent_message_id", parentMessageId ?: UUID.randomUUID().toString())
            .put("timezone_offset_min", -480)
            .put("history_and_training_disabled", false)
        if (!conversationId.isNullOrBlank()) body.put("conversation_id", conversationId)

        val msg = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("author", JSONObject().put("role", "user"))
            .put(
                "content",
                JSONObject().put("content_type", "text").put("parts", JSONArray().put(lastUser))
            )
        body.put("messages", JSONArray().put(msg))
        return body
    }

    private fun contentText(m: JSONObject): String {
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
        upstreamIds.forEach { id ->
            arr.put(
                JSONObject().put("id", id).put("object", "model")
                    .put("created", 0).put("owned_by", "openai-session")
            )
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
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

    fun completion(id: String, model: String, text: String, promptTokens: Int = 0): String {
        val msg = JSONObject().put("role", "assistant").put("content", text)
        val choice = JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop")
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
