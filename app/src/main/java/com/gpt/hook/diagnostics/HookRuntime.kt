package com.gpt.hook.diagnostics

import io.github.libxposed.api.XposedInterface
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import org.json.JSONArray
import org.json.JSONObject
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
                installed.keys.filter { it.startsWith("${rule.id}:") }.forEach(activeKeys::add)
                ModuleLogger.w("rule lookup failed: id=${rule.id}; reason=${t.javaClass.simpleName}")
                continue
            }

            if (candidates.isEmpty()) {
                failed++
                installed.keys.filter { it.startsWith("${rule.id}:") }.forEach(activeKeys::add)
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


    /**
     * Inspect class and method metadata in the target process. No method is invoked and
     * no arguments, return values, fields, or application data are read.
     */
    fun discover(prefix: String, exactClassName: String?, limit: Int): JSONObject {
        require(prefix.startsWith("com.openai.") && prefix.length <= 180) {
            "Discovery prefix must be within com.openai.*"
        }
        require(limit in 1..500) { "limit must be between 1 and 500" }
        if (exactClassName != null) {
            require(exactClassName.startsWith("com.openai.") && exactClassName.length <= 240) {
                "Discovery class must be within com.openai.*"
            }
        }

        val names: Sequence<String> = if (exactClassName != null) {
            sequenceOf(exactClassName)
        } else {
            val indexed = try {
                dexClassNames()
                    .filter { it.startsWith(prefix) && !it.contains("$") }
                    .distinct()
                    .take(20_000)
                    .sorted()
                    .toList()
            } catch (t: Throwable) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "class_index_unavailable")
                    .put("reason", t.javaClass.simpleName)
                    .put("hint", "Retry with className to inspect a known class directly")
            }
            indexed.asSequence()
        }
        val classes = JSONArray()
        var records = 0
        var inspected = 0
        for (name in names) {
            if (records >= limit) break
            inspected++
            val type = try {
                Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                continue
            }
            val methods = JSONArray()
            val candidates = runCatching {
                type.declaredMethods.sortedBy { it.toGenericString() }
            }.getOrDefault(emptyList())
            for (method in candidates) {
                if (records >= limit) break
                methods.put(method.toGenericString().take(1000))
                records++
            }
            if (methods.length() > 0 || exactClassName != null) {
                classes.put(JSONObject()
                    .put("className", type.name)
                    .put("methodCount", methods.length())
                    .put("methods", methods))
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("prefix", prefix)
            .put("exactClassName", exactClassName ?: JSONObject.NULL)
            .put("classCount", classes.length())
            .put("recordCount", records)
            .put("classesInspected", inspected)
            .put("classes", classes)
    }

    private fun dexClassNames(): Sequence<String> = sequence {
        val base = classLoader as? BaseDexClassLoader
            ?: throw IllegalStateException("Target class loader is not a BaseDexClassLoader")
        val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {
            isAccessible = true
        }
        val pathList = pathListField.get(base)
        val elementsField = pathList.javaClass.getDeclaredField("dexElements").apply {
            isAccessible = true
        }
        val elements = elementsField.get(pathList) as? Array<*>
            ?: throw IllegalStateException("Dex elements are unavailable")
        for (element in elements) {
            if (element == null) continue
            val dexField = runCatching {
                element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
            }.getOrNull() ?: continue
            val dex = runCatching { dexField.get(element) as? DexFile }.getOrNull() ?: continue
            val entries = runCatching { dex.entries() }.getOrNull() ?: continue
            while (entries.hasMoreElements()) yield(entries.nextElement())
        }
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
