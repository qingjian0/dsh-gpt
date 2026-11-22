package com.gpt.hook

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.gpt.hook.diagnostics.EventBuffer
import com.gpt.hook.diagnostics.HookConfigParser
import com.gpt.hook.diagnostics.HookRuntime
import com.gpt.hook.diagnostics.ModuleLogger
import com.gpt.hook.diagnostics.RemoteConfigLoader
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** libxposed entry point for the DSH declarative diagnostics runtime. */
class MainModule : XposedModule() {
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-config-apply").apply { isDaemon = true }
    }
    private val reportExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-report-publish").apply { isDaemon = true }
    }
    private val reportQueued = AtomicBoolean(false)
    private val eventBuffer = EventBuffer(onChanged = ::scheduleReportPublish)
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var targetContextForIpc: Context? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        ModuleLogger.framework = this
        ModuleLogger.i("module loaded; process=${param.processName}; api=${getApiVersion()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        ModuleLogger.i("target package ready; package=${param.packageName}; classLoader=${param.classLoader.javaClass.name}")
        installContextBootstrap()

        val hookRuntime = HookRuntime(this, param.classLoader, eventBuffer)
        val prefs = getRemotePreferences("dsh")
        fun applyCurrentConfig() {
            val source = readLocalOverride()
                ?: prefs.getString("hook_config", null)
                ?: RemoteConfigLoader.read(this)
                ?: return
            configExecutor.execute {
                try {
                    val config = HookConfigParser.parse(source)
                    val (applied, failed) = hookRuntime.apply(config)
                    ModuleLogger.i("configuration applied; rules=${config.rules.size}; hooks=$applied; failed=$failed")
                    if (config.scanFields) scanLoadedClasses(param.classLoader)
                } catch (t: Throwable) {
                    ModuleLogger.w("configuration rejected; reason=${t.javaClass.simpleName}")
                }
            }
        }

        applyCurrentConfig()
        configListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        configListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "hook_config") applyCurrentConfig()
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    /**
     * 读目标进程自己 files 目录下的 hook-config.json。
     * libxposed 的 remote prefs 只在 lspd 启动时刷新，改配置后无法即时生效；
     * 这个文件由外部（root/adb）写入，进程重启即读，绕开该缓存。
     */
    private fun readLocalOverride(): String? {
        return try {
            val raw = java.io.File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8)
            val cmd = raw.substringBefore('\u0000').substringBefore(':')
            val f = java.io.File("/data/data/" + cmd + "/files/hook-config.json")
            if (!f.exists()) return null
            val text = f.readText()
            if (text.length > 128 * 1024) null else text
        } catch (t: Throwable) {
            null
        }
    }

    /** Capture the app's base Context at the public Application-onCreate instrumentation boundary. */
    private fun installContextBootstrap() {
        try {
            val callback = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", Application::class.java
            )
            this.hook(callback).setId("dsh.context.bootstrap").intercept { chain: XposedInterface.Chain ->
                val application = chain.getArg(0) as? Application
                val result = chain.proceed()
                if (targetContextForIpc == null && application != null) {
                    // Use the target app's own Context so Binder calls carry the target UID.
                    targetContextForIpc = application.baseContext?.applicationContext
                        ?: application.baseContext
                }
                result
            }
        } catch (t: Throwable) {
            ModuleLogger.w("context bootstrap hook failed: ${t.javaClass.simpleName}")
        }
    }

    /** 遍历 com.openai.* 的已加载类，导出字段/方法名里带功能关键词的，用于定位混淆类。 */
    private fun scanLoadedClasses(cl: ClassLoader) {
        val keys = listOf("temporary", "personaliz", "reasoning", "thinking", "plugin", "connector", "maxtokens", "readonly", "readonly")
        var classes = 0
        var hits = 0
        try {
            val pl = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList").apply { isAccessible = true }.get(cl)
            val els = pl.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }.get(pl) as Array<*>
            val found = StringBuilder()
            for (el in els) {
                if (el == null) continue
                var dex: Any? = null
                for (f in el.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(el)
                    if (v != null && v.javaClass.name == "dalvik.system.DexFile") { dex = v; break }
                }
                if (dex == null) continue
                @Suppress("UNCHECKED_CAST")
                val en = dex.javaClass.getMethod("entries").invoke(dex) as java.util.Enumeration<String>
                while (en.hasMoreElements()) {
                    val name = en.nextElement()
                    if (!name.startsWith("com.openai.")) continue
                    classes++
                    val c = try { Class.forName(name, false, cl) } catch (t: Throwable) { continue }
                    val sb = StringBuilder()
                    try {
                        for (f in c.declaredFields) {
                            val n = f.name.lowercase()
                            if (keys.any { n.contains(it) }) sb.append("  F ").append(f.type.simpleName).append(' ').append(f.name).append('\n')
                        }
                    } catch (_: Throwable) { }
                    try {
                        for (mm in c.declaredMethods) {
                            val n = mm.name.lowercase()
                            if (keys.any { n.contains(it) }) sb.append("  M ").append(mm.returnType.simpleName).append(' ').append(mm.name).append('\n')
                        }
                    } catch (_: Throwable) { }
                    if (sb.isNotEmpty()) {
                        hits++
                        ModuleLogger.i("SCAN " + name + "\n" + sb)
                    }
                }
            }
            ModuleLogger.i("class field scan done; classes=" + classes + "; hits=" + hits)
        } catch (t: Throwable) {
            ModuleLogger.w("class field scan failed; reason=" + t.javaClass.simpleName)
        }
    }

    private fun scheduleReportPublish() {
        if (!reportQueued.compareAndSet(false, true)) return
        reportExecutor.schedule({
            reportQueued.set(false)
            publishReport()
        }, REPORT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun publishReport() {
        val json = eventBuffer.toJson()

        // Primary channel: logcat. Always available even when MIUI blocks cross-app IPC.
        // The observation report is metadata-only and bounded, so it is safe to log.
        ModuleLogger.i("observation-report: $json")

        // Secondary channel: ContentProvider to the module app (best effort).
        val ctx = targetContextForIpc ?: return
        try {
            val uri = Uri.parse("content://${ObservationProvider.AUTHORITY}")
            val extras = Bundle().apply { putString(ObservationProvider.KEY_REPORT, json) }
            val result = ctx.contentResolver.call(uri, ObservationProvider.METHOD_PUBLISH, null, extras)
            if (result?.getBoolean(ObservationProvider.KEY_ACCEPTED) != true) {
                ModuleLogger.w("observation report relay not accepted; logcat channel in use")
            }
        } catch (t: Throwable) {
            ModuleLogger.w("observation report relay unavailable (${t.javaClass.simpleName}); logcat channel in use")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
        const val REPORT_DEBOUNCE_MS = 1500L
    }
}
