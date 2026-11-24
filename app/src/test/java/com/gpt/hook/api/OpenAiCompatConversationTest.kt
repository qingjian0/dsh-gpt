package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val prompt = payload.getJSONArray("messages")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts").getString(0)

        assertTrue(prompt.contains("System: Be concise"))
        assertTrue(prompt.contains("User: first question"))
        assertTrue(prompt.contains("Assistant: first answer"))
        assertTrue(prompt.endsWith("User: follow-up"))
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

    @Test
    fun toolCallingIsRejectedInsteadOfSilentlyIgnored() {
        val request = JSONObject()
            .put("tools", JSONArray().put(JSONObject().put("type", "function")))
        assertTrue(OpenAiCompat.unsupportedChatFeature(request)?.contains("tool/function") == true)
    }

    @Test
    fun imagePartsAreRejectedByTheTextOnlyAdapter() {
        val request = JSONObject().put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", JSONArray().put(
                JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://example.invalid/image.png"))
            ))
        ))
        assertTrue(OpenAiCompat.unsupportedChatFeature(request)?.contains("multimodal") == true)
    }

    @Test
    fun jsonSchemaOutputIsRejectedInsteadOfClaimingConstrainedOutput() {
        val request = JSONObject().put("response_format", JSONObject().put("type", "json_schema"))
        assertTrue(OpenAiCompat.unsupportedChatFeature(request)?.contains("response_format") == true)
    }

    @Test
    fun plainTextChatRemainsSupported() {
        val request = JSONObject().put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", "hello")
        ))
        assertNull(OpenAiCompat.unsupportedChatFeature(request))
    }

    @Test
    fun capabilitiesAdvertiseOnlyTextAndImplementedRoutes() {
        val capabilities = JSONObject(OpenAiCompat.capabilitiesJson())
        assertTrue(capabilities.getBoolean("chat_completions"))
        assertTrue(capabilities.getBoolean("responses"))
        assertEquals("text", capabilities.getJSONArray("modalities").getString(0))
        assertTrue(capabilities.getJSONArray("unsupported").toString().contains("tool_calling"))
    }

}
