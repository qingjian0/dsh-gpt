package com.gpt.hook.diagnostics

import android.util.Log
import io.github.libxposed.api.XposedInterface

/** Centralized diagnostics logger. Never log credentials or request payloads. */
object ModuleLogger {
    private const val TAG = "GPTHook"
    @Volatile var framework: XposedInterface? = null

    fun i(message: String) {
        Log.i(TAG, message)
        framework?.log(Log.INFO, TAG, message, null)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        framework?.log(Log.WARN, TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        framework?.log(Log.ERROR, TAG, message, error)
    }
}
