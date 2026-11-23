package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches class loading and immediately attaches matching live rules.
 * The callback only receives the Class object; it never invokes app methods or reads values.
 */
class ClassLoadWatcher(
    private val module: XposedModule,
    private val eventBuffer: EventBuffer,
    private val onClassLoaded: (Class<*>) -> Unit,
    private val maxEvents: Int = 256
) {
    private val gate = ClassLoadEventGate(maxEvents)
    private val seenForHooks = ConcurrentHashMap.newKeySet<String>()
    private val callbackBusy = ThreadLocal.withInitial { false }
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
            .setId("dsh.classloader.dynamic-hook-watch")
            .intercept { chain: XposedInterface.Chain ->
                val name = chain.getArg(0) as? String
                if (name == null || !name.startsWith("com.openai.")) {
                    chain.proceed()
                } else {
                    val loaded = chain.proceed()
                    if (loaded is Class<*> && !callbackBusy.get()) {
                        if (gate.accept(name)) eventBuffer.recordClassLoaded(name, 0L)
                        if (seenForHooks.add(name)) {
                            callbackBusy.set(true)
                            try {
                                onClassLoaded(loaded)
                            } catch (t: Throwable) {
                                ModuleLogger.w("dynamic class Hook callback failed; class=$name; reason=${t.javaClass.simpleName}")
                            } finally {
                                callbackBusy.set(false)
                            }
                        }
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
            seenForHooks.clear()
            return false
        }
        handle = null
        enabled.set(false)
        gate.clear()
        seenForHooks.clear()
        runCatching { old.unhook() }
        return true
    }

    fun isEnabled(): Boolean = enabled.get()
}
