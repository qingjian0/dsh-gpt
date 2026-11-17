package com.gpt.hook

import com.gpt.hook.diagnostics.AiResearchBridge
import com.gpt.hook.diagnostics.EventBuffer
import com.gpt.hook.diagnostics.HookConfigParser
import com.gpt.hook.diagnostics.HookRuntime
import com.gpt.hook.diagnostics.ModuleLogger
import com.gpt.hook.diagnostics.RemoteConfigLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** libxposed entry point for the DSH declarative diagnostics runtime. */
class MainModule : XposedModule() {
    private val eventBuffer = EventBuffer()
    private var runtime: HookRuntime? = null
    private val aiBridge by lazy { AiResearchBridge(eventBuffer) }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        ModuleLogger.framework = this
        ModuleLogger.i("module loaded; process=${param.processName}; api=${getApiVersion()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        ModuleLogger.i("target package ready; package=${param.packageName}; classLoader=${param.classLoader.javaClass.name}")

        runtime = HookRuntime(this, param.classLoader, eventBuffer)
        val source = RemoteConfigLoader.read(this)
        if (source == null) {
            ModuleLogger.i("no dynamic rules supplied; runtime idle")
            return
        }
        try {
            val config = HookConfigParser.parse(source)
            val (applied, failed) = runtime!!.apply(config)
            ModuleLogger.i("configuration applied; rules=${config.rules.size}; hooks=$applied; failed=$failed")
        } catch (t: Throwable) {
            ModuleLogger.w("configuration rejected; reason=${t.javaClass.simpleName}")
        }
    }

    /** Stable JSON export entry point for a future in-app/share UI. */
    internal fun exportAiObservations(): String = aiBridge.exportObservations()

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
    }
}
