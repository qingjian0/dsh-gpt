package com.gpt.hook.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, opt-in request capture for the local relay. Never stores HTTP auth headers.
 * Request bodies are recursively redacted before persistence.
 */
internal object RequestDumpStore {
    private const val PREFS = "request_dump"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 50
    private const val MAX_BODY_CHARS = 12 * 1024
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).commit()
    }

    @Synchronized
    fun record(context: Context, method: String, target: String, contentType: String?, body: ByteArray?) {
        if (!enabled(context)) return
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { JSONArray(prefs.getString(KEY_RECORDS, "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            if (now - item.optLong("timestamp", 0L) <= RETENTION_MS) next.put(item)
        }
        val raw = body?.toString(Charsets.UTF_8).orEmpty()
        val sanitizedBody = RequestDumpSanitizer.sanitize(raw)
        val bodyTruncated = sanitizedBody.length > MAX_BODY_CHARS
        val safeBody = sanitizedBody.take(MAX_BODY_CHARS)
        val id = java.util.UUID.randomUUID().toString()
        next.put(JSONObject()
            .put("id", id)
            .put("timestamp", now)
            .put("method", method.take(12))
            .put("path", target.substringBefore("?").take(240))
            .put("contentType", contentType.orEmpty().take(100))
            .put("body", safeBody)
            .put("bodyTruncated", bodyTruncated))
        val bounded = JSONArray()
        val start = (next.length() - MAX_RECORDS).coerceAtLeast(0)
        for (i in start until next.length()) bounded.put(next.getJSONObject(i))
        prefs.edit().putString(KEY_RECORDS, bounded.toString()).commit()
    }

    fun snapshot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val source = runCatching { JSONArray(prefs.getString(KEY_RECORDS, "[]")) }.getOrElse { JSONArray() }
        val records = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            if (now - item.optLong("timestamp", 0L) <= RETENTION_MS) records.put(item)
        }
        if (records.length() != source.length()) prefs.edit().putString(KEY_RECORDS, records.toString()).apply()
        return JSONObject()
            .put("enabled", prefs.getBoolean(KEY_ENABLED, false))
            .put("count", records.length())
            .put("maxRecords", MAX_RECORDS)
            .put("retentionHours", 24)
            .put("records", records)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS).commit()
    }
}

/** Pure sanitizer kept separate so redaction behavior can be unit-tested. */
internal object RequestDumpSanitizer {
    private val sensitiveKey = Regex(
        "(?i)(authorization|proxy-authorization|cookie|set-cookie|token|secret|password|credential|session|api[_-]?key|client[_-]?secret|oai-did)"
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val tokenAssignment = Regex(
        "(?i)(access_token|refresh_token|session_token|api_key|authorization|cookie|set-cookie)\\s*([=:])\\s*([^&\\s,;\\\"]+)"
    )
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
    private val apiSecret = Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")

    fun sanitize(source: String): String {
        val json = runCatching { JSONObject(source) }.getOrNull()
        val safe = if (json != null) sanitizeObject(json).toString()
            else runCatching { JSONArray(source).let { sanitizeArray(it).toString() } }.getOrElse { sanitizeText(source) }
        return sanitizeText(safe)
    }

    private fun sanitizeObject(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            result.put(key, if (sensitiveKey.containsMatchIn(key)) "[REDACTED]" else sanitizeValue(value))
        }
        return result
    }

    private fun sanitizeArray(source: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until source.length()) result.put(sanitizeValue(source.opt(i)))
        return result
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> sanitizeObject(value)
        is JSONArray -> sanitizeArray(value)
        is String -> sanitizeText(value)
        else -> value
    }

    private fun sanitizeText(source: String): String =
        source.replace(bearer, "Bearer [REDACTED]")
            .replace(tokenAssignment) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
            .replace(jwt, "[REDACTED_JWT]")
            .replace(apiSecret, "[REDACTED_API_KEY]")
}
