package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatConversationTest {
    @Test
    fun continuationUsesStoredUpstreamConversationAndParentMessageIds() {
        val request = JSONObject()
            .put("model", "auto")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "first question"))
                    .put(JSONObject().put("role", "assistant").put("content", "first answer"))
                    .put(JSONObject().put("role", "user").put("content", "follow-up"))
            )

        val payload = OpenAiCompat.toConversation(request, "upstream-conv-1", "assistant-msg-2")

        assertEquals("upstream-conv-1", payload.getString("conversation_id"))
        assertEquals("assistant-msg-2", payload.getString("parent_message_id"))
        assertEquals("follow-up", payload.getJSONArray("messages")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts").getString(0))
        assertEquals("follow-up", OpenAiCompat.lastUserText(request))
    }

    @Test
    fun firstTurnCarriesPriorUserAndAssistantContext() {
        val request = JSONObject()
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "Be concise"))
                    .put(JSONObject().put("role", "user").put("content", "first question"))
                    .put(JSONObject().put("role", "assistant").put("content", "first answer"))
                    .put(JSONObject().put("role", "user").put("content", "follow-up"))
            )

        val payload = OpenAiCompat.toConversation(request)
        val messages = payload.getJSONArray("messages")
        // 工具协议/系统指令必须是独立的 system 消息，拼进 user 文本模型会拒绝
        val systemRole = messages.getJSONObject(0).getJSONObject("author").getString("role")
        val systemText = messages.getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getString(0)
        val userText = messages.getJSONObject(1).getJSONObject("content")
            .getJSONArray("parts").getString(0)

        assertEquals("system", systemRole)
        assertTrue(systemText.contains("Be concise"))
        assertTrue(userText.contains("User: first question"))
        assertTrue(userText.contains("Assistant: first answer"))
        assertTrue(userText.endsWith("follow-up"))
    }

    @Test
    fun firstTurnDoesNotInventAnUpstreamConversationId() {
        val request = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))

        val payload = OpenAiCompat.toConversation(request)

        assertFalse(payload.has("conversation_id"))
        assertTrue(payload.getString("parent_message_id").isNotBlank())
        assertEquals("hello", payload.getJSONArray("messages")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts").getString(0))
    }
}
