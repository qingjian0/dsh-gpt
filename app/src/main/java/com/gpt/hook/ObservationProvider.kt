package com.gpt.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.gpt.hook.diagnostics.DiscoveryTask
import com.gpt.hook.diagnostics.DiscoveryTaskStatus
import com.gpt.hook.diagnostics.HookConfigParser
import org.json.JSONObject

/**
 * Narrow Binder IPC boundary.
 *
 * ChatGPT may publish bounded metadata observations, fetch its own declarative runtime
 * config, and claim/complete leased discovery jobs. The paired DSH host may manage rules
 * and jobs. The DSH bridge token is never accepted here or forwarded into the target app.
 */
class ObservationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        return when (method) {
            METHOD_PUBLISH -> {
                enforceTargetCaller(uid)
                val json = extras?.getString(KEY_REPORT).orEmpty()
                require(json.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) { "Report exceeds size limit" }
                val root = JSONObject(json)
                require(root.optInt("schemaVersion") == 1 &&
                    root.optString("kind") == "dsh-hook-observations" &&
                    root.optJSONArray("events") != null) { "Invalid observation report" }
                val now = System.currentTimeMillis()
                val saved = prefs.edit().putString(KEY_REPORT, json).putLong(KEY_UPDATED, now).commit()
                Bundle().apply { putBoolean(KEY_ACCEPTED, saved); putLong(KEY_UPDATED, now) }
            }
            METHOD_READ -> {
                enforceModuleCaller(uid)
                Bundle().apply {
                    putString(KEY_REPORT, prefs.getString(KEY_REPORT, EMPTY_REPORT))
                    putLong(KEY_UPDATED, prefs.getLong(KEY_UPDATED, 0L))
                }
            }
            METHOD_CLEAR -> {
                enforceModuleCaller(uid)
                prefs.edit().remove(KEY_REPORT).remove(KEY_UPDATED).commit()
                Bundle().apply { putBoolean(KEY_ACCEPTED, true) }
            }
            METHOD_HOST_SNAPSHOT -> {
                enforceDshHostCaller(uid)
                Bundle().apply {
                    putString(KEY_REPORT, prefs.getString(KEY_REPORT, EMPTY_REPORT))
                    putString(KEY_CONFIG, prefs.getString(KEY_CONFIG, EMPTY_CONFIG))
                    putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, true))
                    putLong(KEY_UPDATED, prefs.getLong(KEY_UPDATED, 0L))
                    putLong(KEY_CONFIG_UPDATED, prefs.getLong(KEY_CONFIG_UPDATED, 0L))
                    putString(KEY_DISCOVERY_TASK, prefs.getString(KEY_DISCOVERY_TASK, null))
                    putString(KEY_DISCOVERY_RESULT, prefs.getString(KEY_DISCOVERY_RESULT, null))
                }
            }
            METHOD_HOST_SET_CONFIG -> {
                enforceDshHostCaller(uid)
                val json = extras?.getString(KEY_CONFIG).orEmpty()
                require(json.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) { "Configuration exceeds size limit" }
                HookConfigParser.parse(json)
                val now = System.currentTimeMillis()
                val saved = prefs.edit().putString(KEY_CONFIG, json).putLong(KEY_CONFIG_UPDATED, now).commit()
                Bundle().apply { putBoolean(KEY_ACCEPTED, saved); putLong(KEY_CONFIG_UPDATED, now) }
            }
            METHOD_HOST_SET_ENABLED -> {
                enforceDshHostCaller(uid)
                val enabled = extras?.getBoolean(KEY_ENABLED)
                    ?: throw IllegalArgumentException("enabled is required")
                val saved = prefs.edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .putLong(KEY_CONFIG_UPDATED, System.currentTimeMillis())
                    .commit()
                Bundle().apply { putBoolean(KEY_ACCEPTED, saved) }
            }
            METHOD_TARGET_CONFIG -> {
                enforceTargetCaller(uid)
                Bundle().apply {
                    putString(KEY_CONFIG, prefs.getString(KEY_CONFIG, EMPTY_CONFIG))
                    putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, true))
                    putLong(KEY_CONFIG_UPDATED, prefs.getLong(KEY_CONFIG_UPDATED, 0L))
                }
            }
            METHOD_HOST_DISCOVER -> {
                enforceDshHostCaller(uid)
                val raw = extras?.getString(KEY_DISCOVERY_REQUEST).orEmpty()
                require(raw.toByteArray(Charsets.UTF_8).size <= MAX_DISCOVERY_REQUEST_BYTES) {
                    "Discovery request exceeds size limit"
                }
                val request = JSONObject(raw)
                val requestId = request.optString("requestId")
                require(requestId.matches(REQUEST_ID_PATTERN)) { "Invalid discovery request ID" }
                val prefix = request.optString("prefix", DEFAULT_PREFIX)
                require(prefix.startsWith(DEFAULT_PREFIX) && prefix.length <= 180) { "Invalid discovery prefix" }
                val className = request.optString("className", "")
                require(className.isEmpty() || (className.startsWith(DEFAULT_PREFIX) && className.length <= 240)) {
                    "Invalid discovery class name"
                }
                val limit = request.optInt("limit", 100)
                val cursor = request.optInt("cursor", 0)
                require(limit in 1..MAX_DISCOVERY_RECORDS && cursor >= 0) { "Invalid discovery page bounds" }

                val now = System.currentTimeMillis()
                val task = DiscoveryTask.queued(requestId, now)
                val payload = JSONObject()
                    .put("requestId", requestId)
                    .put("prefix", prefix)
                    .put("className", if (className.isEmpty()) JSONObject.NULL else className)
                    .put("limit", limit)
                    .put("cursor", cursor)
                    .toString()
                val saved = prefs.edit()
                    .putString(KEY_DISCOVERY_REQUEST, payload)
                    .putString(KEY_DISCOVERY_TASK, encodeTask(task))
                    .remove(KEY_DISCOVERY_RESULT)
                    .commit()
                Bundle().apply {
                    putBoolean(KEY_ACCEPTED, saved)
                    putString(KEY_DISCOVERY_TASK, encodeTask(task))
                }
            }
            METHOD_TARGET_DISCOVERY_POLL -> {
                enforceTargetCaller(uid)
                val current = prefs.getString(KEY_DISCOVERY_TASK, null)?.let(::decodeTask)
                    ?: return Bundle()
                val claimed = current.claim(System.currentTimeMillis(), DISCOVERY_LEASE_MS)
                if (claimed == current) return Bundle()
                prefs.edit().putString(KEY_DISCOVERY_TASK, encodeTask(claimed)).commit()
                if (claimed.status != DiscoveryTaskStatus.RUNNING) return Bundle().apply {
                    putString(KEY_DISCOVERY_TASK, encodeTask(claimed))
                }
                Bundle().apply {
                    putString(KEY_DISCOVERY_REQUEST, prefs.getString(KEY_DISCOVERY_REQUEST, null))
                    putString(KEY_DISCOVERY_TASK, encodeTask(claimed))
                    putLong(KEY_DISCOVERY_REVISION, claimed.revision)
                }
            }
            METHOD_TARGET_DISCOVERY_RESULT -> {
                enforceTargetCaller(uid)
                val requestId = extras?.getString(KEY_DISCOVERY_ID).orEmpty()
                val revision = extras?.getLong(KEY_DISCOVERY_REVISION, -1L) ?: -1L
                val resultRaw = extras?.getString(KEY_DISCOVERY_RESULT).orEmpty()
                require(resultRaw.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) {
                    "Discovery result exceeds size limit"
                }
                val result = JSONObject(resultRaw)
                val current = prefs.getString(KEY_DISCOVERY_TASK, null)?.let(::decodeTask)
                    ?: throw IllegalStateException("Discovery task is unavailable")
                require(current.requestId == requestId && current.revision == revision &&
                    current.status == DiscoveryTaskStatus.RUNNING) { "Stale discovery worker result" }
                val succeeded = result.optBoolean("ok", false)
                val error = result.optString("error", "discovery_failed")
                val retryable = !succeeded && error in RETRYABLE_DISCOVERY_ERRORS
                val finished = current.finish(
                    System.currentTimeMillis(),
                    succeeded,
                    if (succeeded) null else error,
                    retryable
                ) ?: throw IllegalStateException("Discovery lease expired before completion")
                val now = System.currentTimeMillis()
                val resultEnvelope = JSONObject()
                    .put("requestId", requestId)
                    .put("result", result)
                    .put("completedAt", now)
                    .toString()
                val saved = prefs.edit()
                    .putString(KEY_DISCOVERY_TASK, encodeTask(finished))
                    .putString(KEY_DISCOVERY_RESULT, resultEnvelope)
                    .commit()
                Bundle().apply {
                    putBoolean(KEY_ACCEPTED, saved)
                    putString(KEY_DISCOVERY_TASK, encodeTask(finished))
                }
            }
            METHOD_HOST_DISCOVERY_RESULT -> {
                enforceDshHostCaller(uid)
                val requestId = extras?.getString(KEY_DISCOVERY_ID).orEmpty()
                val task = prefs.getString(KEY_DISCOVERY_TASK, null)?.let(::decodeTask) ?: return Bundle()
                if (task.requestId != requestId) return Bundle()
                Bundle().apply {
                    putString(KEY_DISCOVERY_TASK, encodeTask(task))
                    putString(KEY_DISCOVERY_RESULT, prefs.getString(KEY_DISCOVERY_RESULT, null))
                }
            }
            METHOD_HOST_DISCOVERY_CANCEL -> {
                enforceDshHostCaller(uid)
                val requestId = extras?.getString(KEY_DISCOVERY_ID).orEmpty()
                val task = prefs.getString(KEY_DISCOVERY_TASK, null)?.let(::decodeTask)
                    ?: throw IllegalStateException("Discovery task is unavailable")
                if (task.requestId != requestId) throw IllegalStateException("Discovery task is no longer current")
                val cancelled = task.cancel(System.currentTimeMillis())
                val saved = prefs.edit().putString(KEY_DISCOVERY_TASK, encodeTask(cancelled)).commit()
                Bundle().apply { putBoolean(KEY_ACCEPTED, saved); putString(KEY_DISCOVERY_TASK, encodeTask(cancelled)) }
            }
            else -> throw IllegalArgumentException("Unknown provider method")
        }
    }

    private fun encodeTask(task: DiscoveryTask): String = JSONObject()
        .put("requestId", task.requestId)
        .put("status", task.status.name)
        .put("attempt", task.attempt)
        .put("revision", task.revision)
        .put("createdAtMs", task.createdAtMs)
        .put("updatedAtMs", task.updatedAtMs)
        .put("leaseUntilMs", task.leaseUntilMs)
        .put("expiresAtMs", task.expiresAtMs)
        .put("errorCode", task.errorCode ?: JSONObject.NULL)
        .toString()

    private fun decodeTask(raw: String): DiscoveryTask {
        val json = JSONObject(raw)
        return DiscoveryTask(
            requestId = json.getString("requestId"),
            status = DiscoveryTaskStatus.valueOf(json.getString("status")),
            attempt = json.getInt("attempt"),
            revision = json.getLong("revision"),
            createdAtMs = json.getLong("createdAtMs"),
            updatedAtMs = json.getLong("updatedAtMs"),
            leaseUntilMs = json.getLong("leaseUntilMs"),
            expiresAtMs = json.getLong("expiresAtMs"),
            errorCode = json.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun enforceTargetCaller(uid: Int) {
        val packages = requireNotNull(context).packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (TARGET_PACKAGE !in packages) throw SecurityException("Only the configured target app may call this method")
    }

    private fun enforceModuleCaller(uid: Int) {
        if (uid != requireNotNull(context).applicationInfo.uid) {
            throw SecurityException("Only the module app may read or clear observations")
        }
    }

    private fun enforceDshHostCaller(uid: Int) {
        val packages = requireNotNull(context).packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (DSH_HOST_PACKAGE !in packages) {
            throw SecurityException("Only the configured DSH host package may manage Hook controls")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Use call()")

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use call()")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use call()")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Use call()")

    companion object {
        const val AUTHORITY = "com.gpt.hook.observations"
        const val METHOD_PUBLISH = "publish"
        const val METHOD_READ = "read"
        const val METHOD_CLEAR = "clear"
        const val METHOD_HOST_SNAPSHOT = "host_snapshot"
        const val METHOD_HOST_SET_CONFIG = "host_set_config"
        const val METHOD_HOST_SET_ENABLED = "host_set_enabled"
        const val METHOD_TARGET_CONFIG = "target_config"
        const val METHOD_HOST_DISCOVER = "host_discover"
        const val METHOD_TARGET_DISCOVERY_POLL = "target_discovery_poll"
        const val METHOD_TARGET_DISCOVERY_RESULT = "target_discovery_result"
        const val METHOD_HOST_DISCOVERY_RESULT = "host_discovery_result"
        const val METHOD_HOST_DISCOVERY_CANCEL = "host_discovery_cancel"

        const val KEY_REPORT = "report"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_UPDATED = "updatedAt"
        const val KEY_CONFIG = "config"
        const val KEY_ENABLED = "enabled"
        const val KEY_CONFIG_UPDATED = "configUpdatedAt"
        const val KEY_DISCOVERY_REQUEST = "discoveryRequest"
        const val KEY_DISCOVERY_TASK = "discoveryTask"
        const val KEY_DISCOVERY_RESULT = "discoveryResult"
        const val KEY_DISCOVERY_ID = "discoveryId"
        const val KEY_DISCOVERY_REVISION = "discoveryRevision"

        private const val PREFS = "dsh-observations"
        private const val MAX_REPORT_BYTES = 128 * 1024
        private const val MAX_DISCOVERY_REQUEST_BYTES = 16 * 1024
        private const val MAX_DISCOVERY_RECORDS = 500
        private const val DISCOVERY_LEASE_MS = 30_000L
        private const val TARGET_PACKAGE = "com.openai.chatgpt"
        private const val DSH_HOST_PACKAGE = "app.dsh.mobile"
        private const val DEFAULT_PREFIX = "com.openai."
        private val REQUEST_ID_PATTERN = Regex("[A-Fa-f0-9-]{16,64}")
        private val RETRYABLE_DISCOVERY_ERRORS = setOf("class_index_unavailable", "discovery_failed")
        private const val EMPTY_REPORT =
            """{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}"""
        private const val EMPTY_CONFIG =
            """{"schemaVersion":1,"targetPackage":"com.openai.chatgpt","rules":[]}"""
    }
}
