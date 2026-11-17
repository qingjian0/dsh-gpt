package com.gpt.hook.diagnostics

import org.json.JSONObject

/**
 * Local AI bridge contract. Export observations for analysis and validate AI-produced
 * declarative rules before they reach HookRuntime. This class performs no network calls.
 */
class AiResearchBridge(private val events: EventBuffer) {
    fun exportObservations(): String = events.toJson()

    fun validateSuggestedConfig(json: String): JSONObject {
        val config = HookConfigParser.parse(json)
        return JSONObject()
            .put("accepted", true)
            .put("schemaVersion", config.schemaVersion)
            .put("targetPackage", config.targetPackage)
            .put("ruleCount", config.rules.size)
            .put("config", JSONObject(json))
    }

    fun rejection(reason: String): String = JSONObject()
        .put("accepted", false)
        .put("reason", reason.take(300))
        .toString()
}
