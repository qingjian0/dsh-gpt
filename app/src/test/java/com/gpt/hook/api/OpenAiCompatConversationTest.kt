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
