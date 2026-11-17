package com.gpt.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Narrow IPC endpoint: only the configured target package UID may publish bounded,
 * metadata-only observation JSON. Only this module app UID may read the report.
 */
class ObservationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
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
                val saved = ctx.getSharedPreferences(PREFS, 0).edit()
                    .putString(KEY_REPORT, json)
                    .putLong(KEY_UPDATED, System.currentTimeMillis())
                    .commit()
                Bundle().apply {
                    putBoolean(KEY_ACCEPTED, saved)
                    putLong(KEY_UPDATED, System.currentTimeMillis())
                }
            }
            METHOD_READ -> {
                enforceModuleCaller(uid)
                val prefs = ctx.getSharedPreferences(PREFS, 0)
                Bundle().apply {
                    putString(KEY_REPORT, prefs.getString(KEY_REPORT, EMPTY_REPORT))
                    putLong(KEY_UPDATED, prefs.getLong(KEY_UPDATED, 0L))
                }
            }
            METHOD_CLEAR -> {
                enforceModuleCaller(uid)
                ctx.getSharedPreferences(PREFS, 0).edit()
                    .remove(KEY_REPORT).remove(KEY_UPDATED).commit()
                Bundle().apply { putBoolean(KEY_ACCEPTED, true) }
            }
            else -> throw IllegalArgumentException("Unknown provider method")
        }
    }

    private fun enforceTargetCaller(uid: Int) {
        val ctx = requireNotNull(context)
        val packages = ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (TARGET_PACKAGE !in packages) {
            throw SecurityException("Only the configured target app may publish observations")
        }
    }

    private fun enforceModuleCaller(uid: Int) {
        val ctx = requireNotNull(context)
        if (uid != ctx.applicationInfo.uid) {
            throw SecurityException("Only the module app may read or clear observations")
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
        const val KEY_REPORT = "report"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_UPDATED = "updatedAt"
        private const val PREFS = "dsh-observations"
        private const val MAX_REPORT_BYTES = 128 * 1024
        private const val TARGET_PACKAGE = "com.openai.chatgpt"
        private const val EMPTY_REPORT =
            """{"schemaVersion":1,"kind":"dsh-hook-observations","events":[]}"""
    }
}
