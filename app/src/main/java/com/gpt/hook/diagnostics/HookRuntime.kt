package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Applies declarative metadata-only observation rules using libxposed interceptors. */
class HookRuntime(
    private val framework: XposedInterface,
    private val classLoader: ClassLoader,
    private val events: EventBuffer
) {
    private data class Installed(val handle: XposedInterface.HookHandle)
    private val installed = ConcurrentHashMap<String, Installed>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    @Synchronized
    fun apply(config: HookConfig): Pair<Int, Int> {
        val activeKeys = HashSet<String>()
        var applied = 0
        var failed = 0
        for (rule in config.rules) {
            if (!rule.enabled) continue
            try {
                val type = Class.forName(rule.className, false, classLoader)
                val candidates = type.declaredMethods.filter { methodMatches(it, rule) }.take(8)
                require(candidates.isNotEmpty()) { "No matching method found" }
                candidates.forEachIndexed { index, method ->
                    val key = "${rule.id}:${method.toGenericString()}"
                    activeKeys += key
                    val hookId = "dsh.${rule.id}.$index"
                    val handle = framework.hook(method)
                        .setId(hookId)
                        .intercept { chain ->
                            val count = counters.getOrPut(key) { AtomicLong() }.incrementAndGet()
                            if ((count - 1) % rule.sampleEvery != 0L) {
                                chain.proceed()
                            } else {
                                val started = System.nanoTime()
                                var failure: Throwable? = null
                                try {
                                    chain.proceed()
                                } catch (t: Throwable) {
                                    failure = t
                                    throw t
                                } finally {
                                    events.record(rule, method.toGenericString(), System.nanoTime() - started, failure)
                                }
                            }
                        }
                    // setId atomically replaces the previous hook on this executable.
                    installed[key] = Installed(handle)
                    applied++
                }
            } catch (t: Throwable) {
                failed++
                ModuleLogger.w("rule rejected: id=${rule.id}; reason=${t.javaClass.simpleName}")
            }
        }
        installed.entries.toList().forEach { (key, value) ->
            if (key !in activeKeys) {
                runCatching { value.handle.unhook() }
                installed.remove(key)
                counters.remove(key)
            }
        }
        return applied to failed
    }

    @Synchronized
    fun clear() {
        installed.values.forEach { runCatching { it.handle.unhook() } }
        installed.clear()
        counters.clear()
    }

    private fun methodMatches(method: Method, rule: HookRule): Boolean {
        if (method.name != rule.methodName) return false
        if (rule.parameterTypes.isEmpty()) return true
        return method.parameterTypes.map { it.name } == rule.parameterTypes
    }
}
