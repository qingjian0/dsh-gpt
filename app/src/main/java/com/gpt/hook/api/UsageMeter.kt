package com.gpt.hook.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent request counter for the local gateway. */
object UsageMeter {
    private const val PREFS = "dsh_usage"
    private const val HISTORY_KEY = "recent"
    private const val MAX_RECORDS = 30

    @Synchronized
    fun record(context: Context, model: String, channel: String, elapsedMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]")
        history.put(JSONObject()
            .put("model", model)
            .put("channel", channel)
            .put("elapsed_ms", elapsedMs.coerceAtLeast(0))
            .put("at", System.currentTimeMillis()))
        val trimmed = JSONArray()
        for (i in (history.length() - MAX_RECORDS).coerceAtLeast(0) until history.length()) {
            trimmed.put(history.getJSONObject(i))
        }
        prefs.edit()
            .putLong("requests", prefs.getLong("requests", 0L) + 1L)
            .putLong("elapsed_total_ms", prefs.getLong("elapsed_total_ms", 0L) + elapsedMs.coerceAtLeast(0))
            .putString(HISTORY_KEY, trimmed.toString())
            .apply()
    }

    @Synchronized
    fun snapshot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]")
        return JSONObject()
            .put("requests", prefs.getLong("requests", 0L))
            .put("elapsed_total_ms", prefs.getLong("elapsed_total_ms", 0L))
            .put("recent", history)
    }
}
