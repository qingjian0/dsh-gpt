package com.gpt.hook

import com.gpt.hook.diagnostics.EventBuffer
import com.gpt.hook.diagnostics.HookConfigParser
import com.gpt.hook.diagnostics.HookRuntime
import com.gpt.hook.diagnostics.ModuleLogger
import com.gpt.hook.diagnostics.RemoteConfigWatcher
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** libxposed entry point for the DSH declarative diagnostics runtime. */
class MainModule : XposedModule() {
    private val eventBuffer = EventBuffer()
    private var runtime: HookRuntime? = null
    private var configWatcher: RemoteConfigWatcher? = null

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
        runtime = hookRuntime
        configWatcher?.close()
        configWatcher = RemoteConfigWatcher(this) { source ->
            val config = HookConfigParser.parse(source)
            val (applied, failed) = hookRuntime.apply(config)
            ModuleLogger.i("configuration refreshed; rules=${config.rules.size}; hooks=$applied; failed=$failed")
        }.also { it.start() }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
    }
}
