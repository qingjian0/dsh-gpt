package com.gpt.hook

import android.content.pm.ApplicationInfo
import com.gpt.hook.diagnostics.ModuleLogger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Minimal libxposed entry point. Keep this class name in java_init.list. */
class MainModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        ModuleLogger.framework = this
        ModuleLogger.i("module loaded; process=${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_PACKAGE) return
        ModuleLogger.i("target package ready; package=${param.packageName}; process=${param.processName}; classLoader=${param.defaultClassLoader.javaClass.name}")
    }

    private companion object {
        const val TARGET_PACKAGE = "com.openai.chatgpt"
    }
}
