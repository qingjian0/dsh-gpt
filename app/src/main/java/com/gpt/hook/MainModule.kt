package com.gpt.hook

import android.content.SharedPreferences
import com.gpt.hook.diagnostics.EventBuffer
import com.gpt.hook.diagnostics.HookConfigParser
import com.gpt.hook.diagnostics.HookRuntime
import com.gpt.hook.diagnostics.ModuleLogger
import com.gpt.hook.diagnostics.RemoteConfigLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.Executors

/** libxposed entry point for the DSH declarative diagnostics runtime. */
class MainModule : XposedModule() {
    private val eventBuffer = EventBuffer()
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-config-apply").apply { isDaemon = true }
    }
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        ModuleLogger.framework = this
        ModuleLogger.i("module loaded; process=${param.processName}; api=${getApiVersion()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        ModuleLogger.i("target package ready; package=${param.packageName}; classLoader=${param.classLoader.javaClass.name}")

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

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
    }
}
