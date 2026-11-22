package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatSessionTest {
    @Test
    fun firstTurnCreatesNewConversationPayload() {
        val request = JSONObject()
            .put("model", "auto")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
        val payload = OpenAiCompat.toConversation(request)

        assertFalse(payload.has("conversation_id"))
        assertTrue(payload.optString("parent_message_id").isNotBlank())
        assertEquals("hello", payload.getJSONArray("messages").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getString(0))
    }

    @Test
    fun followUpReusesConversationAndAssistantParentMessage() {
        val request = JSONObject()
            .put("model", "auto")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "what was it?")))
        val payload = OpenAiCompat.toConversation(request, "conversation-1", "assistant-message-1")

        assertEquals("conversation-1", payload.getString("conversation_id"))
        assertEquals("assistant-message-1", payload.getString("parent_message_id"))
    }
}
