package com.gpt.hook.api

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
    fun modelCatalogRejectsMissingOrMalformedVisibleEntries() {
        assertThrows(IllegalArgumentException::class.java) { http.parseModels("""{"data":[]}""") }
        assertThrows(IllegalArgumentException::class.java) {
            http.parseModels("""{"models":[{"visibility":"list","slug":"gpt-a"}]}""")
        }
    }
}
