package com.gpt.hook.hardening

import com.gpt.hook.diagnostics.ModuleLogger
import io.github.libxposed.api.XposedInterface
import java.io.File

/**
 * 请求体抓取：hook org.json.JSONObject.toString()，
 * 只在结果里含 conversation_mode / system_hints / action":"next" 时落盘。
 *
 * 用途：ChatGPT Android 走 Cronet，Java 层抓不到 socket，
 * 但请求体是在 Java 层拼好的 JSONObject，这里能完整拿到。
 */
object RequestDump {

    private const val TAG = "dump"
    private const val MAX = 256 * 1024

    private var installed = false
    @Volatile private var sink: File? = null

    fun install(framework: XposedInterface, cl: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val ctx = currentContext()
            if (ctx != null) sink = File(ctx.filesDir, "request-dump.log")
        } catch (_: Throwable) {
        }
        try {
            val jo = Class.forName("org.json.JSONObject", false, cl)
            val m = jo.getDeclaredMethod("toString")
            framework.hook(m).setId("dsh.dump.json").intercept { chain ->
                val r = chain.proceed()
                if (r is String && isInteresting(r)) write(r)
                r
            }
            ModuleLogger.i("$TAG JSONObject.toString hooked")
        } catch (t: Throwable) {
            ModuleLogger.w("$TAG hook failed: ${t.javaClass.simpleName}")
        }
    }

    private fun isInteresting(s: String): Boolean {
        if (s.length > MAX) return false
        return (s.contains("\"conversation_mode\"") || s.contains("\"system_hints\"")) &&
            s.contains("\"messages\"")
    }

    private fun write(json: String) {
        try {
            ModuleLogger.i("$TAG CAPTURED " + json.take(4000))
            val f = sink ?: return
            f.appendText("\n=== " + System.currentTimeMillis() + " ===\n" + json + "\n")
        } catch (_: Throwable) {
        }
    }

    private fun currentContext(): android.content.Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null)
            app as? android.content.Context
        } catch (_: Throwable) {
            null
        }
    }
}
