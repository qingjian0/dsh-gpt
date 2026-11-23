package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional metadata-only ClassLoader watcher.
 *
 * It hooks only ClassLoader.loadClass(String, boolean), filters to com.openai.*,
 * deduplicates names, and stops recording after a fixed event budget. It never
 * initializes, invokes, or reflects over the loaded class.
 */
class ClassLoadWatcher(
    private val module: XposedModule,
    private val eventBuffer: EventBuffer,
    private val maxEvents: Int = 256
) {
    private val gate = ClassLoadEventGate(maxEvents)
    private val enabled = AtomicBoolean(false)
    @Volatile private var handle: XposedInterface.HookHandle? = null

    @Synchronized
    fun enable(): Boolean {
        if (enabled.get() && handle != null) return false
        val method = ClassLoader::class.java.getDeclaredMethod(
            "loadClass",
            String::class.java,
            java.lang.Boolean.TYPE
        )
        val installed = module.hook(method)
            .setId("dsh.classloader.metadata-watch")
            .intercept { chain: XposedInterface.Chain ->
                val name = chain.getArg(0) as? String
                if (name == null || !name.startsWith("com.openai.")) {
                    chain.proceed()
                } else {
                    val started = System.nanoTime()
                    val loaded = chain.proceed()
                    if (loaded is Class<*> && gate.accept(name)) {
                        eventBuffer.recordClassLoaded(name, System.nanoTime() - started)
                    }
                    loaded
                }
            }
        handle = installed
        enabled.set(true)
        return true
    }

    @Synchronized
    fun disable(): Boolean {
        val old = handle ?: run {
            enabled.set(false)
            gate.clear()
            return false
        }
        handle = null
        enabled.set(false)
        gate.clear()
        runCatching { old.unhook() }
        return true
    }

    fun isEnabled(): Boolean = enabled.get()
}
