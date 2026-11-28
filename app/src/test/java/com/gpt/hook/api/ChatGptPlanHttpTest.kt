package com.gpt.hook.api

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatGptPlanHttpTest {
    private val http = ChatGptPlanHttp()

    @Test
    fun modelCatalogKeepsOnlyVisibleListedModelsAndPreservesOrder() {
        val raw = """{"models":[
          {"slug":"gpt-a","display_name":"GPT A","visibility":"list"},
          {"slug":"internal","display_name":"Internal","visibility":"hidden"},
          {"slug":"gpt-b","display_name":"GPT B","visibility":"list"}
        ]}"""
        assertEquals(
            listOf(ChatGptPlanHttp.Model("gpt-a", "GPT A"), ChatGptPlanHttp.Model("gpt-b", "GPT B")),
            http.parseModels(raw)
        )
    }


    @Test
    fun responsesStreamCollectsDeltasOnlyAfterCompletedEvent() {
        val events = """data: {"type":"response.output_text.delta","delta":"Hello"}

data: {"type":"response.output_text.delta","delta":", world!"}

data: {"type":"response.completed","response":{"id":"resp_test"}}

"""
        val deltas = ArrayList<String>()
        val result = http.consumeResponsesSse(BufferedReader(StringReader(events))) { deltas += it }
        assertEquals("Hello, world!", result)
        assertEquals(listOf("Hello", ", world!"), deltas)
    }

    @Test
    fun responsesStreamRejectsFailedAndInterruptedStreams() {
        val failed = """data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}

"""
        assertThrows(IllegalStateException::class.java) {
            http.consumeResponsesSse(BufferedReader(StringReader(failed)))
        }
        val incomplete = """data: {"type":"response.output_text.delta","delta":"partial"}

"""
        assertThrows(IllegalArgumentException::class.java) {
            http.consumeResponsesSse(BufferedReader(StringReader(incomplete)))
        }
    }

    @Test
    fun modelCatalogRejectsMissingOrMalformedVisibleEntries() {
        assertThrows(IllegalArgumentException::class.java) { http.parseModels("""{"data":[]}""") }
        assertThrows(IllegalArgumentException::class.java) {
            http.parseModels("""{"models":[{"visibility":"list","slug":"gpt-a"}]}""")
        }
    }
}
