package com.gpt.hook.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Persistent, bounded gateway usage telemetry. TTFT is measured at first assistant text delta. */
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

    @Synchronized
    private fun record(context: Context, sample: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = JSONArray(prefs.getString(HISTORY_KEY, "[]") ?: "[]")
        history.put(sample)
        val trimmed = JSONArray()
        for (i in (history.length() - MAX_RECORDS).coerceAtLeast(0) until history.length()) {
            trimmed.put(history.getJSONObject(i))
        }
        prefs.edit().putString(HISTORY_KEY, trimmed.toString()).apply()
    }

    @Synchronized
    fun snapshot(context: Context): JSONObject {
        val history = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(HISTORY_KEY, "[]") ?: "[]")
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            .filter { it.optLong("at") >= todayStart }
        val recent = JSONArray()
        for (item in today.takeLast(20)) recent.put(item)
        val total = today.size
        val ok = today.count { it.optBoolean("ok") }
        fun average(key: String): Long {
            val values = today.map { it.optLong(key) }.filter { it > 0L }
            return if (values.isEmpty()) 0L else values.average().toLong()
        }
        val prompt = today.sumOf { it.optInt("prompt_tokens") }
        val completion = today.sumOf { it.optInt("completion_tokens") }
        val last = today.lastOrNull()
        return JSONObject()
            .put("requests", total)
            .put("ok", ok)
            .put("fail", total - ok)
            .put("success_rate", if (total == 0) 0.0 else ok.toDouble() / total)
            .put("avg_ms", average("total_ms"))
            .put("avg_ttft_ms", average("ttft_ms"))
            .put("last_ms", last?.optLong("total_ms") ?: 0L)
            .put("last_ttft_ms", last?.optLong("ttft_ms") ?: 0L)
            .put("prompt_tokens", prompt)
            .put("completion_tokens", completion)
            .put("total_tokens", prompt + completion)
            .put("recent", recent)
    }
}
