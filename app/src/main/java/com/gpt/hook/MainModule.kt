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
            val cmd = java.io.File("/proc/self/cmdline").readText().trim().substringBefore(':')
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
