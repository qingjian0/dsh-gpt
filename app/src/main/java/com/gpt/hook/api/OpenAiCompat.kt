package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.TimeZone

/** OpenAI /v1 协议 ↔ ChatGPT backend conversation 协议 转换。 */
object OpenAiCompat {

    val DEFAULT_MODELS = listOf("auto", "gpt-6", "gpt-6-mini", "gpt-5-6-mini", "research")

    fun toConversation(
        openAi: JSONObject,
        upstreamConversationId: String? = null,
        upstreamParentMessageId: String? = null
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
        val prompt = if (!upstreamConversationId.isNullOrBlank() || transcript.size <= 1) {
            (systemInstructions.map { "System: $it" } + lastUser).joinToString("\n\n")
        } else {
            (systemInstructions.map { "System: $it" } + transcript).joinToString("\n\n")
        }

        val body = JSONObject()
            .put("action", "next")
            .put("model", openAi.optString("model", "auto").ifBlank { "auto" })
            .put("conversation_mode", JSONObject().put("kind", "primary_assistant"))
            .put("parent_message_id", upstreamParentMessageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
            .put("timezone_offset_min", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
            .put("history_and_training_disabled", false)
        if (!upstreamConversationId.isNullOrBlank()) body.put("conversation_id", upstreamConversationId)
        val msg = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("author", JSONObject().put("role", "user"))
            .put(
                "content",
                JSONObject().put("content_type", "text").put("parts", JSONArray().put(prompt))
            )
        body.put("messages", JSONArray().put(msg))
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
