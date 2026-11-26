package com.gpt.hook.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolBridgeContractTest {
    private fun request(): JSONObject = JSONObject(
        """{"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"],"additionalProperties":false}}}]}"""
    )

    @Test
    fun validatesDeclaredToolAndArguments() {
        val raw = """<tool_call>{"name":"lookup","arguments":{"query":"status","limit":2}}</tool_call>"""
        val calls = ToolBridge.parseAll(raw)
        assertEquals(1, calls.size)
        assertNull(ToolBridge.validateCalls(request(), calls, raw))
    }

    @Test
    fun rejectsUndeclaredTool() {
        val raw = """<tool_call>{"name":"delete_everything","arguments":{}}</tool_call>"""
        val calls = ToolBridge.parseAll(raw)
        assertNotNull(ToolBridge.validateCalls(request(), calls, raw))
    }

    @Test
    fun rejectsMissingRequiredAndWrongTypes() {
        val missing = """<tool_call>{"name":"lookup","arguments":{"limit":2}}</tool_call>"""
        assertNotNull(ToolBridge.validateCalls(request(), ToolBridge.parseAll(missing), missing))

        val wrongType = """<tool_call>{"name":"lookup","arguments":{"query":"status","limit":"many"}}</tool_call>"""
        assertNotNull(ToolBridge.validateCalls(request(), ToolBridge.parseAll(wrongType), wrongType))
    }

    @Test
    fun rejectsAdditionalProperties() {
        val raw = """<tool_call>{"name":"lookup","arguments":{"query":"status","extra":true}}</tool_call>"""
        assertNotNull(ToolBridge.validateCalls(request(), ToolBridge.parseAll(raw), raw))
    }

    @Test
    fun rejectsMalformedToolEnvelope() {
        val raw = """<tool_call>{"name":"lookup","arguments":{"query":"status"}}"""
        assertNotNull(ToolBridge.validateCalls(request(), ToolBridge.parseAll(raw), raw))
    }

    @Test
    fun responsesRejectsToolFieldsInsteadOfDroppingThem() {
        assertNotNull(OpenAiCompat.unsupportedResponsesFeature(JSONObject("""{"input":"hello","tools":[]}""")))
        assertNotNull(OpenAiCompat.unsupportedResponsesFeature(JSONObject("""{"input":"hello","tool_choice":"auto"}""")))
        assertNull(OpenAiCompat.unsupportedResponsesFeature(JSONObject("""{"input":"hello"}""")))
    }
}
