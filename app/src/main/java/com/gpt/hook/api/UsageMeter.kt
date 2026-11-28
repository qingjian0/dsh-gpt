package com.gpt.hook.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Persistent, bounded gateway usage telemetry. TTFT is first assistant text delta. */
object UsageMeter {
    private const val PREFS = "dsh_usage"
    private const val HISTORY_KEY = "recent"
    private const val MAX_RECORDS = 200
    private val current = ThreadLocal<RequestState?>()

    private data class RequestState(
        val model: String,
        val channel: String,
        val startedAt: Long,
        val promptTokens: Int,
        var firstTokenAt: Long = 0L,
        var completionChars: Int = 0,
        var success: Boolean = false,
        var statusCode: Int = 0
    )

    fun begin(model: String, channel: String, promptChars: Int) {
        current.set(RequestState(model, channel, System.currentTimeMillis(), (promptChars / 4).coerceAtLeast(0)))
    }

    fun markFirstToken() {
        val state = current.get() ?: return
        if (state.firstTokenAt == 0L) state.firstTokenAt = System.currentTimeMillis()
    }

    fun markOutput(text: String) {
        if (text.isNotEmpty()) markFirstToken()
        current.get()?.let { it.completionChars += text.length }
    }

    fun markSuccess() { current.get()?.let { it.success = true; it.statusCode = 200 } }
    fun markFailure(statusCode: Int = 0) { current.get()?.let { it.statusCode = statusCode } }

    fun finish(context: Context) {
        val state = current.get() ?: return
        current.remove()
        val now = System.currentTimeMillis()
        val elapsed = (now - state.startedAt).coerceAtLeast(0L)
        val ttft = if (state.firstTokenAt == 0L) 0L else (state.firstTokenAt - state.startedAt).coerceAtLeast(0L)
        val completion = (state.completionChars / 4).coerceAtLeast(0)
        record(context, JSONObject()
            .put("model", state.model)
            .put("channel", state.channel)
            .put("ttft_ms", ttft)
            .put("total_ms", elapsed)
            .put("prompt_tokens", state.promptTokens)
            .put("completion_tokens", completion)
            .put("total_tokens", state.promptTokens + completion)
            .put("ok", state.success)
            .put("status_code", state.statusCode)
            .put("at", now))
    }

    private fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Synchronized
    private fun record(context: Context, sample: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = sample.optLong("at")
        val start = dayStart(now)
        val sameDay = prefs.getLong("day_start_ms", -1L) == start
        val history = JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]")
        history.put(sample)
        val trimmed = JSONArray()
        for (i in (history.length() - MAX_RECORDS).coerceAtLeast(0) until history.length()) {
            trimmed.put(history.getJSONObject(i))
        }
        val requests = if (sameDay) prefs.getLong("daily_requests", 0L) else 0L
        val ok = if (sameDay) prefs.getLong("daily_ok", 0L) else 0L
        val ttftCount = if (sameDay) prefs.getLong("daily_ttft_count", 0L) else 0L
        val editor = prefs.edit()
            .putLong("day_start_ms", start)
            .putLong("daily_requests", requests + 1L)
            .putLong("daily_ok", ok + if (sample.optBoolean("ok")) 1L else 0L)
            .putLong("daily_total_ms", (if (sameDay) prefs.getLong("daily_total_ms", 0L) else 0L) + sample.optLong("total_ms"))
            .putLong("daily_ttft_ms", (if (sameDay) prefs.getLong("daily_ttft_ms", 0L) else 0L) + sample.optLong("ttft_ms"))
            .putLong("daily_ttft_count", ttftCount + if (sample.optLong("ttft_ms") > 0L) 1L else 0L)
            .putLong("daily_prompt_tokens", (if (sameDay) prefs.getLong("daily_prompt_tokens", 0L) else 0L) + sample.optInt("prompt_tokens"))
            .putLong("daily_completion_tokens", (if (sameDay) prefs.getLong("daily_completion_tokens", 0L) else 0L) + sample.optInt("completion_tokens"))
            .putString(HISTORY_KEY, trimmed.toString())
        editor.apply()
    }

    @Synchronized
    fun snapshot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]")
        val start = dayStart(System.currentTimeMillis())
        val sameDay = prefs.getLong("day_start_ms", -1L) == start
        val today = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            .filter { it.optLong("at") >= start }
        val recent = JSONArray()
        for (item in today.takeLast(20)) recent.put(item)
        val requests = if (sameDay) prefs.getLong("daily_requests", 0L) else 0L
        val ok = if (sameDay) prefs.getLong("daily_ok", 0L) else 0L
        val ttftCount = if (sameDay) prefs.getLong("daily_ttft_count", 0L) else 0L
        val totalMs = if (sameDay) prefs.getLong("daily_total_ms", 0L) else 0L
        val ttftMs = if (sameDay) prefs.getLong("daily_ttft_ms", 0L) else 0L
        val prompt = if (sameDay) prefs.getLong("daily_prompt_tokens", 0L) else 0L
        val completion = if (sameDay) prefs.getLong("daily_completion_tokens", 0L) else 0L
        val last = today.lastOrNull()
        return JSONObject()
            .put("requests", requests)
            .put("ok", ok)
            .put("fail", requests - ok)
            .put("success_rate", if (requests == 0L) 0.0 else ok.toDouble() / requests)
            .put("avg_ms", if (requests == 0L) 0L else totalMs / requests)
            .put("avg_ttft_ms", if (ttftCount == 0L) 0L else ttftMs / ttftCount)
            .put("last_ms", last?.optLong("total_ms") ?: 0L)
            .put("last_ttft_ms", last?.optLong("ttft_ms") ?: 0L)
            .put("prompt_tokens", prompt)
            .put("completion_tokens", completion)
            .put("total_tokens", prompt + completion)
            .put("recent", recent)
    }
}
