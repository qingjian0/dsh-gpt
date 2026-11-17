package com.gpt.hook

import android.app.Application
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
            val source = prefs.getString("hook_config", null)
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

    /** onPackageReady precedes Application.attach; capture its Context for the module IPC. */
    private fun installContextBootstrap() {
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            this.hook(attach).setId("dsh.context.bootstrap").intercept { chain: XposedInterface.Chain ->
                val targetContext = chain.getArg(0) as? Context
                val result = chain.proceed()
                if (targetContextForIpc == null && targetContext != null) {
                    // Keep the target app's own Context: ContentResolver.call() therefore
                    // crosses Binder with the target UID that the provider authenticates.
                    targetContextForIpc = targetContext.applicationContext ?: targetContext
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
        val ctx = targetContextForIpc ?: return
        try {
            val uri = Uri.parse("content://${ObservationProvider.AUTHORITY}")
            val extras = Bundle().apply { putString(ObservationProvider.KEY_REPORT, eventBuffer.toJson()) }
            val result = ctx.contentResolver.call(uri, ObservationProvider.METHOD_PUBLISH, null, extras)
            if (result?.getBoolean(ObservationProvider.KEY_ACCEPTED) != true) {
                ModuleLogger.w("observation report was not accepted by module app")
            }
        } catch (t: Throwable) {
            ModuleLogger.w("observation report publish failed: ${t.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
        const val REPORT_DEBOUNCE_MS = 1500L
    }
}
