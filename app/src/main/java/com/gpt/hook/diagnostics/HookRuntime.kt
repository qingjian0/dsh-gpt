package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.security.MessageDigest
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

    /**
     * Applies a validated snapshot. Method ordering and hook IDs are deterministic, so
     * adding/removing an overload cannot accidentally replace a different overload's hook.
     * A failed individual method does not prevent other overloads/rules from being applied.
     */
    @Synchronized
    fun apply(config: HookConfig): Pair<Int, Int> {
        val activeKeys = HashSet<String>()
        var applied = 0
        var failed = 0

        for (rule in config.rules) {
            if (!rule.enabled) continue
            val candidates = try {
                val type = Class.forName(rule.className, false, classLoader)
                type.declaredMethods
                    .filter { methodMatches(it, rule) }
                    .sortedBy { it.toGenericString() }
                    .take(8)
            } catch (t: Throwable) {
                failed++
                ModuleLogger.w("rule lookup failed: id=${rule.id}; reason=${t.javaClass.simpleName}")
                continue
            }

            if (candidates.isEmpty()) {
                failed++
                ModuleLogger.w("rule has no matching methods: id=${rule.id}")
                continue
            }

            for (method in candidates) {
                val signature = method.toGenericString()
                val key = "${rule.id}:$signature"
                activeKeys += key
                try {
                    val hookId = stableHookId(rule.id, signature)
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
                                    events.record(rule, signature, System.nanoTime() - started, failure)
                                }
                            }
                        }
                    // libxposed setId atomically replaces an existing hook with this ID.
                    installed[key] = Installed(handle)
                    applied++
                } catch (t: Throwable) {
                    failed++
                    ModuleLogger.w(
                        "method hook failed: id=${rule.id}; signature=${signature.take(180)}; " +
                            "reason=${t.javaClass.simpleName}"
                    )
                }
            }
        }

        // Retain hooks for failed replacements when their stable key is still requested.
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

    private fun stableHookId(ruleId: String, signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "dsh.$ruleId.$digest"
    }
}
