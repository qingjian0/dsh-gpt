package com.gpt.hook.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatConversationTest {

    @Test
    fun rejectsMultimodalChatInsteadOfFlatteningImagesToEmptyText() {
        val request = JSONObject().put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", JSONArray().put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "https://example.invalid/image.png"))
            ))
        ))
        assertTrue(OpenAiCompat.unsupportedChatFeature(request)?.contains("multimodal") == true)
    }

    @Test
    fun rejectsStructuredOutputAndUnsupportedToolSelection() {
        val schema = JSONObject().put("response_format", JSONObject().put("type", "json_schema"))
        val selection = JSONObject().put("tool_choice", "required")
        assertTrue(OpenAiCompat.unsupportedChatFeature(schema)?.contains("constrained") == true)
        assertTrue(OpenAiCompat.unsupportedChatFeature(selection)?.contains("tool_choice") == true)
    }

    @Test
    fun keepsToolBridgeAndPlainTextChatSupported() {
        val request = JSONObject()
            .put("tools", JSONArray().put(JSONObject().put("type", "function")))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
        assertEquals(null, OpenAiCompat.unsupportedChatFeature(request))
    }

    @Test
    fun rejectsMultimodalResponsesInput() {
        val request = JSONObject().put("input", JSONArray().put(
            JSONObject().put("type", "message").put("content", JSONArray().put(
                JSONObject().put("type", "input_image").put("image_url", "https://example.invalid/image.png")
            ))
        ))
        assertTrue(OpenAiCompat.unsupportedResponsesFeature(request)?.contains("multimodal") == true)
    }

    @Test
    fun capabilitiesAdvertiseOnlyVerifiedModalitiesAndToolBridge() {
        val capabilities = JSONObject(OpenAiCompat.capabilitiesJson())
        assertEquals("text", capabilities.getJSONArray("modalities").getString(0))
        assertTrue(capabilities.getBoolean("tool_calling"))
        assertEquals("ToolBridge text-protocol adapter", capabilities.getString("tool_calling_mode"))
    }

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
