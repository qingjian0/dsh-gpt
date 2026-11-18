package com.gpt.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONObject
import com.gpt.hook.diagnostics.HookConfigParser

/**
 * Narrow IPC endpoint.
 *
 * Target process may publish bounded metadata observations and fetch validated runtime
 * configuration. The paired DSH host may read snapshots and update declarative rules.
 * No DSH bridge token is accepted here or forwarded into the target process.
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
                require(json.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) {
                    "Report exceeds size limit"
                }
                val root = JSONObject(json)
                require(root.optInt("schemaVersion") == 1 &&
                    root.optString("kind") == "dsh-hook-observations" &&
                    root.optJSONArray("events") != null) { "Invalid observation report" }
                val now = System.currentTimeMillis()
                val saved = prefs.edit()
                    .putString(KEY_REPORT, json)
                    .putLong(KEY_UPDATED, now)
                    .commit()
                Bundle().apply {
                    putBoolean(KEY_ACCEPTED, saved)
                    putLong(KEY_UPDATED, now)
                }
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
                }
            }
            METHOD_HOST_SET_CONFIG -> {
                enforceDshHostCaller(uid)
                val json = extras?.getString(KEY_CONFIG).orEmpty()
                require(json.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) {
                    "Configuration exceeds size limit"
                }
                // Validate at the IPC boundary; the target process validates again before applying.
                HookConfigParser.parse(json)
                val now = System.currentTimeMillis()
                val saved = prefs.edit()
                    .putString(KEY_CONFIG, json)
                    .putLong(KEY_CONFIG_UPDATED, now)
                    .commit()
                Bundle().apply {
                    putBoolean(KEY_ACCEPTED, saved)
                    putLong(KEY_CONFIG_UPDATED, now)
                }
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
            else -> throw IllegalArgumentException("Unknown provider method")
        }
    }

    private fun enforceTargetCaller(uid: Int) {
        val ctx = requireNotNull(context)
        val packages = ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (TARGET_PACKAGE !in packages) {
            throw SecurityException("Only the configured target app may call this method")
        }
    }

    private fun enforceModuleCaller(uid: Int) {
        val ctx = requireNotNull(context)
        if (uid != ctx.applicationInfo.uid) {
            throw SecurityException("Only the module app may read or clear observations")
        }
    }

    private fun enforceDshHostCaller(uid: Int) {
        val ctx = requireNotNull(context)
        val packages = ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (DSH_HOST_PACKAGE !in packages) {
            throw SecurityException("Only the paired DSH host may access Hook controls")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Use call()")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use call()")

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use call()")

    companion object {
        const val AUTHORITY = "com.gpt.hook.observations"
        const val METHOD_PUBLISH = "publish"
        const val METHOD_READ = "read"
        const val METHOD_CLEAR = "clear"
        const val METHOD_HOST_SNAPSHOT = "host_snapshot"
        const val METHOD_HOST_SET_CONFIG = "host_set_config"
        const val METHOD_HOST_SET_ENABLED = "host_set_enabled"
        const val METHOD_TARGET_CONFIG = "target_config"
        const val KEY_REPORT = "report"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_UPDATED = "updatedAt"
        const val KEY_CONFIG = "config"
        const val KEY_ENABLED = "enabled"
        const val KEY_CONFIG_UPDATED = "configUpdatedAt"
        private const val PREFS = "dsh-observations"
        private const val MAX_REPORT_BYTES = 128 * 1024
        private const val TARGET_PACKAGE = "com.openai.chatgpt"
        private const val DSH_HOST_PACKAGE = "app.dsh.mobile"
        private const val EMPTY_REPORT =
            """{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}"""
        private const val EMPTY_CONFIG =
            """{"schemaVersion":1,"targetPackage":"com.openai.chatgpt","rules":[]}"""
    }
}
