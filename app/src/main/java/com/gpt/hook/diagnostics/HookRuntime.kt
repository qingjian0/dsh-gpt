package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Applies declarative metadata-only observation rules using libxposed interceptors. */
data class HookApplyReport(
    val added: Int,
    val replaced: Int,
    val retained: Int,
    val removed: Int,
    val failed: Int
) {
    val activeCount: Int get() = added + replaced + retained
}

class HookRuntime(
    private val framework: XposedInterface,
    private val classLoader: ClassLoader,
    private val events: EventBuffer
) {
    private data class Installed(
        val handle: XposedInterface.HookHandle,
        val fingerprint: String,
        val ruleId: String,
        val signature: String,
        val sampleEvery: Int
    )
    private val installed = ConcurrentHashMap<String, Installed>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val discoveryEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DexDiscoveryEngine(classLoader)
    }

    /**
     * Applies a validated snapshot. Method ordering and hook IDs are deterministic, so
     * adding/removing an overload cannot accidentally replace a different overload's hook.
     * A failed individual method does not prevent other overloads/rules from being applied.
     */
    fun apply(config: HookConfig): Pair<Int, Int> {
        val report = applyWithReport(config)
        return report.activeCount to report.failed
    }

    @Synchronized
    fun applyWithReport(config: HookConfig): HookApplyReport {
        val activeKeys = HashSet<String>()
        var added = 0
        var replaced = 0
        var retained = 0
        var removed = 0
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
                val oldKeys = installed.keys.filter { it.startsWith("${rule.id}:") }
                activeKeys.addAll(oldKeys)
                retained += oldKeys.size
                ModuleLogger.w("rule lookup failed: id=${rule.id}; reason=${t.javaClass.simpleName}")
                continue
            }

            if (candidates.isEmpty()) {
                failed++
                val oldKeys = installed.keys.filter { it.startsWith("${rule.id}:") }
                activeKeys.addAll(oldKeys)
                retained += oldKeys.size
                ModuleLogger.w("rule has no matching methods: id=${rule.id}")
                continue
            }

            for (method in candidates) {
                val signature = method.toGenericString()
                val key = "${rule.id}:$signature"
                activeKeys += key
                val fingerprint = "${rule.className}|${rule.methodName}|${rule.parameterTypes.joinToString(",")}|sample=${rule.sampleEvery}"
                val existing = installed[key]
                if (existing?.fingerprint == fingerprint) {
                    retained++
                    continue
                }
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
                    installed[key] = Installed(handle, fingerprint, rule.id, signature, rule.sampleEvery)
                    if (existing == null) added++ else replaced++
                } catch (t: Throwable) {
                    failed++
                    if (existing != null) retained++
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
                removed++
            }
        }
        return HookApplyReport(added, replaced, retained, removed, failed)
    }

    /** Bounded metadata-only class, method, constructor and field discovery. */
    fun discover(prefix: String, exactClassName: String? = null, limit: Int = 100, cursor: Int = 0): JSONObject =
        discoveryEngine.discover(prefix, exactClassName, limit, cursor)

    /**
     * A truthful inventory of Hook handles currently retained by this Runtime.
     * This is distinct from configured rules and from discovery candidates.
     */
    fun runtimeSnapshot(): JSONObject {
        val hooks = org.json.JSONArray()
        val entries = installed.entries.toList().sortedBy { it.key }
        entries.take(MAX_STATUS_HOOKS).forEach { (key, value) ->
            hooks.put(JSONObject()
                .put("ruleId", value.ruleId)
                .put("signature", value.signature.take(256))
                .put("fingerprint", value.fingerprint.take(256))
                .put("sampleEvery", value.sampleEvery)
                .put("invocations", counters[key]?.get() ?: 0L)
                .put("state", "installed"))
        }
        return JSONObject()
            .put("state", "active")
            .put("activeHookCount", entries.size)
            .put("inventoryTruncated", entries.size > MAX_STATUS_HOOKS)
            .put("hooks", hooks)
            .put("capturedAtMs", System.currentTimeMillis())
    }

    @Synchronized
    fun clear(): Int {
        val removed = installed.size
        installed.values.forEach { runCatching { it.handle.unhook() } }
        installed.clear()
        counters.clear()
        return removed
    }

    private fun methodMatches(method: Method, rule: HookRule): Boolean {
        if (method.name != rule.methodName) return false
        if (rule.parameterTypes.isEmpty()) return true
        return method.parameterTypes.map { it.name } == rule.parameterTypes
    }

    private companion object {
        const val MAX_STATUS_HOOKS = 64
    }

    private fun stableHookId(ruleId: String, signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "dsh.$ruleId.$digest"
    }
}
